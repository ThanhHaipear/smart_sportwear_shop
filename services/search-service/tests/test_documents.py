import json
from pathlib import Path
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


def test_catalog_shared_example_validates_against_search_model():
    fixture = Path(__file__).resolve().parents[3] / "contracts" / "examples" / "catalog-index-document-v1.json"
    document = CatalogIndexDocument.model_validate(json.loads(fixture.read_text(encoding="utf-8")))
    assert document.source_version == 2
    assert len(document.variants) == 1
    assert document.variants[0].available_quantity == 7
    assert len(document.images) == 1
    assert document.images[0].url.startswith("https://res.cloudinary.com/")
