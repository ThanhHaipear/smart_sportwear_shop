from __future__ import annotations

from app.clients import catalog_api_client, search_api_client
from app.schemas.product import SkuLookupItem, SkuLookupResult


async def lookup(sku: str, limit: int = 10) -> SkuLookupResult:
    normalized = sku.strip().upper()
    if not normalized:
        return SkuLookupResult(items=[], total=0, querySku="", matchType="none")
    response = await search_api_client.search_products(normalized, limit=limit)
    ids = [str(row["productId"]) for row in response.get("items", [])]
    documents = await catalog_api_client.get_index_documents(ids)
    exact, partial = [], []
    for document in documents:
        images = sorted(document.get("images") or [], key=lambda row: row.get("sortOrder", 0))
        for variant in document.get("variants", []):
            candidate = (variant.get("sku") or "").upper()
            if normalized not in candidate:
                continue
            item = SkuLookupItem(
                productId=str(document["productId"]), variantId=str(variant["id"]),
                name=document.get("name") or "", slug=document.get("slug") or str(document["productId"]),
                sku=variant.get("sku") or "", category=document.get("category") or "",
                brandName=document.get("brand") or "", color=variant.get("color"), size=variant.get("size"),
                price=float(variant["price"]), available=int(variant.get("availableQuantity") or 0),
                variantStatus=variant.get("status") or "", primaryImage=images[0].get("url") if images else None,
            )
            (exact if candidate == normalized else partial).append(item)
    items = exact[:1] or partial[:limit]
    return SkuLookupResult(items=items, total=len(items), querySku=normalized,
                           matchType="exact" if exact else "partial" if partial else "none")
