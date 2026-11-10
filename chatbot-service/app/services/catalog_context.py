"""Read the last event/API-generated catalog context; never query Catalog DB."""
from __future__ import annotations

import logging
from pathlib import Path

logger = logging.getLogger(__name__)

_CACHE_FILE = (
    Path(__file__).parent.parent
    / "retrieval" / "product" / "query_rewrite" / "catalog_context.txt"
)

async def refresh_catalog_context() -> str:
    """Compatibility no-op. Context is updated by the Catalog event/API pipeline."""
    return get_catalog_context()


def get_catalog_context() -> str:
    """Sync lazy-load từ cache file. Trả về empty string nếu chưa có."""
    if _CACHE_FILE.exists():
        try:
            return _CACHE_FILE.read_text(encoding="utf-8")
        except Exception:
            pass
    return ""
