"""Phase 9: pre-registered final evaluation, paired comparisons and the one-shot TEST guard."""
import json
from pathlib import Path

import pytest

from app.metrics import paired_bootstrap
from scripts.evaluate_offline import COMPARISONS, guard_test_split
from scripts.record_evaluation_runs import rows_from_report

REPO_ROOT = Path(__file__).resolve().parents[2]


def test_paired_bootstrap_detects_consistent_difference():
    groups = [f"o{i}" for i in range(40)]
    better = paired_bootstrap([1.0] * 40, [0.0] * 20 + [1.0] * 20, groups, seed=1)
    assert better["meanDifference"] == 0.5 and better["verdict"] == "left better"
    same = paired_bootstrap([1.0, 0.0] * 20, [0.0, 1.0] * 20, groups, seed=1)
    assert same["verdict"] == "no significant difference"
    assert paired_bootstrap([0.0] * 40, [1.0] * 40, groups, seed=1)["verdict"] == "right better"
    with pytest.raises(ValueError):
        paired_bootstrap([1.0], [1.0, 0.0], ["o"], seed=1)


def test_guard_allows_test_once_and_only_final():
    assert guard_test_split("dev", "phase6", False, [], None) is None
    assert guard_test_split("dev", "final", False, ["x"], None) is None          # DEV dry runs are free
    assert "--final" in guard_test_split("test", "final", False, [], None)
    assert "final stage" in guard_test_split("test", "phase6", True, [], None)
    assert guard_test_split("test", "final", True, [], None) is None
    assert "already evaluated" in guard_test_split("test", "final", True, ["run1.json"], None)
    assert "already evaluated" in guard_test_split("test", "final", True, ["run1.json"], "  ")
    assert guard_test_split("test", "final", True, ["run1.json"], "split file regenerated after bug fix") is None


def test_comparisons_are_preregistered_and_well_formed():
    ids = [row[0] for row in COMPARISONS]
    assert ids == [f"C{i}" for i in range(1, 10)]
    design = (REPO_ROOT / "docs/recommendation-design.md").read_text(encoding="utf-8")
    for cid, rq, task, segment, left, right in COMPARISONS:
        assert f"| {cid} | {rq} |" in design  # every comparison is written in design 7.9
        assert task in ("fbt", "home") and segment in ("all", "warm", "cold") and left != right


def test_report_rows_cover_every_segment_and_k():
    report = {"runAt": "2026-11-17T10:00:00+00:00", "dataset": "synthetic-v1", "split": "test",
              "protocol": {"kValues": [5, 10]},
              "results": [{"name": "popularity", "tasks": {"fbt": {
                  "all": {"n": 3, **{f"{m}@{k}": {"mean": 0.5} for m in ("precision", "recall", "hit_rate", "ndcg")
                                     for k in (5, 10)}, "coverage@5": 0.2, "coverage@10": 0.4},
                  "cold": {"n": 0}}}}]}
    rows = rows_from_report(report, "a" * 64)
    assert [(r.segment, r.k) for r in rows] == [("all", 5), ("all", 10)]
    assert rows[0].datasetVersion == "synthetic-v1/test" and rows[0].coverageAtK == 0.2
    assert rows[0].reportSha256 == "a" * 64 and len(rows[0].modelVersion) <= 50
