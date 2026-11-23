from __future__ import annotations

from uuid import UUID

import httpx
from pydantic import BaseModel, Field

from app.config import Settings


class CatalogImage(BaseModel):
    id: UUID
    url: str
    public_id: str | None = Field(default=None, alias="publicId")
    asset_version: str | None = Field(default=None, alias="assetVersion")
    width: int | None = Field(default=None, gt=0)
    height: int | None = Field(default=None, gt=0)
    format: str | None = None
    bytes: int | None = Field(default=None, gt=0)
    checksum: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    source_kind: str | None = Field(default=None, alias="sourceKind")
    license_code: str | None = Field(default=None, alias="licenseCode")
    source_uri: str | None = Field(default=None, alias="sourceUri")
    source_group_code: str | None = Field(default=None, alias="sourceGroupCode")
    sort_order: int = Field(default=0, alias="sortOrder")


class CatalogVariant(BaseModel):
    id: UUID
    sku: str
    size: str | None = None
    color: str | None = None
    color_family: str | None = Field(default=None, alias="colorFamily")
    price: float
    available_quantity: int = Field(alias="availableQuantity")
    status: str


class CatalogIndexDocument(BaseModel):
    product_id: UUID = Field(alias="productId")
    source_version: int = Field(alias="sourceVersion", ge=0)
    status: str
    name: str
    description: str = ""
    category_id: UUID | None = Field(default=None, alias="categoryId")
    category: str | None = None
    category_slug: str | None = Field(default=None, alias="categorySlug")
    brand_id: UUID | None = Field(default=None, alias="brandId")
    brand: str | None = None
    brand_slug: str | None = Field(default=None, alias="brandSlug")
    product_type: str | None = Field(default=None, alias="productType")
    gender: str | None = None
    sport_codes: list[str] = Field(default_factory=list, alias="sportCodes")
    attributes: dict[str, object] = Field(default_factory=dict)
    variants: list[CatalogVariant] = Field(default_factory=list)
    images: list[CatalogImage] = Field(default_factory=list)


class CatalogClient:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def index_document(self, product_id: UUID) -> CatalogIndexDocument | None:
        url = f"{self.settings.catalog_api_url.rstrip('/')}/internal/v1/products/{product_id}/index-document"
        async with httpx.AsyncClient(timeout=self.settings.catalog_request_timeout_seconds) as client:
            response = await client.get(url, headers={
                "X-Internal-Service-Token": self.settings.catalog_service_token,
            })
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return CatalogIndexDocument.model_validate(response.json())
