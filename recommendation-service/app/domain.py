import hashlib
import json
import math
from collections import Counter, defaultdict
from collections.abc import Hashable
from dataclasses import dataclass
from datetime import datetime, timezone
from itertools import combinations
from uuid import UUID


@dataclass(frozen=True)
class Rule:
    antecedent: UUID
    consequent: UUID
    support: float
    confidence: float
    lift: float
    pair_count: int
    model_version: str


@dataclass(frozen=True)
class BasketRow:
    order_id: Hashable
    product_ids: tuple
    occurred_at: datetime


@dataclass(frozen=True)
class ModelMetadata:
    """What `recommendation_models` stores so a model can be traced back to its exact training data."""
    algorithm: str
    config: dict
    training_cutoff_at: datetime
    training_event_checksum: str


def training_checksum(rows: list[BasketRow]) -> str:
    """SHA-256 of the training baskets in a canonical form (sorted orders, sorted products, UTC seconds)."""
    canonical = sorted(
        ({"orderId": str(row.order_id), "productIds": sorted(str(p) for p in row.product_ids),
          "occurredAt": row.occurred_at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
         for row in rows), key=lambda item: item["orderId"])
    payload = json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def mine_association_rules(
    baskets: list[set[UUID]], model_version: str, min_support: float = 0.01,
    min_confidence: float = 0.1, min_lift: float = 1.0,
) -> list[Rule]:
    baskets = [set(basket) for basket in baskets if basket]
    if not baskets:
        return []
    product_counts: Counter[UUID] = Counter()
    pair_counts: Counter[tuple[UUID, UUID]] = Counter()
    for basket in baskets:
        product_counts.update(basket)
        pair_counts.update(combinations(sorted(basket, key=str), 2))
    total = len(baskets)
    rules: list[Rule] = []
    for (left, right), pair_count in pair_counts.items():
        for antecedent, consequent in ((left, right), (right, left)):
            support = pair_count / total
            confidence = pair_count / product_counts[antecedent]
            lift = confidence / (product_counts[consequent] / total)
            if support >= min_support and confidence >= min_confidence and lift >= min_lift:
                rules.append(Rule(antecedent, consequent, support, confidence, lift, pair_count, model_version))
    return sorted(rules, key=lambda rule: (-rule.confidence, -rule.lift, -rule.support, str(rule.consequent)))


def rank_rules(rules: list[Rule], sources: set[UUID], limit: int) -> list[Rule]:
    best: dict[UUID, Rule] = {}
    for rule in rules:
        if rule.antecedent not in sources or rule.consequent in sources:
            continue
        current = best.get(rule.consequent)
        key = lambda value: (value.confidence, value.lift, value.support, value.pair_count)
        if current is None or key(rule) > key(current):
            best[rule.consequent] = rule
    return sorted(best.values(), key=lambda rule: (-rule.confidence, -rule.lift, -rule.support, str(rule.consequent)))[:limit]


# Implicit-feedback weights (docs/recommendation-design.md, section 4). Impressions weigh 0.
EVENT_WEIGHTS = {"VIEW": 1.0, "RECOMMENDATION_CLICK": 1.0, "SEARCH_CLICK": 1.0, "WISHLIST": 2.0,
                 "ADD_TO_CART": 3.0, "PURCHASE": 5.0}
# The single alternative weight set allowed on DEV, reported as a sensitivity check only.
ALTERNATIVE_EVENT_WEIGHTS = {"VIEW": 1.0, "RECOMMENDATION_CLICK": 1.0, "SEARCH_CLICK": 1.0, "WISHLIST": 1.0,
                             "ADD_TO_CART": 2.0, "PURCHASE": 3.0}
CLICK_EVENT_TYPES = frozenset({"RECOMMENDATION_CLICK", "SEARCH_CLICK"})
CLICK_VIEW_DEDUP_SECONDS = 60


@dataclass(frozen=True)
class WeightedEvent:
    product_id: Hashable
    event_type: str
    occurred_at: datetime
    actor: Hashable = None


def drop_views_after_clicks(events: list[WeightedEvent]) -> list[WeightedEvent]:
    """A click opens the product page, which records a VIEW; count that pair once (design 4)."""
    last_click: dict[tuple, datetime] = {}
    kept = []
    for event in sorted(events, key=lambda e: e.occurred_at):
        key = (event.actor, event.product_id)
        if event.event_type in CLICK_EVENT_TYPES:
            last_click[key] = event.occurred_at
        elif event.event_type == "VIEW" and event.actor is not None and key in last_click \
                and (event.occurred_at - last_click[key]).total_seconds() <= CLICK_VIEW_DEDUP_SECONDS:
            continue
        kept.append(event)
    return kept


def popularity_scores(events: list[WeightedEvent], cutoff: datetime, half_life_days: float | None = None,
                      weights: dict[str, float] = EVENT_WEIGHTS) -> dict[Hashable, float]:
    """score(i) = sum of w(e) * exp(-ln2 * age / half_life) over events of i strictly before `cutoff`.

    `half_life_days=None` disables decay (plain weighted count). Events at or after the cutoff are ignored,
    so the same call is safe in offline evaluation and in serving.
    """
    decay = math.log(2) / half_life_days if half_life_days else 0.0
    scores: dict[Hashable, float] = {}
    for event in drop_views_after_clicks([e for e in events if e.occurred_at < cutoff]):
        weight = weights.get(event.event_type, 0.0)
        if weight == 0:
            continue
        age_days = (cutoff - event.occurred_at).total_seconds() / 86400
        scores[event.product_id] = scores.get(event.product_id, 0.0) + weight * math.exp(-decay * age_days)
    return scores


def rank_by_score(scores: dict[Hashable, float], candidates: list[Hashable], exclude: set,
                  limit: int) -> list[Hashable]:
    """Candidates not excluded, best score first; ties (and unseen products, score 0) by id for determinism."""
    pool = [item for item in candidates if item not in exclude]
    return sorted(pool, key=lambda item: (-scores.get(item, 0.0), str(item)))[:limit]


def precision_recall_at_k(cases: list[tuple[list[UUID], set[UUID]]], k: int) -> tuple[float, float]:
    precision = []
    recall = []
    for predicted, relevant in cases:
        hits = len(set(predicted[:k]) & relevant)
        precision.append(hits / k)
        recall.append(hits / len(relevant) if relevant else 0.0)
    return sum(precision) / len(precision), sum(recall) / len(recall)
