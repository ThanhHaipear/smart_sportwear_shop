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
    host = parsed.hostname or ""
    port = f":{parsed.port}" if parsed.port else ""
    admin_username = unquote(parsed.username or "")
    # Supabase pooler identifies the tenant through the username suffix:
    # postgres.<project-ref> -> search_app.<project-ref>.
    suffix = admin_username.split(".", 1)[1] if "." in admin_username else ""
    runtime_username = f"{role}.{suffix}" if suffix else role
    return urlunsplit((
        parsed.scheme,
        f"{quote(runtime_username)}:{quote(password)}@{host}{port}",
        parsed.path, parsed.query, "",
    ))


def update_env(path: Path, updates: dict[str, str]) -> None:
    lines = path.read_text("utf-8").splitlines() if path.exists() else []
    output, seen = [], set()
    for line in lines:
        if "=" in line and not line.lstrip().startswith("#"):
            key = line.split("=", 1)[0]
            if key in updates:
                output.append(f"{key}={updates[key]}")
                seen.add(key)
                continue
        output.append(line)
    if output and output[-1] != "":
        output.append("")
    output.extend(f"{key}={value}" for key, value in updates.items() if key not in seen)
    path.write_text("\n".join(output) + "\n", encoding="utf-8")


async def apply(admin_dsn: str) -> dict:
    search_password = secrets.token_urlsafe(36)
    chatbot_password = secrets.token_urlsafe(36)
    internal_token = secrets.token_urlsafe(36)
    normalized = admin_dsn.replace("postgresql+asyncpg://", "postgresql://")
    connection = await asyncpg.connect(normalized, timeout=15)
    try:
        admin_role = await connection.fetchval("select current_user")
        quoted_admin = await connection.fetchval("select quote_ident($1)", admin_role)
        quoted_search_password = await connection.fetchval("select quote_literal($1)", search_password)
        quoted_chatbot_password = await connection.fetchval("select quote_literal($1)", chatbot_password)
        async with connection.transaction():
            await connection.execute(
                """DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='search_owner') THEN
                        CREATE ROLE search_owner NOLOGIN;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='search_app') THEN
                        CREATE ROLE search_app LOGIN;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='chatbot_owner') THEN
                        CREATE ROLE chatbot_owner NOLOGIN;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='chatbot_app') THEN
                        CREATE ROLE chatbot_app LOGIN;
                    END IF;
                END $$"""
            )
            await connection.execute(f"GRANT search_owner, chatbot_owner TO {quoted_admin}")
            await connection.execute(
                f"ALTER ROLE search_app LOGIN PASSWORD {quoted_search_password}"
            )
            await connection.execute(
                f"ALTER ROLE chatbot_app LOGIN PASSWORD {quoted_chatbot_password}"
            )
            database = await connection.fetchval("select current_database()")
            quoted_database = await connection.fetchval("select quote_ident($1)", database)
            await connection.execute(f"GRANT CONNECT ON DATABASE {quoted_database} TO search_app, chatbot_app")
            await connection.execute((ROOT / "services/search-service/migrations/V1__create_ai_search.sql").read_text("utf-8"))
            await connection.execute((ROOT / "chatbot-service/migrations/V1__create_chatbot_schema.sql").read_text("utf-8"))
            await connection.execute("ALTER ROLE search_app SET search_path=ai_search,extensions,pg_catalog")
            await connection.execute("ALTER ROLE chatbot_app SET search_path=chatbot,pg_catalog")
        search_dsn = runtime_dsn(normalized, "search_app", search_password)
        chatbot_dsn = runtime_dsn(normalized, "chatbot_app", chatbot_password)
    finally:
        await connection.close()

    async def permission_snapshot(dsn: str, owned_schema: str) -> dict:
        conn = None
        last_error = None
        for attempt in range(6):
            try:
                conn = await asyncpg.connect(dsn, timeout=15)
                break
            except asyncpg.InvalidPasswordError as error:
                last_error = error
                if attempt == 5:
                    raise
                await asyncio.sleep(2 ** attempt)
        if conn is None:
            raise last_error or RuntimeError("runtime role connection failed")
        try:
            attributes = await conn.fetchrow(
                "select rolsuper,rolcreatedb,rolcreaterole from pg_roles where rolname=current_user"
            )
            return {
                "role": await conn.fetchval("select current_user"),
                "superuser": attributes["rolsuper"],
                "createDatabase": attributes["rolcreatedb"],
                "createRole": attributes["rolcreaterole"],
                "ownedSchemaUsage": await conn.fetchval(
                    "select has_schema_privilege(current_user,$1,'USAGE')", owned_schema
                ),
                "publicCreate": await conn.fetchval(
                    "select has_schema_privilege(current_user,'public','CREATE')"
                ),
            }
        finally:
            await conn.close()

    search_permissions = await permission_snapshot(search_dsn, "ai_search")
    chatbot_permissions = await permission_snapshot(chatbot_dsn, "chatbot")
    update_env(ROOT / "services/search-service/.env", {
        "SEARCH_ENABLED": "false",
        "DATABASE_URL": search_dsn,
        "CATALOG_API_URL": "http://127.0.0.1:8092",
        "INTERNAL_SERVICE_TOKEN": internal_token,
        "VISUAL_SEARCH_ENABLED": "false",
    })
    update_env(ROOT / "chatbot-service/.env", {
        "CHATBOT_DATABASE_URL": chatbot_dsn,
        "SEARCH_SERVICE_TOKEN": internal_token,
        "CATALOG_SERVICE_TOKEN": internal_token,
    })
    return {
        "appliedAt": datetime.now(timezone.utc).isoformat(),
        "database": "configured-supabase-postgres",
        "schemasCreated": ["ai_search", "chatbot"],
        "rolesConfigured": ["search_owner", "search_app", "chatbot_owner", "chatbot_app"],
        "searchPermissions": search_permissions,
        "chatbotPermissions": chatbot_permissions,
        "visualSearchEnabled": False,
        "secretsPrinted": False,
        "rollback": "Disable services; preserve schemas. Revoke LOGIN from app roles if isolation is required.",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--evidence", default="evidence/microservice-phase-3-4-supabase-2026-11-10.json")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    admin_dsn = values.get("DB_WRITE_URL") or values.get("DB_READ_URL") or ""
    if not admin_dsn:
        raise SystemExit("Admin database DSN is not configured")
    result = asyncio.run(apply(admin_dsn))
    evidence_path = ROOT / args.evidence
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
