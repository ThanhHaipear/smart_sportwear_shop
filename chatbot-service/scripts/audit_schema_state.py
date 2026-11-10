from __future__ import annotations

import argparse
import asyncio
from urllib.parse import urlsplit

import asyncpg
from dotenv import dotenv_values


async def audit(dsn: str) -> None:
    normalized_dsn = dsn.replace("postgresql+asyncpg://", "postgresql://")
    parsed = urlsplit(normalized_dsn)
    connection = await asyncpg.connect(normalized_dsn, timeout=10)
    try:
        role = await connection.fetchval("select current_user")
        privileges = await connection.fetchrow(
            "select rolsuper, rolcreaterole, rolcreatedb from pg_roles where rolname=current_user"
        )
        database_name = await connection.fetchval("select current_database()")
        schemas = await connection.fetch(
            "select schema_name from information_schema.schemata "
            "where schema_name in ('chatbot','ai_search') order by schema_name"
        )
        service_roles = await connection.fetch(
            "select rolname, rolsuper, rolcanlogin from pg_roles "
            "where rolname in ('search_owner','search_app','chatbot_owner','chatbot_app') order by rolname"
        )
        print({
            "connected": True,
            "is_chatbot_app": role == "chatbot_app",
            "database": database_name,
            "endpoint_kind": "supabase" if "supabase" in (parsed.hostname or "") else "other",
            "port": parsed.port or 5432,
            "can_create_schema": bool(await connection.fetchval(
                "select has_database_privilege(current_user, current_database(), 'CREATE')"
            )),
            "can_create_role": bool(privileges["rolcreaterole"] or privileges["rolsuper"]),
            "schemas": [row["schema_name"] for row in schemas],
            "service_roles": [dict(row) for row in service_roles],
        })
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", default=".env")
    args = parser.parse_args()
    values = dotenv_values(args.env_file)
    dsn = values.get("CHATBOT_DATABASE_URL") or values.get("DB_WRITE_URL") or ""
    if not dsn:
        raise SystemExit("No chatbot database URL is configured")
    asyncio.run(audit(dsn))


if __name__ == "__main__":
    main()
