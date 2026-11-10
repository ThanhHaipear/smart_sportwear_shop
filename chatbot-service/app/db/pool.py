from __future__ import annotations

import asyncpg

_pool: asyncpg.Pool | None = None
_write_pool: asyncpg.Pool | None = None


def _normalize_dsn(dsn: str) -> str:
    return dsn.replace("postgresql+asyncpg://", "postgresql://")


async def init_pool(dsn: str) -> None:
    global _pool
    if not dsn:
        return
    _pool = await asyncpg.create_pool(_normalize_dsn(dsn), min_size=1, max_size=5)


async def init_write_pool(dsn: str) -> None:
    global _write_pool
    if not dsn:
        return
    _write_pool = await asyncpg.create_pool(
        _normalize_dsn(dsn), min_size=1, max_size=3,
        server_settings={"search_path": "chatbot,public"},
    )


async def close_pool() -> None:
    global _pool, _write_pool
    if _pool:
        await _pool.close()
        _pool = None
    if _write_pool:
        await _write_pool.close()
        _write_pool = None


def get_pool() -> asyncpg.Pool | None:
    return _pool


def get_write_pool() -> asyncpg.Pool | None:
    return _write_pool


async def write_ready() -> bool:
    if _write_pool is None:
        return False
    try:
        async with _write_pool.acquire() as conn:
            return bool(await conn.fetchval("SELECT 1"))
    except Exception:
        return False
