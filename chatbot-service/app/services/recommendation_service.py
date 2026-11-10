from __future__ import annotations

from app.clients.catalog_api_client import get_public_detail
from app.schemas.product import RecommendedProductItem, RecommendationResult


def _related_item(row: dict) -> RecommendedProductItem:
    return RecommendedProductItem(
        productId=str(row["id"]), name=row.get("name") or "", slug=row.get("slug") or str(row["id"]),
        categoryName=(row.get("category") or {}).get("name"),
        priceMin=float(row["minPrice"]) if row.get("minPrice") is not None else None,
        primaryImage=row.get("thumbnail"), reason="Sản phẩm liên quan",
    )


async def recommend_related(product_id: str, limit: int = 4) -> RecommendationResult:
    if not product_id:
        return RecommendationResult(items=[], basedOnProductId=None, mode="related",
                                    error="Bạn chưa chọn sản phẩm nào.")
    detail = await get_public_detail(product_id)
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
