from __future__ import annotations

import argparse
import asyncio

import asyncpg
from dotenv import dotenv_values


async def audit(dsn: str) -> None:
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"), timeout=10)
    try:
        role = await connection.fetchval("select current_user")
        schemas = await connection.fetch(
            "select schema_name from information_schema.schemata "
            "where schema_name in ('chatbot','ai_search') order by schema_name"
        )
        print({
            "connected": True,
            "is_chatbot_app": role == "chatbot_app",
            "schemas": [row["schema_name"] for row in schemas],
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
