"""
Shared pytest configuration and fixtures for chatbot-service tests.

External dependencies are isolated:
  - DB pool   : get_pool() returns None  → keyword_retrieve raises → caught by service layer
  - Redis     : REDIS_URL="" → falls back to InMemorySessionStore
  - OpenAI    : OPENAI_API_KEY="" → vector branch skipped
  - Backend   : patched per test / module in integration / contract tests
"""
from __future__ import annotations

import os

# ── Set test env vars BEFORE any app module is imported ──────────────────────
os.environ["REDIS_URL"] = ""
os.environ["OPENAI_API_KEY"] = ""
os.environ["ANTHROPIC_API_KEY"] = ""
os.environ["DB_WRITE_URL"] = ""
os.environ["CHATBOT_DATABASE_URL"] = ""
os.environ["SEARCH_API_BASE_URL"] = "http://test-search.local"
os.environ["SEARCH_SERVICE_TOKEN"] = "test-internal-token"
os.environ["CATALOG_API_BASE_URL"] = "http://test-backend.local"
os.environ["CATALOG_SERVICE_TOKEN"] = "test-internal-token"
os.environ["BACKEND_API_BASE_URL"] = "http://test-backend.local"
os.environ["JWT_ACCESS_SECRET"] = "test-access-secret-that-is-at-least-32-bytes"
os.environ["EVALUATION_LOGGING_ENABLED"] = "false"
os.environ["CHATBOT_ENV"] = "test"
# ─────────────────────────────────────────────────────────────────────────────

import pytest
from unittest.mock import patch

from app.observability.trace_logger import setup_logging

setup_logging("WARNING")   # suppress INFO noise during tests


@pytest.fixture(scope="session", autouse=True)
def _init_registry():
    """Register tools once per session."""
    from app.tools.registry import setup_registry
    setup_registry()


@pytest.fixture(scope="session", autouse=True)
def _init_session_store():
    """Use fresh InMemorySessionStore for the whole session."""
    import app.memory.store_factory as sf
    from app.memory.in_memory_store import InMemorySessionStore
    sf._active_store = InMemorySessionStore()


@pytest.fixture
def null_db():
    """Patch DB pool to None so no asyncpg connection is attempted."""
    with patch("app.db.pool.get_pool", return_value=None):
        yield


@pytest.fixture
async def http_client(null_db):
    """
    ASGI test client via httpx. Triggers FastAPI lifespan.
    Each test gets a fresh in-memory session store from lifespan.
    """
    from httpx import AsyncClient, ASGITransport
    from app.main import app

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        yield client
