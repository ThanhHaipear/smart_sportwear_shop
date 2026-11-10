from __future__ import annotations

import httpx

from app.config.settings import settings


class SearchApiError(RuntimeError):
    pass


async def search_products(query: str, *, limit: int = 5, filters: dict | None = None) -> dict:
    """Call the Phase-3 Search API. Chatbot never queries search/catalog tables."""
    headers = {"X-Internal-Service-Token": settings.SEARCH_SERVICE_TOKEN}
    payload = {"query": query, "page": 1, "limit": limit, "filters": filters or {}}
    timeout = httpx.Timeout(settings.SEARCH_API_TIMEOUT_SECONDS)
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                f"{settings.SEARCH_API_BASE_URL.rstrip('/')}/internal/v1/search",
                json=payload,
                headers=headers,
            )
        response.raise_for_status()
        return response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise SearchApiError("search-service is unavailable") from exc


async def ready() -> bool:
    try:
        async with httpx.AsyncClient(timeout=settings.SEARCH_API_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{settings.SEARCH_API_BASE_URL.rstrip('/')}/health/ready"
            )
        return response.status_code == 200 and response.json().get("status") == "ready"
    except (httpx.HTTPError, ValueError):
        return False
