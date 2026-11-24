import json
from datetime import datetime, timezone
from pathlib import Path

from app.offline_split import before, candidate_set, checksum, fbt_cases, home_cases, parse, windows, within

T0 = datetime(2026, 9, 1, tzinfo=timezone.utc)
WINDOWS = windows(T0, 8)
TEST = WINDOWS["test"]          # train < 2026-10-20, evaluate [2026-10-20, 2026-10-27)
REPO_ROOT = Path(__file__).resolve().parents[2]


def event(user, kind, product, at):
    return {"userRef": user, "eventType": kind, "productId": product, "occurredAt": at}


def basket(order, user, products, at):
    return {"orderId": order, "userRef": user, "productIds": sorted(products), "occurredAt": at}


def test_windows_follow_weeks():
    dev = WINDOWS["dev"]
    assert dev.train_until == dev.eval_from == parse("2026-10-13T00:00:00Z")
    assert dev.eval_until == TEST.train_until == TEST.eval_from == parse("2026-10-20T00:00:00Z")
    assert TEST.eval_until == parse("2026-10-27T00:00:00Z")


def test_cutoff_is_strict_and_windows_do_not_overlap():
    rows = [event("u", "VIEW", "a", "2026-10-19T23:59:59Z"), event("u", "VIEW", "a", "2026-10-20T00:00:00Z"),
            event("u", "VIEW", "a", "2026-10-27T00:00:00Z")]
    assert before(rows, TEST.train_until) == rows[:1]
    assert within(rows, TEST) == rows[1:2]


def test_candidates_respect_status_stock_and_launch():
    products = [
        {"productId": "a", "slug": "a", "status": "ACTIVE", "availableQuantity": 3},
        {"productId": "b", "slug": "b", "status": "INACTIVE", "availableQuantity": 3},
        {"productId": "c", "slug": "c", "status": "ACTIVE", "availableQuantity": 0},
        {"productId": "d", "slug": "d", "status": "ACTIVE", "availableQuantity": 3},
    ]
    launch = {"d": 49.0}
    assert candidate_set(products, launch, T0, WINDOWS["dev"].eval_from) == ["a"]
    assert candidate_set(products, launch, T0, TEST.eval_from) == ["a", "d"]


def test_fbt_leave_one_in_with_warm_and_cold_sources():
    baskets = [
        basket("o1", "u1", ["a", "b"], "2026-10-01T10:00:00Z"),          # training
        basket("o2", "u2", ["a", "c", "n"], "2026-10-21T10:00:00Z"),     # evaluation, n is new
        basket("o3", "u3", ["b"], "2026-10-22T10:00:00Z"),               # single item: ignored
    ]
    cases, excluded = fbt_cases(baskets, TEST, ["a", "b", "c", "n"])
    by_source = {case["sourceProductId"]: case for case in cases}
    assert set(by_source) == {"a", "c", "n"}
    assert by_source["a"]["relevant"] == ["c", "n"] and by_source["a"]["segment"] == "warm"
    assert by_source["n"]["segment"] == "cold" and by_source["c"]["segment"] == "cold"
    assert excluded == {"droppedRelevantNotInCandidates": 0, "skippedSourcesNotInCandidates": 0}


def test_fbt_drops_items_outside_candidates():
    baskets = [basket("o", "u", ["a", "x"], "2026-10-21T10:00:00Z")]
    cases, excluded = fbt_cases(baskets, TEST, ["a"])
    assert cases == []
    assert excluded == {"droppedRelevantNotInCandidates": 1, "skippedSourcesNotInCandidates": 1}


def test_home_grades_take_maximum_and_skip_training_purchases():
    interactions = [
        event("u1", "VIEW", "a", "2026-10-02T10:00:00Z"),
        event("u1", "ADD_TO_CART", "b", "2026-10-21T10:00:00Z"),
        event("u1", "ADD_TO_CART", "c", "2026-10-21T10:01:00Z"),
        event("u1", "ADD_TO_CART", "z", "2026-10-21T10:02:00Z"),
        event("u2", "VIEW", "a", "2026-10-21T10:00:00Z"),      # view only: no relevant products
        event("u3", "ADD_TO_CART", "a", "2026-10-22T10:00:00Z"),
    ]
    baskets = [basket("o1", "u1", ["z"], "2026-10-03T10:00:00Z"),
               basket("o2", "u1", ["c"], "2026-10-21T10:05:00Z")]
    cases, excluded = home_cases(interactions, baskets, TEST, ["a", "b", "c", "z"])
    by_user = {case["userRef"]: case for case in cases}
    assert by_user["u1"] == {"userRef": "u1", "relevant": {"b": 1, "c": 2}, "segment": "warm"}
    assert by_user["u3"]["segment"] == "cold"
    assert "u2" not in by_user
    assert excluded["removedRepurchases"] == 1 and excluded["usersWithoutRelevant"] == 1


def test_no_case_uses_future_information_for_segments():
    # A source bought only after the cutoff must stay cold even if it appears later in many baskets.
    baskets = [basket("o1", "u", ["a", "b"], "2026-10-20T00:00:00Z"),
               basket("o2", "u", ["a", "b"], "2026-10-25T00:00:00Z")]
    cases, _ = fbt_cases(baskets, TEST, ["a", "b"])
    assert {case["segment"] for case in cases} == {"cold"}


def test_checksum_ignores_key_order():
    assert checksum({"a": 1, "b": [1, 2]}) == checksum({"b": [1, 2], "a": 1})


def test_committed_splits_are_reproducible():
    manifest_path = REPO_ROOT / "evidence/recommendation/synthetic-v1-splits-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    from scripts.build_evaluation_split import build
    generator = json.loads((REPO_ROOT / manifest["generatorManifest"]).read_text(encoding="utf-8"))
    splits, _ = build(REPO_ROOT / "datasets/recommendation/synthetic-v1", generator)
    for name, split in splits.items():
        assert split["fbt"]["sha256"] == manifest["splits"][name]["fbt"]["sha256"]
        assert split["home"]["sha256"] == manifest["splits"][name]["home"]["sha256"]
        assert split["candidates"]["sha256"] == manifest["splits"][name]["candidates"]["sha256"]
