from datetime import datetime, timedelta, timezone

import pytest

from app.content import (build_items, item_scores, jaccard, layered, price_band, price_thresholds, profile_scores,
                         similarity, user_profile)
from app.domain import WeightedEvent

CUTOFF = datetime(2026, 10, 13, tzinfo=timezone.utc)


def row(pid, ptype, sport, gender="MEN", price=500_000, brand="nike", category=None, colors=("BLACK",)):
    return {"id": pid, "productType": ptype, "category": category or ptype.lower(), "brand": brand,
            "gender": gender, "sportCodes": [sport], "colorFamilies": list(colors), "minPrice": price}


CATALOG = [
    row("shoe-run", "SPORTS_SHOES", "RUNNING", price=1_400_000),
    row("shoe-run-2", "SPORTS_SHOES", "RUNNING", price=1_500_000),
    row("short-run", "SPORTS_BOTTOM", "RUNNING", price=360_000),
    row("bottle-run", "SPORTS_ACCESSORY", "RUNNING", gender="UNISEX", price=220_000),
    row("shoe-foot", "SPORTS_SHOES", "FOOTBALL", price=1_200_000, brand="adidas"),
]
ITEMS = build_items(CATALOG)


def test_jaccard_and_empty_groups():
    assert jaccard(frozenset({"a", "b"}), frozenset({"b", "c"})) == pytest.approx(1 / 3)
    assert jaccard(frozenset(), frozenset()) == 0.0


def test_price_bands_split_candidates_into_quartiles():
    thresholds = price_thresholds([100, 200, 300, 400])
    assert thresholds == [100, 200, 300]
    assert [price_band(p, thresholds) for p in (100, 150, 300, 400)] == ["Q1", "Q2", "Q3", "Q4"]
    assert price_band(None, thresholds) is None


def test_similarity_is_one_for_identical_and_symmetric():
    same = build_items([row("a", "SPORTS_TOP", "RUNNING"), row("b", "SPORTS_TOP", "RUNNING")])
    assert similarity(same["a"], same["b"]) == pytest.approx(1.0)
    assert similarity(ITEMS["shoe-run"], ITEMS["shoe-foot"]) == similarity(ITEMS["shoe-foot"], ITEMS["shoe-run"])


def test_similar_mode_prefers_same_sport_and_type():
    scores = item_scores(["shoe-run"], ITEMS, [r["id"] for r in CATALOG], "SIMILAR")
    assert "shoe-run" not in scores
    assert max(scores, key=scores.get) == "shoe-run-2"
    assert set(scores) == {"shoe-run-2", "short-run", "bottle-run", "shoe-foot"}  # SIMILAR drops nothing


def test_complement_mode_drops_same_type_and_keeps_same_sport_first():
    scores = item_scores(["shoe-run"], ITEMS, [r["id"] for r in CATALOG], "COMPLEMENT")
    assert "shoe-run-2" not in scores and "shoe-foot" not in scores
    assert set(scores) == {"short-run", "bottle-run"}
    assert scores["short-run"] > scores["bottle-run"]  # same gender beats UNISEX


def test_items_without_attributes_still_score_on_remaining_groups():
    bare = build_items([{"id": "x", "category": "c1", "brand": "nike", "minPrice": 1},
                        {"id": "y", "category": "c1", "brand": "nike", "minPrice": 1}])
    assert 0 < item_scores(["x"], bare, ["y"], "SIMILAR")["y"] < 1


def test_unknown_mode_is_rejected():
    with pytest.raises(ValueError):
        item_scores(["shoe-run"], ITEMS, ["short-run"], "OTHER")


def test_user_profile_uses_only_past_weighted_events():
    events = [WeightedEvent("shoe-run", "ADD_TO_CART", CUTOFF - timedelta(days=1), "u"),
              WeightedEvent("shoe-foot", "PURCHASE", CUTOFF + timedelta(days=1), "u"),
              WeightedEvent("shoe-foot", "RECOMMENDATION_IMPRESSION", CUTOFF - timedelta(days=1), "u")]
    profile = user_profile(events, ITEMS, CUTOFF)
    assert sum(profile.values()) == pytest.approx(1.0)
    assert ("sport", "RUNNING") in profile and ("sport", "FOOTBALL") not in profile
    assert user_profile([], ITEMS, CUTOFF) == {}


def test_profile_scores_rank_matching_products_first():
    events = [WeightedEvent("short-run", "PURCHASE", CUTOFF - timedelta(days=2), "u")]
    scores = profile_scores(user_profile(events, ITEMS, CUTOFF), ITEMS, ["shoe-run", "shoe-foot", "bottle-run"])
    assert scores["shoe-run"] > scores["shoe-foot"]


def test_layered_fills_gaps_in_order_without_duplicates():
    assert layered([["a"], ["a", "b"], ["c", "d"]], 3) == [("a", 0), ("b", 1), ("c", 2)]
    assert layered([[], ["x"]], 5) == [("x", 1)]
