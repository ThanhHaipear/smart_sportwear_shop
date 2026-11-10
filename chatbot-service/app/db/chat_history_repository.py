"""
Persistent conversation history — reads and writes the chat_messages table.

The in-memory session store holds the hot chat_history list.
This module provides the durable backing store: load on cold start, save on every turn.

All functions are fail-safe: they return empty / None and log a warning on any DB error
so that a DB outage never interrupts the chat API response path.
"""
from __future__ import annotations

from app.db.pool import get_pool, get_write_pool
from app.observability.trace_logger import get_logger

logger = get_logger(__name__)

_INSERT_SQL = """
    INSERT INTO chatbot.chat_messages (session_id, user_id, role, content, intent, created_at)
    VALUES ($1, $2, $3, $4, $5, now())
"""

_SELECT_SQL = """
    SELECT role, content
    FROM chatbot.chat_messages
    WHERE session_id = $1
      AND (($2::text IS NULL AND user_id IS NULL) OR user_id = $2)
    ORDER BY created_at DESC
    LIMIT $3
"""


async def load_last_messages(session_id: str, user_id: str | None, limit: int = 10) -> list[dict]:
    """
    Load the last `limit` messages for a session, ordered oldest-first.
    Returns [] when pool is unavailable, table missing, or any error.
    """
    pool = get_write_pool()
    if pool is None:
        return []
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(_SELECT_SQL, session_id, user_id, limit)
        # rows come back newest-first; reverse to oldest-first for LLM context
        return [{"role": row["role"], "content": row["content"]} for row in reversed(rows)]
    except Exception as exc:
        logger.warning(f"chat_history_repository | load_error={exc!r} session={session_id}")
        return []


async def save_turn(
    session_id: str,
    user_id: str | None,
    user_message: str,
    assistant_reply: str,
    intent: str,
) -> None:
    """
    Persist a full turn (user message + assistant reply) to the DB.
    The caller tracks this coroutine so graceful shutdown can drain pending writes.
    """
    pool = get_write_pool()
    if pool is None:
        return
    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """INSERT INTO chatbot.chat_sessions (id, user_id)
                       VALUES ($1, $2)
                       ON CONFLICT (id) DO UPDATE SET
                           user_id = COALESCE(EXCLUDED.user_id, chatbot.chat_sessions.user_id),
                           updated_at = now()""",
                    session_id, user_id,
                )
                await conn.execute(_INSERT_SQL, session_id, user_id, "user",      user_message,    None)
                await conn.execute(_INSERT_SQL, session_id, user_id, "assistant", assistant_reply, intent)
    except Exception as exc:
        logger.warning(f"chat_history_repository | save_error={exc!r} session={session_id}")


async def save_retrieval_trace(session_id: str | None, query: str,
                               candidate_ids: list[str], search_mode: str | None,
                               latency_ms: int) -> None:
    pool = get_write_pool()
    if pool is None:
        return
    try:
        import json
        async with pool.acquire() as conn:
            await conn.execute(
                """INSERT INTO chatbot.retrieval_logs
                   (session_id, query, candidate_product_ids, search_mode, latency_ms)
                   VALUES ($1, $2, $3::jsonb, $4, $5)""",
                session_id, query, json.dumps(candidate_ids), search_mode, latency_ms,
            )
    except Exception as exc:
        logger.warning(f"chat_history_repository | trace_error={exc!r} session={session_id}")
