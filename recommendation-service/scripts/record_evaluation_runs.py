"""Copy an offline evaluation report into `recommendation.evaluation_runs` (design 7.8).

One row per configuration x task x segment x K. The evidence JSON stays the source of truth; each row keeps
the report's SHA-256 so it can be traced back. Requires migration V3.

Dry run by default (prints the rows); pass --execute to insert them. Re-running with --execute for the same
report is refused, so rows are never duplicated.

Usage:
    python scripts/record_evaluation_runs.py --report ../evidence/recommendation/synthetic-v1-test-final-<stamp>.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.schemas import EvaluationResult  # noqa: E402


def rows_from_report(report: dict, report_sha256: str) -> list[EvaluationResult]:
    evaluated_at = datetime.fromisoformat(report["runAt"])
    dataset_version = f"{report['dataset']}/{report['split']}"
    rows = []
    for result in report["results"]:
        for task, segments in result["tasks"].items():
            for segment, metrics in segments.items():
                if not metrics["n"]:
                    continue
                for k in report["protocol"]["kValues"]:
                    rows.append(EvaluationResult(
                        modelVersion=result["name"], datasetVersion=dataset_version, k=k,
                        precisionAtK=metrics[f"precision@{k}"]["mean"], recallAtK=metrics[f"recall@{k}"]["mean"],
                        hitRateAtK=metrics[f"hit_rate@{k}"]["mean"], ndcgAtK=metrics[f"ndcg@{k}"]["mean"],
                        coverageAtK=metrics[f"coverage@{k}"], task=task, segment=segment, caseCount=metrics["n"],
                        reportSha256=report_sha256, evaluatedAt=evaluated_at))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--execute", action="store_true", help="insert the rows into evaluation_runs")
    args = parser.parse_args()
    data = args.report.read_bytes()
    report_sha256 = hashlib.sha256(data).hexdigest()
    rows = rows_from_report(json.loads(data), report_sha256)
    print(json.dumps({"report": args.report.name, "sha256": report_sha256, "rows": len(rows),
                      "mode": "EXECUTE" if args.execute else "DRY_RUN"}, indent=2))
    if not args.execute:
        for row in rows[:5]:
            print(row.model_dump_json(exclude={"evaluatedAt"}))
        return 0

    from app.config import get_settings
    from app.repository import PostgresRepository
    repository = PostgresRepository(get_settings().recommendation_database_url)
    with repository.connection() as connection:
        existing = connection.execute("select count(*) from evaluation_runs where report_sha256=%s",
                                      (report_sha256,)).fetchone()[0]
    if existing:
        parser.error(f"{existing} rows from this report are already stored")
    for row in rows:
        repository.save_evaluation(row)
    print(f"inserted {len(rows)} rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
