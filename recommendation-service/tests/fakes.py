from uuid import UUID

from app.catalog import CatalogDocument


class FakeCatalog:
    """In-memory stand-in for Catalog internal APIs."""

    def __init__(self):
        self.documents: dict[UUID, dict] = {}
        self.slugs: dict[UUID, str] = {}
        self.variant_owner: dict[UUID, UUID] = {}
        self.calls: list[tuple[str, UUID]] = []

    def add_product(self, product_id: UUID, *, version=1, status="ACTIVE", variants=None, images=None, slug=None):
        self.documents[product_id] = {
            "productId": str(product_id), "sourceVersion": version, "status": status,
            "name": f"Product {str(product_id)[-4:]}", "categoryId": None, "brandId": None,
            "productType": "SPORTS_TOP", "variants": variants or [], "images": images or [],
        }
        if slug:
            self.slugs[product_id] = slug
        for variant in variants or []:
            self.variant_owner[UUID(variant["id"])] = product_id

    def index_document(self, product_id):
        self.calls.append(("index_document", product_id))
        raw = self.documents.get(product_id)
        return CatalogDocument(raw) if raw else None

    def public_slug(self, product_id):
        self.calls.append(("public_slug", product_id))
        return self.slugs.get(product_id)

    def variant_product_id(self, variant_id):
        self.calls.append(("variant_product_id", variant_id))
        return self.variant_owner.get(variant_id)


def variant(variant_id: UUID, *, price=100.0, available=5, status="ACTIVE"):
    return {"id": str(variant_id), "sku": f"SKU-{str(variant_id)[-4:]}", "price": price,
            "availableQuantity": available, "status": status}
