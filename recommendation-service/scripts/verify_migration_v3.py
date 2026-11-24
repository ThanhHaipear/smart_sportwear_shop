"""Verify migration V3 (evaluation_runs metric columns) on a disposable PostgreSQL, or check a live schema.

Review R3 (PR #1). Two modes:

1. Rehearsal (default) on a LOCAL scratch server, e.g. `docker run postgres:17`:
       python scripts/verify_migration_v3.py --admin-url postgresql://postgres:pw@127.0.0.1:55432/postgres
   - clean:   fresh database, V1 -> V2 -> V3, runtime role writes through POST /internal/v1/evaluations.
   - upgrade: fresh database at V2 with pre-V3 evaluation rows written by the OLD app statement; shows the
              NEW app fails on V2 (why V3 must run first); applies V3 twice; checks old rows unchanged and
              the runtime role writes through the API; checks the OLD app still writes on V3 (app rollback
              needs no schema rollback); checks the new range constraint; runs the permission probe.
   Refuses any host other than localhost so it can never touch Supabase. Writes a JSON evidence file.

2. Read-only check of a live schema (what the assigned person runs on Supabase before/after V3):
       python scripts/verify_migration_v3.py --check-only "<migration or runtime role DSN>"
   Prints whether the seven V3 columns and the range constraint exist. Never writes.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import psycopg
from psycopg import sql

SERVICE = Path(__file__).resolve().parents[1]
ROOT = SERVICE.parent
sys.path.insert(0, str(SERVICE))

MIGRATIONS = SERVICE / "migrations"
V1, V2, V3 = (MIGRATIONS / name for name in (
    "V1__create_recommendation_schema.sql", "V2__backfill_legacy_recommendation.sql",
    "V3__extend_evaluation_runs.sql"))
V3_COLUMNS = {"task": "character varying", "segment": "character varying", "case_count": "integer",
              "hit_rate_at_k": "double precision", "ndcg_at_k": "double precision",
              "coverage_at_k": "double precision", "report_sha256": "character"}
V3_CONSTRAINT = "chk_evaluation_runs_v3_ranges"
PRE_V3_COLUMNS = ["id", "model_version", "dataset_version", "k", "precision_at_k", "recall_at_k", "evaluated_at"]
# The statement the app used before V3 (main @ 32493515, app/repository.py save_evaluation).
OLD_APP_INSERT = ("insert into evaluation_runs(model_version,dataset_version,k,precision_at_k,recall_at_k,"
                  "evaluated_at) values(%s,%s,%s,%s,%s,%s)")
RUNTIME_PASSWORD = "migration-rehearsal-only"
INTERNAL_TOKEN = "rehearsal-token"
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}


# --- read-only schema check (safe on Supabase) ---------------------------------------------------------------
def schema_state(connection: psycopg.Connection) -> dict:
    rows = connection.execute(
        "select column_name, data_type from information_schema.columns "
        "where table_schema='recommendation' and table_name='evaluation_runs'").fetchall()
    present = dict(rows)
    constraint = connection.execute(
        "select 1 from pg_constraint c join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace "
        "where n.nspname='recommendation' and t.relname='evaluation_runs' and c.conname=%s", (V3_CONSTRAINT,)).fetchone()
    missing = sorted(name for name in V3_COLUMNS if name not in present)
    wrong_type = sorted(name for name, kind in V3_COLUMNS.items() if name in present and present[name] != kind)
    return {"tableExists": bool(present), "v3ColumnsPresent": sorted(set(V3_COLUMNS) & set(present)),
            "v3ColumnsMissing": missing, "v3ColumnsWrongType": wrong_type,
            "rangeConstraint": constraint is not None,
            "v3Applied": bool(present) and not missing and not wrong_type and constraint is not None}


def check_only(url: str) -> int:
    with psycopg.connect(url) as connection:
        connection.read_only = True
        state = schema_state(connection) | {"evaluationRows": connection.execute(
            "select count(*) from recommendation.evaluation_runs").fetchone()[0]}
    print(json.dumps(state, indent=2))
    return 0 if state["v3Applied"] else 1


# --- rehearsal on a disposable local server ------------------------------------------------------------------
def with_database(url: str, database: str, user: str | None = None, password: str | None = None) -> str:
    parts = urlsplit(url)
    netloc = parts.netloc.rsplit("@", 1)[-1]
    if user:
        netloc = f"{user}:{password}@{netloc}"
    else:
        netloc = parts.netloc
    return urlunsplit((parts.scheme, netloc, f"/{database}", parts.query, ""))


def apply(url: str, *files: Path) -> None:
    with psycopg.connect(url) as connection:
        for path in files:
            connection.execute(path.read_text("utf-8"))


def fresh_database(admin_url: str, name: str) -> str:
    with psycopg.connect(admin_url, autocommit=True) as connection:
        connection.execute(sql.SQL("drop database if exists {} with (force)").format(sql.Identifier(name)))
        connection.execute(sql.SQL("create database {}").format(sql.Identifier(name)))
    return with_database(admin_url, name)


def enable_runtime_login(admin_db_url: str, database: str) -> str:
    """Give recommendation_app LOGIN on the scratch server, like apply-phase-5-supabase.py does on Supabase."""
    with psycopg.connect(admin_db_url, autocommit=True) as connection:
        connection.execute(sql.SQL("alter role recommendation_app login password {}").format(sql.Literal(RUNTIME_PASSWORD)))
        connection.execute(sql.SQL("grant connect on database {} to recommendation_app").format(sql.Identifier(database)))
        connection.execute("alter role recommendation_app set search_path=recommendation,pg_catalog")
    return with_database(admin_db_url, database, "recommendation_app", RUNTIME_PASSWORD)


def api_client(runtime_url: str):
    from fastapi.testclient import TestClient

    from app.config import Settings, get_settings
    from app.main import create_app

    app = create_app()
    app.dependency_overrides[get_settings] = lambda: Settings(
        recommendation_enabled=True, recommendation_database_url=runtime_url,
        recommendation_internal_token=INTERNAL_TOKEN)
    return TestClient(app)


def post_evaluation(runtime_url: str, model_version: str) -> dict:
    one, two, three = (str(uuid4()) for _ in range(3))
    body = {"modelVersion": model_version, "datasetVersion": "migration-rehearsal", "k": 2,
            "cases": [{"predicted": [one, two], "relevant": [one]}, {"predicted": [three, one], "relevant": [two]}]}
    response = api_client(runtime_url).post("/internal/v1/evaluations", json=body,
                                            headers={"X-Internal-Service-Token": INTERNAL_TOKEN})
    with psycopg.connect(runtime_url) as connection:
        row = connection.execute(
            "select precision_at_k, recall_at_k, hit_rate_at_k, ndcg_at_k, case_count from evaluation_runs "
            "where model_version=%s", (model_version,)).fetchone()
    return {"status": response.status_code, "storedRow": None if row is None else {
        "precision": row[0], "recall": row[1], "hitRate": row[2], "ndcg": row[3], "caseCount": row[4]}}


def evaluation_rows(url: str) -> list[list]:
    with psycopg.connect(url) as connection:
        query = sql.SQL("select {} from recommendation.evaluation_runs order by id").format(
            sql.SQL(",").join(map(sql.Identifier, PRE_V3_COLUMNS)))
        return [[str(value) for value in row] for row in connection.execute(query).fetchall()]


def table_counts(url: str) -> dict:
    tables = ["catalog_products", "user_interactions", "order_baskets", "association_rules", "processed_events",
              "recommendation_models", "evaluation_runs"]
    with psycopg.connect(url) as connection:
        return {t: connection.execute(sql.SQL("select count(*) from recommendation.{}").format(
            sql.Identifier(t))).fetchone()[0] for t in tables}


def check(results: list, name: str, passed: bool, detail=None) -> None:
    results.append({"check": name, "passed": bool(passed), **({"detail": detail} if detail is not None else {})})


def clean_install(admin_url: str, results: list) -> None:
    url = fresh_database(admin_url, "rec_v3_clean")
    apply(url, V1, V2, V3)
    with psycopg.connect(url) as connection:
        state = schema_state(connection)
    check(results, "clean: V1->V2->V3 creates all seven columns and the constraint", state["v3Applied"], state)
    runtime = enable_runtime_login(url, "rec_v3_clean")
    written = post_evaluation(runtime, "clean-v3")
    check(results, "clean: runtime role writes through POST /internal/v1/evaluations",
          written["status"] == 200 and written["storedRow"] and written["storedRow"]["ndcg"] is not None, written)


def upgrade(admin_url: str, results: list) -> None:
    url = fresh_database(admin_url, "rec_v3_upgrade")
    apply(url, V1, V2)
    runtime = enable_runtime_login(url, "rec_v3_upgrade")

    # Data written by the pre-V3 app, as the runtime role.
    now = datetime.now(timezone.utc)
    with psycopg.connect(runtime) as connection:
        for version, precision, recall in (("legacy-a", 0.4, 0.5), ("legacy-b", 0.2, 0.25)):
            connection.execute(OLD_APP_INSERT, (version, "legacy-dataset", 5, precision, recall, now))
        connection.execute("insert into catalog_products(product_id,source_version,status,available_quantity,snapshot) "
                           "values(%s,1,'ACTIVE',3,'{}'::jsonb)", (uuid4(),))
        connection.execute("insert into user_interactions(event_id,occurred_at,anonymous_id,event_type,product_id,"
                           "source,data_source) values(%s,now(),'anon-rehearsal','VIEW',%s,'TEST','DEMO')",
                           (uuid4(), uuid4()))
    before_rows, before_counts = evaluation_rows(url), table_counts(url)

    # The new app before V3: shows why the migration must be applied first.
    new_on_v2 = post_evaluation_safely(runtime, "new-app-on-v2")
    check(results, "upgrade: NEW app on V2 schema fails to write (apply V3 before deploying)",
          new_on_v2["status"] != 200 or new_on_v2.get("storedRow") is None, new_on_v2)

    apply(url, V3)
    apply(url, V3)  # idempotent: a second run (e.g. a retried deployment) must not fail or change data
    with psycopg.connect(url) as connection:
        state = schema_state(connection)
        new_values = connection.execute(
            "select count(*) from recommendation.evaluation_runs where task is not null or segment is not null "
            "or case_count is not null or hit_rate_at_k is not null or ndcg_at_k is not null "
            "or coverage_at_k is not null or report_sha256 is not null").fetchone()[0]
    check(results, "upgrade: V3 applied twice, seven columns and constraint present", state["v3Applied"], state)
    check(results, "upgrade: pre-V3 evaluation rows unchanged", evaluation_rows(url) == before_rows,
          {"rows": len(before_rows)})
    check(results, "upgrade: new columns are NULL on pre-V3 rows", new_values == 0)
    check(results, "upgrade: row counts of every table unchanged", table_counts(url) == before_counts, before_counts)

    written = post_evaluation(runtime, "new-app-on-v3")
    check(results, "upgrade: runtime role writes through POST /internal/v1/evaluations",
          written["status"] == 200 and written["storedRow"] and written["storedRow"]["ndcg"] is not None, written)

    with psycopg.connect(runtime) as connection:
        connection.execute(OLD_APP_INSERT, ("old-app-on-v3", "legacy-dataset", 5, 0.3, 0.3, now))
    check(results, "rollback: OLD app still writes on V3 schema (no schema rollback needed)", True)

    try:
        with psycopg.connect(runtime) as connection:
            connection.execute("insert into evaluation_runs(model_version,dataset_version,k,precision_at_k,recall_at_k,"
                               "ndcg_at_k) values('bad','x',5,0.1,0.1,1.5)")
        rejected = False
    except psycopg.errors.CheckViolation:
        rejected = True
    check(results, "upgrade: range constraint rejects ndcg_at_k = 1.5", rejected)

    probe = subprocess.run([sys.executable, str(SERVICE / "scripts" / "verify_schema_permissions.py"), runtime],
                           capture_output=True, text=True)
    check(results, "upgrade: permission probe (runtime writes own schema, cannot write public)",
          probe.returncode == 0, (probe.stdout or probe.stderr).strip())


def post_evaluation_safely(runtime_url: str, model_version: str) -> dict:
    try:
        return post_evaluation(runtime_url, model_version)
    except psycopg.Error as error:  # TestClient re-raises the server-side database error
        return {"status": "error", "error": type(error).__name__, "message": str(error).splitlines()[0]}


def rehearse(admin_url: str, evidence: Path | None) -> int:
    host = urlsplit(admin_url).hostname
    if host not in LOCAL_HOSTS:
        raise SystemExit(f"refusing to run the rehearsal on {host}: use a local scratch server (--check-only is read-only)")
    with psycopg.connect(admin_url) as connection:
        server = connection.execute("show server_version").fetchone()[0]
    results: list = []
    clean_install(admin_url, results)
    upgrade(admin_url, results)
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
    report = {"migration": V3.name, "postgres": server, "commit": commit,
              "ranAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
              "passed": all(r["passed"] for r in results), "checks": results}
    text = json.dumps(report, indent=2, default=str)
    print(text)
    if evidence:
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(text + "\n", encoding="utf-8")
    return 0 if report["passed"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--admin-url", help="superuser DSN of a LOCAL scratch PostgreSQL (rehearsal)")
    mode.add_argument("--check-only", metavar="DSN", help="read-only V3 schema check of a live database")
    parser.add_argument("--evidence", type=Path, help="write the rehearsal report to this JSON file")
    args = parser.parse_args()
    return check_only(args.check_only) if args.check_only else rehearse(args.admin_url, args.evidence)


if __name__ == "__main__":
    sys.exit(main())
