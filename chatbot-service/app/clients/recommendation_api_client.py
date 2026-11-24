from __future__ import annotations

import httpx

from app.config.settings import settings


class RecommendationApiError(RuntimeError):
    pass


async def get_product_recommendations(product_id: str, limit: int) -> dict | None:
    """`GET /api/v1/recommendations/products/{id}`: association rules -> content -> popularity.

    Returns None when the product is unknown; raises RecommendationApiError when the service is down or
    disabled (503), so the caller can fall back.
    """
    try:
        async with httpx.AsyncClient(timeout=settings.RECOMMENDATION_API_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{settings.RECOMMENDATION_API_BASE_URL.rstrip('/')}/api/v1/recommendations/products/{product_id}",
                params={"limit": limit},
            )
        if response.status_code == 404:
            return None
        response.raise_for_status()
        body = response.json()
        return body if isinstance(body, dict) else None
    except (httpx.HTTPError, ValueError) as exc:
        raise RecommendationApiError("recommendation-service is unavailable") from exc
