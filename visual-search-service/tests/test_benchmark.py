from app.services.benchmark import (
    BenchmarkObservation,
    RankedCandidate,
    evaluate_benchmark,
    percentile_nearest_rank,
)


def observation(
    index: int,
    *,
    correct_rank: int | None = 1,
    group: str = "tops",
    latency: float = 100,
    colors: frozenset[str] = frozenset(),
) -> BenchmarkObservation:
    expected = f"product-{index}"
    candidates = [
        RankedCandidate(f"other-{rank}", f"image-{rank}", "https://example.test/image", 1 - rank / 100, group)
        for rank in range(1, 11)
    ]
    if correct_rank is not None:
        candidates[correct_rank - 1] = RankedCandidate(
            expected, f"image-{index}", "https://example.test/match", 0.99, group, "BLUE"
        )
    return BenchmarkObservation(
        query_id=f"query-{index}",
        expected_product_ids=frozenset({expected}),
        expected_group=group,
        expected_color_families=colors,
        judgment_complete=True,
        candidates=tuple(candidates),
        latency_ms=latency,
    )


def unknown(index: int, *, rejected: bool) -> BenchmarkObservation:
    candidates = () if rejected else (
        RankedCandidate("other", "image", "https://example.test/image", 0.5, "tops"),
    )
    return BenchmarkObservation(
        query_id=f"unknown-{index}",
        expected_product_ids=frozenset(),
        expected_group="unknown",
        expected_color_families=frozenset(),
        judgment_complete=True,
        candidates=candidates,
        latency_ms=50,
    )


def test_nearest_rank_percentile() -> None:
    assert percentile_nearest_rank(range(1, 101), 0.95) == 95


def test_metrics_cover_precision_recall_accuracy_unknown_latency_and_slices() -> None:
    observations = [
        observation(1, correct_rank=1, group="tops", colors=frozenset({"BLUE"}), latency=80),
        observation(2, correct_rank=3, group="shoes", latency=120),
        observation(3, correct_rank=None, group="accessories", latency=100),
        unknown(1, rejected=True),
        unknown(2, rejected=False),
    ]

    metrics = evaluate_benchmark(observations, split="DEV")

    assert metrics["positive"]["precision"] == {"at1": 0.3333, "at5": 0.1333, "at10": 0.0667}
    assert metrics["positive"]["recall"] == {"at1": 0.3333, "at5": 0.6667, "at10": 0.6667}
    assert metrics["positive"]["accuracy"] == {"top1": 0.3333, "top5": 0.6667}
    assert metrics["positive"]["colorAccuracy"] == {"value": 1.0, "sampleCount": 1, "status": "MEASURED"}
    assert metrics["unknown"]["rejectionRate"] == 0.5
    assert metrics["latencyMs"] == {"p50": 80.0, "p95": 120.0, "sampleCount": 5}
    assert set(metrics["slices"]) == {"tops", "shoes", "accessories"}
    assert metrics["acceptance"]["eligible"] is False
    assert metrics["acceptance"]["passed"] is False


def test_test_acceptance_requires_independent_coverage_and_targets() -> None:
    groups = ("tops", "bottoms", "uniforms", "shoes", "accessories")
    observations = [observation(index, group=groups[index % 5], latency=200) for index in range(100)]
    observations.extend(unknown(index, rejected=True) for index in range(20))

    metrics = evaluate_benchmark(observations, split="TEST")

    assert metrics["positive"]["recall"]["at5"] == 1.0
    assert metrics["positive"]["categoryAccuracy"]["value"] == 1.0
    assert metrics["acceptance"]["passed"] is True


def test_test_acceptance_rejects_small_incomplete_or_slow_run() -> None:
    observations = [observation(1, correct_rank=None, latency=3001), unknown(1, rejected=False)]
    metrics = evaluate_benchmark(observations, split="TEST")

    failures = metrics["acceptance"]["failures"]
    assert metrics["acceptance"]["passed"] is False
    assert any("positive sample count" in failure for failure in failures)
    assert any("missing required groups" in failure for failure in failures)
    assert any("unknown sample count" in failure for failure in failures)
    assert any("recall@5" in failure for failure in failures)
    assert any("p95 latency" in failure for failure in failures)
