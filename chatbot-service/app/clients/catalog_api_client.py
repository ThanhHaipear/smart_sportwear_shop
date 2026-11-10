from __future__ import annotations

import asyncio
import httpx

from app.config.settings import settings


class CatalogApiError(RuntimeError):
    pass


def _headers() -> dict[str, str]:
    return {"X-Internal-Service-Token": settings.CATALOG_SERVICE_TOKEN}


async def get_index_document(product_id: str) -> dict | None:
    try:
        async with httpx.AsyncClient(timeout=settings.CATALOG_API_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{settings.CATALOG_API_BASE_URL.rstrip('/')}/internal/v1/products/{product_id}/index-document",
                headers=_headers(),
            )
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise CatalogApiError("catalog-service is unavailable") from exc


async def get_index_documents(product_ids: list[str]) -> list[dict]:
    results = await asyncio.gather(
        *(get_index_document(product_id) for product_id in product_ids),
        return_exceptions=True,
    )
    return [result for result in results if isinstance(result, dict)]


async def get_public_detail(product_id: str) -> dict | None:
    try:
        async with httpx.AsyncClient(timeout=settings.CATALOG_API_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{settings.CATALOG_API_BASE_URL.rstrip('/')}/api/v1/products/{product_id}"
            )
        if response.status_code == 404:
            return None
        response.raise_for_status()
        body = response.json()
        return body.get("data") if isinstance(body, dict) else None
    except (httpx.HTTPError, ValueError) as exc:
        raise CatalogApiError("catalog-service is unavailable") from exc


async def ready() -> bool:
    try:
        async with httpx.AsyncClient(timeout=settings.CATALOG_API_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{settings.CATALOG_API_BASE_URL.rstrip('/')}/actuator/health"
            )
        return response.status_code == 200
    except httpx.HTTPError:
        return False
