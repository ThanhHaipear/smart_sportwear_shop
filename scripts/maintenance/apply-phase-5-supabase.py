from __future__ import annotations

import argparse
import asyncio
import json
import secrets
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit, urlunsplit

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]


def runtime_dsn(admin_dsn: str, role: str, password: str) -> str:
    parsed = urlsplit(admin_dsn.replace("postgresql+asyncpg://", "postgresql://"))
    suffix = (unquote(parsed.username or "").split(".", 1) + [""])[1]
    username = f"{role}.{suffix}" if suffix else role
    port = f":{parsed.port}" if parsed.port else ""
    return urlunsplit((parsed.scheme, f"{quote(username)}:{quote(password)}@{parsed.hostname}{port}",
                       parsed.path, parsed.query, ""))


def update_env(path: Path, updates: dict[str, str]) -> None:
    lines = path.read_text("utf-8").splitlines() if path.exists() else []
    output, seen = [], set()
    for line in lines:
        key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
        if key in updates:
            output.append(f"{key}={updates[key]}"); seen.add(key)
        else:
            output.append(line)
    if output and output[-1] != "": output.append("")
    output.extend(f"{key}={value}" for key, value in updates.items() if key not in seen)
    path.write_text("\n".join(output) + "\n", encoding="utf-8")


async def apply(admin_dsn: str) -> dict:
    normalized = admin_dsn.replace("postgresql+asyncpg://", "postgresql://")
    password = secrets.token_urlsafe(36)
    internal_token = secrets.token_urlsafe(36)
    connection = await asyncpg.connect(normalized, timeout=20)
    try:
        admin_role = await connection.fetchval("select current_user")
        quoted_admin = await connection.fetchval("select quote_ident($1)", admin_role)
        quoted_password = await connection.fetchval("select quote_literal($1)", password)
        async with connection.transaction():
            await connection.execute("""do $$ begin
                if not exists(select 1 from pg_roles where rolname='recommendation_owner') then
                    create role recommendation_owner nologin;
                end if;
                if not exists(select 1 from pg_roles where rolname='recommendation_app') then
                    create role recommendation_app login;
                end if;
            end $$""")
            await connection.execute(f"grant recommendation_owner to {quoted_admin}")
            await connection.execute(f"alter role recommendation_app login password {quoted_password}")
            database = await connection.fetchval("select quote_ident(current_database())")
            await connection.execute(f"grant connect on database {database} to recommendation_app")
            for name in ("V1__create_recommendation_schema.sql", "V2__backfill_legacy_recommendation.sql"):
                await connection.execute((ROOT / "recommendation-service/migrations" / name).read_text("utf-8"))
            await connection.execute("alter role recommendation_app set search_path=recommendation,pg_catalog")
            counts = dict(await connection.fetchrow("""select
                (select count(*) from recommendation.user_interactions) interaction_count,
                (select count(*) from recommendation.association_rules) rule_count,
                (select count(*) from recommendation.recommendation_logs) log_count"""))
        dsn = runtime_dsn(normalized, "recommendation_app", password)
    finally:
        await connection.close()

    runtime = None
    for attempt in range(6):
        try:
            runtime = await asyncpg.connect(dsn, timeout=20); break
        except asyncpg.InvalidPasswordError:
            if attempt == 5: raise
            await asyncio.sleep(2 ** attempt)
    try:
        attributes = await runtime.fetchrow("select rolsuper,rolcreatedb,rolcreaterole from pg_roles where rolname=current_user")
        permissions = {"role": await runtime.fetchval("select current_user"),
            "superuser": attributes["rolsuper"], "createDatabase": attributes["rolcreatedb"],
            "createRole": attributes["rolcreaterole"],
            "ownedSchemaUsage": await runtime.fetchval("select has_schema_privilege(current_user,'recommendation','USAGE')"),
            "publicCreate": await runtime.fetchval("select has_schema_privilege(current_user,'public','CREATE')")}
        async with runtime.transaction():
            await runtime.execute("insert into recommendation.processed_events(consumer_name,event_id) values('permission-probe',gen_random_uuid())")
    finally:
        await runtime.close()

    update_env(ROOT / "recommendation-service/.env", {
        "RECOMMENDATION_ENABLED": "false", "RECOMMENDATION_DATABASE_URL": dsn,
        "RECOMMENDATION_INTERNAL_TOKEN": internal_token,
    })
    return {"appliedAt": datetime.now(timezone.utc).isoformat(), "database": "configured-supabase-postgres",
        "schemasCreated": ["recommendation"], "rolesConfigured": ["recommendation_owner", "recommendation_app"],
        "permissions": permissions, "backfillCounts": counts, "recommendationEnabled": False,
        "secretsPrinted": False, "rollback": "Keep schema; disable route and optionally revoke recommendation_app LOGIN."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--evidence", default="evidence/microservice-phase-5-supabase-2026-11-10.json")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    admin_dsn = values.get("DB_WRITE_URL") or values.get("DB_READ_URL") or ""
    if not admin_dsn: raise SystemExit("Admin database DSN is not configured")
    result = asyncio.run(apply(admin_dsn))
    evidence = ROOT / args.evidence
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__": main()
