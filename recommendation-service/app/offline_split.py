"""Temporal split and frozen evaluation cases (docs/recommendation-design.md, section 7).

Pure functions only: no database, no clock. The offline evaluator (phase 9) reads the files built from
these functions, and trains every algorithm on `events_before(cutoff)` only.

Two splits share one dataset:
- DEV:  train = weeks 1-6, evaluate on week 7 (used to choose configurations);
- TEST: train = weeks 1-7, evaluate on week 8 (run once with the chosen configuration).
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timedelta

# Relevance grades for the HOME task. VIEW and WISHLIST are not relevant, so precision, recall and NDCG
# all use the same set R (design 7.4).
HOME_GRADES = {"PURCHASE": 2, "ADD_TO_CART": 1}
# Events that make a user "warm" (design 7.5): any event with weight > 0 (impressions weigh 0).
WARM_EVENT_TYPES = frozenset({"VIEW", "RECOMMENDATION_CLICK", "SEARCH_CLICK", "WISHLIST", "ADD_TO_CART"})


@dataclass(frozen=True)
class Window:
    name: str
    train_until: datetime  # exclusive; training uses events strictly before this instant
    eval_from: datetime    # inclusive
    eval_until: datetime   # exclusive

    def as_dict(self) -> dict:
        return {"name": self.name, "trainUntil": iso(self.train_until),
                "evalFrom": iso(self.eval_from), "evalUntil": iso(self.eval_until)}


def parse(timestamp: str) -> datetime:
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00"))


def iso(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def windows(t0: datetime, weeks: int = 8) -> dict[str, Window]:
    if weeks < 3:
        raise ValueError("need at least one training week plus DEV and TEST weeks")
    week = timedelta(weeks=1)
    dev_from, test_from = t0 + (weeks - 2) * week, t0 + (weeks - 1) * week
    return {"dev": Window("dev", dev_from, dev_from, test_from),
            "test": Window("test", test_from, test_from, t0 + weeks * week)}


def before(rows: list[dict], cutoff: datetime) -> list[dict]:
    """Rows usable for training: strictly earlier than the cutoff."""
    return [row for row in rows if parse(row["occurredAt"]) < cutoff]


def within(rows: list[dict], window: Window) -> list[dict]:
    return [row for row in rows if window.eval_from <= parse(row["occurredAt"]) < window.eval_until]


def candidate_set(products: list[dict], launch_days: dict[str, float], t0: datetime, at: datetime) -> list[str]:
    """Products recommendable at `at`: ACTIVE, in stock and already launched. Sorted for a stable checksum."""
    day = (at - t0).total_seconds() / 86400
    return sorted(p["productId"] for p in products
                  if p["status"] == "ACTIVE" and p["availableQuantity"] > 0
                  and launch_days.get(p["slug"], 0.0) <= day)


def fbt_cases(baskets: list[dict], window: Window, candidates: list[str]) -> tuple[list[dict], dict]:
    """Leave-one-in cases for FREQUENTLY_BOUGHT_TOGETHER (design 7.4, task A).

    Each product of an evaluation-window basket with >= 2 products becomes a source; the other products
    of that basket are the relevant set. Warm = the source appears in at least one training basket.
    """
    allowed = set(candidates)
    trained = {pid for basket in before(baskets, window.train_until) for pid in basket["productIds"]}
    cases, dropped_items, skipped_sources = [], 0, 0
    for basket in within(baskets, window):
        items = basket["productIds"]
        if len(items) < 2:
            continue
        for source in items:
            if source not in allowed:
                skipped_sources += 1
                continue
            relevant = [pid for pid in items if pid != source and pid in allowed]
            dropped_items += sum(1 for pid in items if pid != source and pid not in allowed)
            if not relevant:
                continue
            cases.append({"caseId": f"{basket['orderId']}:{source}", "orderId": basket["orderId"],
                          "sourceProductId": source, "relevant": relevant,
                          "segment": "warm" if source in trained else "cold"})
    return cases, {"droppedRelevantNotInCandidates": dropped_items, "skippedSourcesNotInCandidates": skipped_sources}


def home_cases(interactions: list[dict], baskets: list[dict], window: Window,
               candidates: list[str]) -> tuple[list[dict], dict]:
    """Per-user cases for HOME (design 7.4, task B).

    Relevant = products the user added to cart or bought in the window, graded PURCHASE=2, ADD_TO_CART=1.
    Products the user bought during training are removed from R, matching the serving filter (design 6.4).
    """
    allowed = set(candidates)
    train_events = before(interactions, window.train_until)
    train_baskets = before(baskets, window.train_until)
    warm_users = {row["userRef"] for row in train_events if row["eventType"] in WARM_EVENT_TYPES}
    warm_users |= {basket["userRef"] for basket in train_baskets}
    bought_before: dict[str, set[str]] = {}
    for basket in train_baskets:
        bought_before.setdefault(basket["userRef"], set()).update(basket["productIds"])

    grades: dict[str, dict[str, int]] = {}
    for row in within(interactions, window):
        if row["eventType"] in HOME_GRADES and row.get("userRef"):
            user = grades.setdefault(row["userRef"], {})
            user[row["productId"]] = max(user.get(row["productId"], 0), HOME_GRADES[row["eventType"]])
    for basket in within(baskets, window):
        user = grades.setdefault(basket["userRef"], {})
        for pid in basket["productIds"]:
            user[pid] = max(user.get(pid, 0), HOME_GRADES["PURCHASE"])

    active_users = {row["userRef"] for row in within(interactions, window) if row.get("userRef")}
    cases, removed_repurchase, removed_not_candidate = [], 0, 0
    for user_ref in sorted(grades):
        relevant = {}
        for pid, grade in sorted(grades[user_ref].items()):
            if pid in bought_before.get(user_ref, set()):
                removed_repurchase += 1
            elif pid not in allowed:
                removed_not_candidate += 1
            else:
                relevant[pid] = grade
        if relevant:
            cases.append({"userRef": user_ref, "relevant": relevant,
                          "segment": "warm" if user_ref in warm_users else "cold"})
    return cases, {"activeUsersInWindow": len(active_users),
                   "usersWithoutRelevant": len(active_users | set(grades)) - len(cases),
                   "removedRepurchases": removed_repurchase,
                   "removedNotInCandidates": removed_not_candidate}


def checksum(value) -> str:
    data = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def segment_counts(cases: list[dict]) -> dict:
    counts = {"all": len(cases), "warm": 0, "cold": 0}
    for case in cases:
        counts[case["segment"]] += 1
    return counts
