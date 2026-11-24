from __future__ import annotations

import logging

from app.clients.catalog_api_client import CatalogApiError, get_public_detail
from app.clients.recommendation_api_client import RecommendationApiError, get_product_recommendations
from app.schemas.product import RecommendedProductItem, RecommendationResult

logger = logging.getLogger(__name__)

# recommendation-service tags every item with the layer that produced it.
REASONS = {
    "ASSOCIATION_RULE": "Thường được mua cùng",
    "SIMILAR_CONTENT": "Hợp với sản phẩm bạn đang xem",
    "POPULARITY": "Đang được nhiều người mua",
}


def _related_item(row: dict) -> RecommendedProductItem:
    return RecommendedProductItem(
        productId=str(row["id"]), name=row.get("name") or "", slug=row.get("slug") or str(row["id"]),
        categoryName=(row.get("category") or {}).get("name"),
        priceMin=float(row["minPrice"]) if row.get("minPrice") is not None else None,
        primaryImage=row.get("thumbnail"), reason="Sản phẩm liên quan",
    )


def _recommended_item(item: dict) -> RecommendedProductItem:
    product = item.get("product") or {}
    return RecommendedProductItem(
        productId=str(product["id"]), name=product.get("name") or "", slug=product.get("slug") or str(product["id"]),
        priceMin=float(product["minPrice"]) if product.get("minPrice") is not None else None,
        primaryImage=product.get("thumbnail") or product.get("thumbnailUrl"),
        reason=REASONS.get(item.get("algorithm") or "", "Sản phẩm liên quan"),
    )


async def recommend_related(product_id: str, limit: int = 4) -> RecommendationResult:
    if not product_id:
        return RecommendationResult(items=[], basedOnProductId=None, mode="related",
                                    error="Bạn chưa chọn sản phẩm nào.")
    try:
        body = await get_product_recommendations(product_id, limit)
        items = [_recommended_item(item) for item in (body or {}).get("items") or [] if item.get("product")]
        if items:
            return RecommendationResult(items=items[:limit], basedOnProductId=product_id, mode="related",
                                        requestId=str(body["requestId"]) if body.get("requestId") else None)
    except (RecommendationApiError, KeyError, TypeError, ValueError) as exc:
        logger.warning("related recommendations fell back to catalog: %s", exc)
    # Fallback: the Catalog detail's relatedProducts (behaviour before recommendation-service was wired in).
    try:
        detail = await get_public_detail(product_id)
    except CatalogApiError:
        return RecommendationResult(items=[], basedOnProductId=product_id, mode="related",
                                    error="Chưa lấy được gợi ý, bạn thử lại sau nhé.")
    rows = (detail or {}).get("relatedProducts") or []
    return RecommendationResult(items=[_related_item(row) for row in rows[:limit]],
                                basedOnProductId=product_id, mode="related")


async def recommend_by_need(query: str, limit: int = 4) -> RecommendationResult:
    from app.services.product_search_service import search
    result = await search(query, limit=limit)
    items = [RecommendedProductItem(
        productId=item.productId, name=item.name, slug=item.slug,
        categoryName=item.category, sportType=item.sportType, priceMin=item.priceMin,
        primaryImage=item.primaryImage, reason="Phù hợp theo nhu cầu",
    ) for item in result.items]
    return RecommendationResult(items=items, basedOnProductId=None, mode="need_based")
