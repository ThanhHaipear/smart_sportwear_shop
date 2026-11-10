from __future__ import annotations

from app.clients.catalog_api_client import get_index_document
from app.schemas.product import ProductDetailResult, VariantAvailabilityItem


async def get_detail(product_id: str, size_hint: str | None = None,
                     color_hint: str | None = None) -> ProductDetailResult:
    if not product_id:
        return ProductDetailResult(found=False)
    document = await get_index_document(product_id)
    if not document or document.get("status") != "ACTIVE":
        return ProductDetailResult(found=False, productId=product_id)
    variants = [VariantAvailabilityItem(
        variantId=str(row["id"]), color=row.get("color"), size=row.get("size"),
        price=float(row["price"]), available=int(row.get("availableQuantity") or 0),
        sku=row.get("sku"),
    ) for row in document.get("variants", []) if row.get("status") != "INACTIVE"]
    prices = [variant.price for variant in variants]
    images = [row["url"] for row in sorted(
        document.get("images") or [], key=lambda row: row.get("sortOrder", 0)
    ) if row.get("url")]
    return ProductDetailResult(
        found=True, productId=str(document["productId"]), name=document.get("name"),
        slug=document.get("slug") or str(document["productId"]), category=document.get("category"),
        brandName=document.get("brand"), sportType=(document.get("sportCodes") or [None])[0],
        gender=document.get("gender"), description=document.get("description"),
        priceMin=min(prices) if prices else None, priceMax=max(prices) if prices else None,
        variants=variants, images=images, sizeHint=size_hint, colorHint=color_hint,
    )
