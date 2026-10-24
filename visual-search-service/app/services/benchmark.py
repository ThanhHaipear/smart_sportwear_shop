from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Iterable


METRIC_K_VALUES = (1, 5, 10)
REQUIRED_TEST_GROUPS = frozenset({"tops", "bottoms", "uniforms", "shoes", "accessories"})


@dataclass(frozen=True, slots=True)
class RankedCandidate:
    product_id: str
    image_id: str
    matched_image_url: str
    similarity: float
    category_group: str | None = None
    color_family: str | None = None


@dataclass(frozen=True, slots=True)
class BenchmarkObservation:
    query_id: str
    expected_product_ids: frozenset[str]
    expected_group: str
    expected_color_families: frozenset[str]
    judgment_complete: bool
    candidates: tuple[RankedCandidate, ...]
    latency_ms: float
    error: str | None = None

    @property
    def is_unknown(self) -> bool:
        return self.judgment_complete and not self.expected_product_ids


def percentile_nearest_rank(values: Iterable[float], percentile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    rank = max(1, math.ceil(percentile * len(ordered)))
    return float(ordered[rank - 1])


def _round_ratio(numerator: int | float, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return round(numerator / denominator, 4)


def _positive_metrics(observations: list[BenchmarkObservation]) -> dict[str, object]:
    complete = [item for item in observations if item.judgment_complete and item.expected_product_ids]
    precision: dict[str, float | None] = {}
    recall: dict[str, float | None] = {}
    accuracy: dict[str, float | None] = {}

    for k in METRIC_K_VALUES:
        precision_total = 0.0
        recall_total = 0.0
        hits = 0
        for item in complete:
            returned = tuple(candidate.product_id for candidate in item.candidates[:k])
            relevant_returned = len(item.expected_product_ids.intersection(returned))
            precision_total += relevant_returned / k
            recall_total += relevant_returned / len(item.expected_product_ids)
            hits += relevant_returned > 0
        precision[f"at{k}"] = _round_ratio(precision_total, len(complete))
        recall[f"at{k}"] = _round_ratio(recall_total, len(complete))
        if k in (1, 5):
            accuracy[f"top{k}"] = _round_ratio(hits, len(complete))

    category_cases = [item for item in complete if item.expected_group != "unknown"]
    category_hits = sum(
        bool(item.candidates and item.candidates[0].category_group == item.expected_group)
        for item in category_cases
    )
    color_cases = [item for item in complete if item.expected_color_families]
    color_hits = sum(
        bool(
            item.candidates
            and item.candidates[0].color_family
            and item.candidates[0].color_family in item.expected_color_families
        )
        for item in color_cases
    )
    top5_failures = sum(
        not item.expected_product_ids.intersection(candidate.product_id for candidate in item.candidates[:5])
        for item in complete
    )
    return {
        "sampleCount": len(complete),
        "precision": precision,
        "recall": recall,
        "accuracy": accuracy,
        "categoryAccuracy": {
            "value": _round_ratio(category_hits, len(category_cases)),
            "sampleCount": len(category_cases),
        },
        "colorAccuracy": {
            "value": _round_ratio(color_hits, len(color_cases)),
            "sampleCount": len(color_cases),
            "status": "MEASURED" if color_cases else "N/A_MISSING_EXPECTED_COLOR_LABELS",
        },
        "retrievalFailureRateAt5": {
            "value": _round_ratio(top5_failures, len(complete)),
            "failureCount": top5_failures,
            "sampleCount": len(complete),
        },
    }


def evaluate_benchmark(
    observations: list[BenchmarkObservation],
    *,
    split: str,
    minimum_test_positive_samples: int = 100,
    minimum_test_unknown_samples: int = 20,
) -> dict[str, object]:
    successful = [item for item in observations if item.error is None]
    complete = [item for item in successful if item.judgment_complete]
    positives = [item for item in complete if item.expected_product_ids]
    unknowns = [item for item in complete if not item.expected_product_ids]
    groups = sorted({item.expected_group for item in positives})
    unknown_rejections = sum(not item.candidates for item in unknowns)
    request_failures = len(observations) - len(successful)

    metrics: dict[str, object] = {
        "split": split,
        "sampleCount": len(observations),
        "completeJudgmentCount": len(complete),
        "positive": _positive_metrics(successful),
        "unknown": {
            "sampleCount": len(unknowns),
            "rejectedCount": unknown_rejections,
            "rejectionRate": _round_ratio(unknown_rejections, len(unknowns)),
        },
        "requestFailureRate": {
            "value": _round_ratio(request_failures, len(observations)),
            "failureCount": request_failures,
            "sampleCount": len(observations),
        },
        "latencyMs": {
            "p50": round(percentile_nearest_rank((item.latency_ms for item in successful), 0.50), 2),
            "p95": round(percentile_nearest_rank((item.latency_ms for item in successful), 0.95), 2),
            "sampleCount": len(successful),
        },
        "groups": groups,
        "slices": {
            group: _positive_metrics([item for item in successful if item.expected_group == group])
            for group in groups
        },
    }

    failures: list[str] = []
    if split.upper() == "TEST":
        if len(positives) < minimum_test_positive_samples:
            failures.append(f"positive sample count must be at least {minimum_test_positive_samples}")
        missing_groups = REQUIRED_TEST_GROUPS.difference(groups)
        if missing_groups:
            failures.append(f"missing required groups: {', '.join(sorted(missing_groups))}")
        if len(unknowns) < minimum_test_unknown_samples:
            failures.append(f"unknown sample count must be at least {minimum_test_unknown_samples}")
        positive_metrics = metrics["positive"]
        recall_at_5 = positive_metrics["recall"]["at5"]  # type: ignore[index]
        category_accuracy = positive_metrics["categoryAccuracy"]["value"]  # type: ignore[index]
        if recall_at_5 is None or recall_at_5 < 0.80:
            failures.append("recall@5 is below 80%")
        if category_accuracy is None or category_accuracy < 0.90:
            failures.append("category accuracy is below 90%")
        latency = metrics["latencyMs"]
        if latency["p95"] > 3000:  # type: ignore[index]
            failures.append("p95 latency exceeds 3000 ms")
        if request_failures:
            failures.append("one or more benchmark requests failed")

    metrics["acceptance"] = {
        "eligible": split.upper() == "TEST",
        "passed": split.upper() == "TEST" and not failures,
        "failures": failures,
        "note": "DEV is tuning-only and cannot satisfy final acceptance." if split.upper() != "TEST" else None,
    }
    return metrics
