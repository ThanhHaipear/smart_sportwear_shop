from __future__ import annotations

from pydantic import BaseModel


class ProductSearchItem(BaseModel):
    productId: str
    name: str
    slug: str
    category: str
    brandName: str
    sportType: str | None
    gender: str | None
    priceMin: float
    priceMax: float
    availableColors: list[str]
    availableSizes: list[str]
    totalAvailable: int
    primaryImage: str | None


class AppliedFilters(BaseModel):
    keyword: str | None = None
    productType: str | None = None
    sportType: str | None = None
    gender: str | None = None
    priceMin: float | None = None
    priceMax: float | None = None


class ProductSearchResult(BaseModel):
    items: list[ProductSearchItem]
    total: int
    appliedFilters: AppliedFilters


class SkuLookupItem(BaseModel):
    productId: str
    variantId: str
    name: str
    slug: str
    sku: str
    category: str
    brandName: str
    color: str | None = None
    size: str | None = None
    price: float
    available: int
    variantStatus: str
    primaryImage: str | None = None


class SkuLookupResult(BaseModel):
    items: list[SkuLookupItem]
    total: int
    querySku: str
    matchType: str  # exact | partial | none


# ── Phase 6: Product detail + variant availability ────────────────────────────

class VariantAvailabilityItem(BaseModel):
    variantId: str
    color: str | None = None
    size: str | None = None
    price: float
    available: int
    sku: str | None = None


class ProductDetailResult(BaseModel):
    found: bool
    productId: str | None = None
    name: str | None = None
    slug: str | None = None
    category: str | None = None
    brandName: str | None = None
    sportType: str | None = None
    gender: str | None = None
    description: str | None = None
    priceMin: float | None = None
    priceMax: float | None = None
    variants: list[VariantAvailabilityItem] = []
    images: list[str] = []
    # Passed-through so response_generator knows what was queried
    sizeHint: str | None = None
    colorHint: str | None = None


# ── Phase 7: Recommendation ──────────────────────────────────────────────────

class RecommendedProductItem(BaseModel):
    productId: str
    name: str
    slug: str
    categoryName: str | None = None
    sportType: str | None = None
    priceMin: float | None = None
    primaryImage: str | None = None
    reason: str = ""


class RecommendationResult(BaseModel):
    items: list[RecommendedProductItem]
    basedOnProductId: str | None = None
    requestId: str | None = None        # recommendation-service request, for tracing
    mode: str                           # "related" | "need_based"
    error: str | None = None


# ── Phase 7: Size Advice ─────────────────────────────────────────────────────

class SizeAdviceResult(BaseModel):
    source: str                         # "product_variants" | "rule_based" | "knowledge" | "unknown"
    productName: str | None = None
    availableSizes: list[str] = []
    suggestedSize: str | None = None
    caveat: str | None = None
    knowledgeTitle: str | None = None
    knowledgeContent: str | None = None
    error: str | None = None
