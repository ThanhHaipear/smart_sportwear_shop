from __future__ import annotations

import argparse
import asyncio
from uuid import uuid4

import asyncpg
from dotenv import dotenv_values


class RollbackProbe(Exception):
    pass


async def verify(dsn: str) -> None:
    connection = await asyncpg.connect(dsn, timeout=15)
    try:
        assert await connection.fetchval("select current_user") == "search_app"
        assert await connection.fetchval("select has_schema_privilege(current_user,'ai_search','USAGE')")
        assert not await connection.fetchval("select has_schema_privilege(current_user,'public','CREATE')")
        async with connection.transaction():
            await connection.execute(
                """insert into ai_search.indexing_jobs(id,job_type,status,total_count)
                   values($1,'PERMISSION_TEST','PENDING',0)""",
                uuid4(),
            )
            try:
                async with connection.transaction():
                    await connection.execute("create table public.__search_permission_probe(id integer)")
            except asyncpg.InsufficientPrivilegeError:
                pass
            else:
                raise AssertionError("search_app unexpectedly created a table in public")
            raise RollbackProbe
    except RollbackProbe:
        pass
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", default=".env")
    args = parser.parse_args()
    dsn = dotenv_values(args.env_file).get("DATABASE_URL") or ""
    if not dsn:
        raise SystemExit("search_app DATABASE_URL is required")
    asyncio.run(verify(dsn))
    print("search_app schema permission test passed")


if __name__ == "__main__":
    main()
