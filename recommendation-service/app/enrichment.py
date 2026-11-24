"""Complete thin integration events before they are applied to the read models.

Catalog publishes `PRODUCT_REINDEX_REQUESTED` with only `productId` + `sourceVersion`, and Sales
publishes `ORDER_CREATED` whose `items` are variant IDs. Recommendation stores product snapshots
and product-level baskets, so both events must be resolved against Catalog first.
"""
from __future__ import annotations

import logging
from uuid import UUID

from app.catalog import CatalogPort, build_snapshot
from app.repository import Repository
from app.schemas import IntegrationEvent, ProductSnapshot

logger = logging.getLogger(__name__)

CATALOG_EVENT_TYPES = frozenset({"PRODUCT_REINDEX_REQUESTED", "PRODUCT_CHANGED", "INVENTORY_CHANGED"})


class EventEnricher:
    def __init__(self, catalog: CatalogPort, repository: Repository):
        self.catalog = catalog
        self.repository = repository

    def fetch_snapshot(self, product_id: UUID) -> tuple[ProductSnapshot, int] | None:
        document = self.catalog.index_document(product_id)
        if document is None:
            return None
        slug = self.catalog.public_slug(product_id) if document.status == "ACTIVE" else None
        return build_snapshot(document, slug), document.source_version

    def enrich(self, event: IntegrationEvent) -> IntegrationEvent:
        if event.product is None and event.product_id and event.event_type in CATALOG_EVENT_TYPES:
            fetched = self.fetch_snapshot(event.product_id)
            if fetched is None:
                # Catalog no longer has the product: keep a non-recommendable tombstone.
                event.product = ProductSnapshot(id=event.product_id, name="", slug=str(event.product_id),
                                                availableQuantity=0, status="DELETED")
            else:
                event.product, version = fetched
                event.source_version = max(event.source_version, version)
        if event.order_id and event.items:
            event.items = self.resolve_products(event.items)
        return event

    def resolve_products(self, ids: list[UUID]) -> list[UUID]:
        """Map order item IDs (variant or product) to distinct product IDs present in `catalog_products`."""
        products: list[UUID] = []
        for item_id in dict.fromkeys(ids):
            product_id = self._resolve_one(item_id)
            if product_id is not None and product_id not in products:
                products.append(product_id)
        return products

    def _resolve_one(self, item_id: UUID) -> UUID | None:
        if self.repository.has_product(item_id):
            return item_id
        product_id = self.catalog.variant_product_id(item_id)
        if product_id is None:
            # Not a variant: accept it only if Catalog knows it as a product.
            product_id = item_id
        if not self.repository.has_product(product_id):
            fetched = self.fetch_snapshot(product_id)
            if fetched is None:
                logger.warning("Order item %s does not match any catalog product or variant", item_id)
                return None
            self.repository.upsert_product(*fetched)
        return product_id
