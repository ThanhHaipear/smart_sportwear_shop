from __future__ import annotations

import argparse
import asyncio

import asyncpg
from dotenv import dotenv_values


class RollbackProbe(Exception):
    pass


async def verify(dsn: str) -> None:
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"))
    try:
        assert await connection.fetchval("select current_user") == "chatbot_app"
        assert await connection.fetchval("select has_schema_privilege(current_user, 'chatbot', 'USAGE')")
        assert not await connection.fetchval("select has_schema_privilege(current_user, 'public', 'CREATE')")
        async with connection.transaction():
            await connection.execute(
                "insert into chatbot.chat_sessions(id, channel) values($1, 'permission-test')",
                "__phase4_permission_probe__",
            )
            try:
                async with connection.transaction():
                    await connection.execute("create table public.__chatbot_permission_probe(id integer)")
            except asyncpg.InsufficientPrivilegeError:
                pass
            else:
                raise AssertionError("chatbot_app unexpectedly created a table in public")
            raise RollbackProbe
    except RollbackProbe:
        pass
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dsn", nargs="?", help="chatbot_app DSN; never use the migration-owner DSN")
    parser.add_argument("--env-file")
    args = parser.parse_args()
    dsn = args.dsn
    if args.env_file:
        dsn = dotenv_values(args.env_file).get("CHATBOT_DATABASE_URL") or ""
    if not dsn:
        raise SystemExit("chatbot_app DSN is required")
    asyncio.run(verify(dsn))
    print("chatbot_app schema permission test passed")


if __name__ == "__main__":
    main()
