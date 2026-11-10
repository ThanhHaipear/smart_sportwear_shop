from uuid import uuid4

from app.catalog.client import CatalogIndexDocument
from app.services.documents import canonical_text, content_hash


def test_canonical_document_is_deterministic():
    document = CatalogIndexDocument.model_validate({
        "productId": str(uuid4()), "sourceVersion": 2, "status": "ACTIVE",
        "name": "Áo chạy bộ", "description": "Nhẹ", "category": "Áo",
        "categorySlug": "ao", "brand": "Demo", "brandSlug": "demo", "sportCodes": ["RUNNING"],
        "attributes": {"z": "sau", "a": "trước"}, "variants": [], "images": [],
    })
    first = canonical_text(document)
    second = canonical_text(document)
    assert first == second
    assert "a: trước z: sau" in first
    assert content_hash(first) == content_hash(second)
