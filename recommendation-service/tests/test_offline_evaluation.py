import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from app.domain import WeightedEvent, popularity_scores, rank_by_score
from app.metrics import (bootstrap_ci, case_metrics, coverage_at_k, grouped_mean, hit_rate_at_k, ndcg_at_k,
                         precision_at_k, recall_at_k)
from scripts.evaluate_offline import association_config, popularity_config, random_config, training_data

CUTOFF = datetime(2026, 10, 13, tzinfo=timezone.utc)
SERVICE_ROOT = Path(__file__).resolve().parents[1]


def at(days_before: float) -> str:
    return (CUTOFF - timedelta(days=days_before)).strftime("%Y-%m-%dT%H:%M:%SZ")


# Worked example from Mission.md C.9: L = [A, B, C, D, E], R = {B, E, F}.
def test_metrics_match_hand_calculation():
    predicted, relevant = list("ABCDE"), {"B": 1, "E": 1, "F": 1}
    assert precision_at_k(predicted, relevant, 5) == pytest.approx(0.4)
    assert recall_at_k(predicted, relevant, 5) == pytest.approx(2 / 3)
    assert hit_rate_at_k(predicted, relevant, 5) == 1.0
    assert ndcg_at_k(predicted, relevant, 5) == pytest.approx(0.478, abs=1e-3)
    assert ndcg_at_k(list("BEACD"), relevant, 5) == pytest.approx(0.765, abs=1e-3)


def test_short_list_counts_missing_positions_as_misses():
    assert precision_at_k(["A"], {"A": 1}, 5) == pytest.approx(0.2)
    assert ndcg_at_k(["A"], {"A": 1}, 5) == 1.0


def test_graded_ndcg_rewards_purchase_first():
    relevant = {"P": 2, "C": 1}
    assert ndcg_at_k(["P", "C"], relevant, 5) == 1.0
    assert ndcg_at_k(["C", "P"], relevant, 5) < 1.0


def test_coverage_grouped_mean_and_bootstrap():
    assert coverage_at_k([["A", "B"], ["B", "C"]], ["A", "B", "C", "D"], 1) == 0.5
    assert grouped_mean([1.0, 0.0, 1.0], ["o1", "o1", "o2"]) == pytest.approx(0.75)
    low, high = bootstrap_ci([0.0, 1.0] * 20, [f"o{i}" for i in range(40)], seed=1)
    assert 0.0 < low <= 0.5 <= high < 1.0
    assert bootstrap_ci([0.0, 1.0] * 20, [f"o{i}" for i in range(40)], seed=1) == (low, high)
    assert case_metrics([], {"A": 1}, 5)["hit_rate"] == 0.0


def test_popularity_ignores_events_at_or_after_cutoff():
    events = [WeightedEvent("a", "VIEW", CUTOFF - timedelta(days=1)),
              WeightedEvent("b", "PURCHASE", CUTOFF),
              WeightedEvent("b", "PURCHASE", CUTOFF + timedelta(days=1))]
    assert popularity_scores(events, CUTOFF) == {"a": 1.0}


def test_popularity_weights_and_half_life():
    events = [WeightedEvent("a", "ADD_TO_CART", CUTOFF - timedelta(days=7)),
              WeightedEvent("a", "RECOMMENDATION_IMPRESSION", CUTOFF - timedelta(days=1))]
    assert popularity_scores(events, CUTOFF) == {"a": 3.0}
    assert popularity_scores(events, CUTOFF, half_life_days=7) == {"a": pytest.approx(1.5)}


def test_view_right_after_click_is_counted_once():
    events = [WeightedEvent("a", "RECOMMENDATION_CLICK", CUTOFF - timedelta(minutes=10), "u"),
              WeightedEvent("a", "VIEW", CUTOFF - timedelta(minutes=10) + timedelta(seconds=2), "u"),
              WeightedEvent("a", "VIEW", CUTOFF - timedelta(minutes=5), "u")]
    assert popularity_scores(events, CUTOFF) == {"a": 2.0}


def test_rank_by_score_only_returns_candidates():
    ranking = rank_by_score({"a": 5, "x": 9, "b": 1}, ["a", "b", "c"], {"b"}, 10)
    assert ranking == ["a", "c"]  # x is not a candidate (e.g. out of stock), b is excluded


def test_training_data_drops_future_rows():
    interactions = [{"userRef": "u", "eventType": "VIEW", "productId": "a", "occurredAt": at(1)},
                    {"userRef": "u", "eventType": "VIEW", "productId": "z", "occurredAt": at(-1)}]
    baskets = [{"orderId": "o1", "userRef": "u", "productIds": ["a", "b"], "occurredAt": at(2)},
               {"orderId": "o2", "userRef": "u", "productIds": ["c", "z"], "occurredAt": at(0)}]
    data = training_data(interactions, baskets, CUTOFF)
    assert {e.product_id for e in data.events} == {"a", "b"}
    assert data.baskets == [{"a", "b"}] and data.bought_by_user == {"u": {"a", "b"}}


def test_algorithms_never_return_source_or_non_candidates():
    baskets = [{"orderId": f"o{i}", "userRef": "u", "productIds": ["a", "b", "x"], "occurredAt": at(3)}
               for i in range(5)]
    data = training_data([], baskets, CUTOFF)
    candidates = ["a", "b", "c"]
    popularity = popularity_config(data, CUTOFF, None, {"PURCHASE": 5.0}, "test")
    configs = [random_config(data), popularity, association_config(data, 0.01, 0.1),
               association_config(data, 0.01, 0.1, popularity)]
    for config in configs:
        ranking = config.fbt("a", candidates, "case")
        assert "a" not in ranking and set(ranking) <= set(candidates)
    assert configs[2].fbt("a", candidates, "case") == ["b"]          # pure rules: no fill
    assert configs[3].fbt("a", candidates, "case") == ["b", "c"]     # filled with popularity
    assert popularity.home("u", candidates) == ["c"]                # bought a, b in training


def test_test_split_requires_final_flag():
    result = subprocess.run([sys.executable, "scripts/evaluate_offline.py", "--dataset", "x", "--split", "test",
                             "--stage", "phase6"], cwd=SERVICE_ROOT, capture_output=True, text=True)
    assert result.returncode != 0 and "--final" in result.stderr
