"""Ranking metrics for offline evaluation (docs/recommendation-design.md, section 7.6).

`relevant` maps product -> gain. Binary tasks (FREQUENTLY_BOUGHT_TOGETHER) use gain 1 for every item;
HOME uses PURCHASE=2, ADD_TO_CART=1. Precision, recall and hit rate only look at membership in `relevant`.

DCG uses linear gain and the log2(i + 1) discount (as sklearn.metrics.ndcg_score), so position 1 is not
discounted. A list shorter than K is padded with misses: precision still divides by K.
"""
from __future__ import annotations

import math
import random
from collections.abc import Hashable, Sequence

METRICS = ("precision", "recall", "hit_rate", "ndcg")


def precision_at_k(predicted: Sequence[Hashable], relevant: dict, k: int) -> float:
    return sum(1 for item in predicted[:k] if item in relevant) / k


def recall_at_k(predicted: Sequence[Hashable], relevant: dict, k: int) -> float:
    if not relevant:
        return 0.0
    return sum(1 for item in predicted[:k] if item in relevant) / len(relevant)


def hit_rate_at_k(predicted: Sequence[Hashable], relevant: dict, k: int) -> float:
    return 1.0 if any(item in relevant for item in predicted[:k]) else 0.0


def dcg(gains: Sequence[float]) -> float:
    return sum(gain / math.log2(position + 2) for position, gain in enumerate(gains))


def ndcg_at_k(predicted: Sequence[Hashable], relevant: dict, k: int) -> float:
    ideal = dcg(sorted(relevant.values(), reverse=True)[:k])
    if ideal == 0:
        return 0.0
    return dcg([relevant.get(item, 0) for item in predicted[:k]]) / ideal


def case_metrics(predicted: Sequence[Hashable], relevant: dict, k: int) -> dict[str, float]:
    return {"precision": precision_at_k(predicted, relevant, k), "recall": recall_at_k(predicted, relevant, k),
            "hit_rate": hit_rate_at_k(predicted, relevant, k), "ndcg": ndcg_at_k(predicted, relevant, k)}


def coverage_at_k(lists: Sequence[Sequence[Hashable]], candidates: Sequence[Hashable], k: int) -> float:
    if not candidates:
        return 0.0
    allowed = set(candidates)
    return len({item for items in lists for item in items[:k] if item in allowed}) / len(allowed)


def mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def grouped_mean(values: Sequence[float], groups: Sequence[Hashable]) -> float:
    """Average inside each group first, then across groups (e.g. per order instead of per case)."""
    buckets: dict[Hashable, list[float]] = {}
    for value, group in zip(values, groups):
        buckets.setdefault(group, []).append(value)
    return mean([mean(bucket) for bucket in buckets.values()])


def bootstrap_ci(values: Sequence[float], groups: Sequence[Hashable], seed: int, resamples: int = 1000,
                 level: float = 0.95) -> tuple[float, float]:
    """Percentile bootstrap of the per-case mean, resampling whole groups (orders or users)."""
    buckets: dict[Hashable, list[float]] = {}
    for value, group in zip(values, groups):
        buckets.setdefault(group, []).append(value)
    keys = sorted(buckets, key=str)
    if not keys:
        return 0.0, 0.0
    rng = random.Random(seed)
    estimates = []
    for _ in range(resamples):
        total = count = 0.0
        for key in rng.choices(keys, k=len(keys)):
            total += sum(buckets[key])
            count += len(buckets[key])
        estimates.append(total / count)
    estimates.sort()
    low = estimates[int((1 - level) / 2 * resamples)]
    high = estimates[min(resamples - 1, int((1 + level) / 2 * resamples))]
    return low, high


def paired_bootstrap(left: Sequence[float], right: Sequence[float], groups: Sequence[Hashable], seed: int,
                     resamples: int = 1000, level: float = 0.95) -> dict:
    """Mean of (left - right) over the same cases, with a percentile CI from resampling whole groups.

    Both algorithms are scored on identical cases, so the difference is paired: resampling an order (or user)
    takes that unit's cases for both sides at once. `better` is decided only when the CI excludes 0.
    """
    if len(left) != len(right) or len(left) != len(groups):
        raise ValueError("paired values and groups must have the same length")
    differences = [a - b for a, b in zip(left, right)]
    low, high = bootstrap_ci(differences, groups, seed, resamples, level)
    verdict = "left better" if low > 0 else "right better" if high < 0 else "no significant difference"
    return {"meanDifference": round(mean(differences), 4), "ci95": [round(low, 4), round(high, 4)],
            "n": len(differences), "verdict": verdict}
