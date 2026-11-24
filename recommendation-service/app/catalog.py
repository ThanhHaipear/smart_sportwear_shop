"""Read-only client for Catalog internal APIs.

Recommendation never reads catalog tables. Product state and variant ownership are
obtained from Catalog over HTTP, the same way search-service builds its index.
"""
from __future__ import annotations

from typing import Any, Protocol
from uuid import UUID

import httpx

from app.config import Settings
from app.schemas import ProductSnapshot


class CatalogUnavailable(RuntimeError):
    """Catalog could not answer; the caller should retry the event later."""


class CatalogDocument:
    """Subset of `GET /internal/v1/products/{id}/index-document` used by recommendation."""

    def __init__(self, raw: dict[str, Any]):
        self.raw = raw
        self.product_id = UUID(str(raw["productId"]))
        self.source_version = int(raw.get("sourceVersion") or 0)
        self.status = str(raw.get("status") or "INACTIVE")


class CatalogPort(Protocol):
    def index_document(self, product_id: UUID) -> CatalogDocument | None: ...
    def public_slug(self, product_id: UUID) -> str | None: ...
    def variant_product_id(self, variant_id: UUID) -> UUID | None: ...


class HttpCatalogClient:
    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None):
        if not settings.catalog_service_token.strip():
            raise ValueError("CATALOG_SERVICE_TOKEN is required to read Catalog internal APIs")
        self.base_url = settings.catalog_api_url.rstrip("/")
        self.client = httpx.Client(timeout=settings.catalog_request_timeout_seconds, transport=transport)
        self.internal_headers = {"X-Internal-Service-Token": settings.catalog_service_token}

    def _get(self, path: str, internal: bool) -> dict[str, Any] | None:
        try:
            response = self.client.get(self.base_url + path, headers=self.internal_headers if internal else None)
        except httpx.HTTPError as error:
            raise CatalogUnavailable(f"Catalog request failed: {type(error).__name__}") from error
        if response.status_code == 404:
            return None
        if response.status_code >= 400:
            raise CatalogUnavailable(f"Catalog returned HTTP {response.status_code} for {path}")
        return response.json()

    def index_document(self, product_id: UUID) -> CatalogDocument | None:
        raw = self._get(f"/internal/v1/products/{product_id}/index-document", internal=True)
        return CatalogDocument(raw) if raw else None

    def public_slug(self, product_id: UUID) -> str | None:
        # The public detail endpoint only serves ACTIVE products and wraps data in an envelope.
        raw = self._get(f"/api/v1/products/{product_id}", internal=False)
        data = (raw or {}).get("data", raw) or {}
        slug = data.get("slug")
        return str(slug) if slug else None

    def variant_product_id(self, variant_id: UUID) -> UUID | None:
        raw = self._get(f"/internal/v1/variants/{variant_id}/sale-snapshot", internal=True)
        return UUID(str(raw["productId"])) if raw and raw.get("productId") else None


def build_snapshot(document: CatalogDocument, slug: str | None) -> ProductSnapshot:
    """Turn a Catalog index document into the snapshot stored in `catalog_products`.

    Only ACTIVE variants count toward price and availability, matching the storefront list query.
    """
    raw = document.raw
    variants = [v for v in raw.get("variants") or [] if str(v.get("status")) == "ACTIVE"]
    prices = [float(v["price"]) for v in variants if v.get("price") is not None]
    available = sum(max(0, int(v.get("availableQuantity") or 0)) for v in variants)
    images = sorted(raw.get("images") or [], key=lambda image: int(image.get("sortOrder") or 0))
    thumbnail = images[0].get("url") if images else None
    return ProductSnapshot(
        id=document.product_id,
        name=str(raw.get("name") or ""),
        slug=slug or str(document.product_id),
        thumbnailUrl=thumbnail,
        thumbnail=thumbnail,
        productType=raw.get("productType"),
        minPrice=min(prices) if prices else None,
        maxPrice=max(prices) if prices else None,
        availableQuantity=available,
        status=document.status,
        categoryId=raw.get("categoryId"),
        brandId=raw.get("brandId"),
        gender=raw.get("gender"),
        sportCodes=sorted(set(raw.get("sportCodes") or [])),
        colorFamilies=sorted({str(v["colorFamily"]) for v in variants if v.get("colorFamily")}),
    )
