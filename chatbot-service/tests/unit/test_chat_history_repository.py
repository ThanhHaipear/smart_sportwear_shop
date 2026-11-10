from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

from app.db.chat_history_repository import load_last_messages


async def test_load_history_binds_user_and_limit_to_distinct_parameters():
    connection = MagicMock()
    connection.fetch = AsyncMock(
        return_value=[
            {"role": "assistant", "content": "second"},
            {"role": "user", "content": "first"},
        ]
    )
    acquire = AsyncMock()
    acquire.__aenter__.return_value = connection
    pool = MagicMock()
    pool.acquire.return_value = acquire

    with patch("app.db.chat_history_repository.get_write_pool", return_value=pool):
        messages = await load_last_messages("user:u1:s1", "u1", limit=7)

    sql, session_id, user_id, limit = connection.fetch.await_args.args
    assert "LIMIT $3" in sql
    assert (session_id, user_id, limit) == ("user:u1:s1", "u1", 7)
    assert messages == [
        {"role": "user", "content": "first"},
        {"role": "assistant", "content": "second"},
    ]
