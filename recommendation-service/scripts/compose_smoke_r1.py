"""Review R1 smoke test: Chatbot reaches recommendation-service by its Compose name, and falls back to Catalog.

Runs the real chatbot-service and recommendation-service images from docker-compose.yml (plus
scripts/compose_smoke/compose.r1-smoke.yml), with a scratch PostgreSQL in Docker and a Catalog stub:

  1. both profiles on, Recommendation enabled  -> Chatbot "related" mode returns Recommendation's list
  2a. Recommendation running but disabled (503) -> Chatbot falls back to Catalog relatedProducts
  2b. "recommendation" profile off (no container) -> Chatbot falls back to Catalog relatedProducts

Usage (Docker Desktop running):  .venv\\Scripts\\python.exe scripts\\compose_smoke_r1.py [--evidence FILE] [--keep]
Everything it starts is removed at the end unless --keep. It never touches Supabase.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import psycopg

SERVICE = Path(__file__).resolve().parents[1]
ROOT = SERVICE.parent
sys.path.insert(0, str(SERVICE / "scripts"))
import verify_migration_v3 as migrations  # noqa: E402

PROJECT = "r1smoke"
PG_CONTAINER, PG_PORT, PG_PASSWORD = "r1smoke-postgres", 55433, "smoke"
SMOKE_DIR = SERVICE / "scripts" / "compose_smoke"
SOURCE = "11111111-1111-4111-8111-111111111111"
PRODUCTS = [SOURCE, "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333"]
CATALOG_FALLBACK_ID = "99999999-9999-4999-8999-999999999999"


def run(*args: str, env: dict | None = None, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=ROOT, env={**os.environ, **(env or {})}, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", check=check)


def compose(*args: str, profiles=("chatbot", "recommendation"), env: dict | None = None, check: bool = True):
    files = ["-f", "docker-compose.yml", "-f", str(SMOKE_DIR / "compose.r1-smoke.yml")]
    profile_args = [arg for p in profiles for arg in ("--profile", p)]
    return run("docker", "compose", "-p", PROJECT, *files, *profile_args, *args, env=env, check=check)


def start_database() -> str:
    run("docker", "rm", "-f", PG_CONTAINER, check=False)
    run("docker", "run", "-d", "--name", PG_CONTAINER, "-e", f"POSTGRES_PASSWORD={PG_PASSWORD}",
        "-p", f"{PG_PORT}:5432", "postgres:17-alpine")
    admin = f"postgresql://postgres:{PG_PASSWORD}@127.0.0.1:{PG_PORT}/postgres"
    for _ in range(60):
        try:
            with psycopg.connect(admin, connect_timeout=2):
                break
        except psycopg.OperationalError:
            time.sleep(1)
    url = migrations.fresh_database(admin, "rec_smoke")
    migrations.apply(url, migrations.V1, migrations.V2, migrations.V3)
    migrations.enable_runtime_login(url, "rec_smoke")
    with psycopg.connect(url) as connection:
        for index, product_id in enumerate(PRODUCTS):
            snapshot = {"id": product_id, "name": f"Smoke product {index + 1}", "slug": f"smoke-{index + 1}",
                        "availableQuantity": 5, "status": "ACTIVE", "minPrice": 100.0 + index}
            connection.execute("insert into recommendation.catalog_products(product_id,source_version,status,"
                               "available_quantity,purchase_count,snapshot) values(%s,1,'ACTIVE',5,%s,%s)",
                               (product_id, len(PRODUCTS) - index, json.dumps(snapshot)))
    return (f"postgresql://recommendation_app:{migrations.RUNTIME_PASSWORD}"
            f"@host.docker.internal:{PG_PORT}/rec_smoke")


def probe(env: dict, profiles=("chatbot", "recommendation")) -> dict:
    """Run app.services.recommendation_service.recommend_related inside a one-off chatbot container."""
    result = compose("run", "--rm", "--no-deps", "-v", f"{SMOKE_DIR / 'probe.py'}:/app/probe.py:ro",
                     "chatbot-service", "python", "probe.py", profiles=profiles, env=env)
    output = result.stdout
    return json.loads(output[output.index("{"):]) | {"log": [line for line in result.stderr.splitlines()
                                                             if "fell back" in line]}


def recommendation_status_codes(env: dict) -> list[str]:
    logs = compose("logs", "recommendation-service", env=env, check=False).stdout
    return [line.split('" ')[-1].strip() for line in logs.splitlines() if "GET /api/v1/recommendations" in line]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--keep", action="store_true", help="leave containers running for inspection")
    args = parser.parse_args()
    checks: list[dict] = []
    try:
        env = {"SMOKE_RECOMMENDATION_DATABASE_URL": start_database(), "SMOKE_RECOMMENDATION_ENABLED": "true"}
        compose("build", "chatbot-service", "recommendation-service", env=env)
        compose("up", "-d", "--no-deps", "recommendation-service", "catalog-stub", env=env)
        time.sleep(3)

        case1 = probe(env)
        ids = [item[0] for item in case1["items"]]
        checks.append({"case": "1 both profiles on, Recommendation enabled",
                       "passed": case1["RECOMMENDATION_API_BASE_URL"] == "http://recommendation-service:8091"
                       and case1["requestId"] is not None and bool(ids) and CATALOG_FALLBACK_ID not in ids
                       and SOURCE not in ids, "result": case1})

        env_disabled = env | {"SMOKE_RECOMMENDATION_ENABLED": "false"}
        compose("up", "-d", "--no-deps", "recommendation-service", env=env_disabled)
        time.sleep(3)
        case2a = probe(env_disabled)
        codes = recommendation_status_codes(env_disabled)
        checks.append({"case": "2a Recommendation running but disabled (503)",
                       "passed": [i[0] for i in case2a["items"]] == [CATALOG_FALLBACK_ID]
                       and case2a["requestId"] is None and any(code.startswith("503") for code in codes),
                       "result": case2a, "recommendationResponses": codes})

        compose("rm", "-sf", "recommendation-service", env=env)
        case2b = probe(env, profiles=("chatbot",))
        checks.append({"case": "2b recommendation profile off (no container)",
                       "passed": [i[0] for i in case2b["items"]] == [CATALOG_FALLBACK_ID] and case2b["requestId"] is None,
                       "result": case2b})
    finally:
        if not args.keep:
            compose("down", "-v", "--remove-orphans", env={"SMOKE_RECOMMENDATION_DATABASE_URL": "unused"}, check=False)
            run("docker", "rm", "-f", PG_CONTAINER, check=False)

    commit = run("git", "rev-parse", "--short", "HEAD", check=False).stdout.strip()
    report = {"review": "R1", "commit": commit, "ranAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
              "passed": len(checks) == 3 and all(c["passed"] for c in checks), "checks": checks}
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(text + "\n", encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")  # Vietnamese reasons; Windows consoles default to cp1252
    print(text)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
