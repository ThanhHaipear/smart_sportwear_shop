import asyncio
from unittest.mock import AsyncMock, Mock
from uuid import UUID

from app.catalog.client import CatalogIndexDocument
from app.config import Settings
from scripts.backfill import active_product_ids, inspect


def test_dry_run_validates_catalog_documents_without_writes():
    product_id = UUID("10000000-0000-0000-0000-000000000001")
    catalog = Mock()
    catalog.index_document = AsyncMock()
    catalog.index_document.return_value = CatalogIndexDocument.model_validate({
        "productId": str(product_id),
        "sourceVersion": 2,
        "status": "ACTIVE",
        "name": "Giày Demo",
        "variants": [{
            "id": "20000000-0000-0000-0000-000000000001",
            "sku": "DEMO-42",
            "price": 1000000,
            "availableQuantity": 3,
            "status": "ACTIVE",
        }],
        "images": [{
            "id": "50000000-0000-0000-0000-000000000001",
            "url": "https://res.cloudinary.com/demo/image/upload/v1/shoe.jpg",
        }],
    })

    report = asyncio.run(inspect([product_id], catalog))

    assert report == {
        "mode": "dry-run",
        "requestedProducts": 1,
        "foundProducts": 1,
        "missingProducts": 0,
        "variants": 1,
        "images": 1,
        "statuses": {"ACTIVE": 1},
        "errors": [],
        "passed": True,
    }
    catalog.index_document.assert_awaited_once_with(product_id)


def test_active_product_ids_are_discovered_through_catalog_api():
    response = Mock()
    response.raise_for_status = Mock()
    response.json.return_value = {
        "data": [
            {"id": "10000000-0000-0000-0000-000000000001"},
            {"id": "10000000-0000-0000-0000-000000000002"},
        ],
        "meta": {"page": 1, "totalPages": 1},
    }
    client = Mock()
    client.get = AsyncMock(return_value=response)

    product_ids = asyncio.run(active_product_ids(
        Settings(catalog_api_url="http://catalog:8092"),
        client,
    ))

    assert product_ids == [
        UUID("10000000-0000-0000-0000-000000000001"),
        UUID("10000000-0000-0000-0000-000000000002"),
    ]
    client.get.assert_awaited_once_with(
        "http://catalog:8092/api/v1/products",
        params={"page": 1, "limit": 100},
    )
