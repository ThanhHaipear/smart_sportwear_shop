from __future__ import annotations

import pytest

from app.retrieval.product.parser.sku_parser import extract_sku
from app.services import sku_lookup_service


@pytest.mark.parametrize(
    "message,expected",
    [
        ("SKU SEED-BOOT-RED-43 còn hàng không?", "SEED-BOOT-RED-43"),
        ("mã sản phẩm abc-123-xl", "ABC-123-XL"),
        ("kiểm tra RUN-42-BLK", "RUN-42-BLK"),
        ("đơn hàng DH-123 đang ở đâu", None),
        ("tôi muốn mua giày chạy bộ", None),
    ],
)
def test_extract_sku(message, expected):
    assert extract_sku(message) == expected


def _document(*skus: str) -> dict:
    return {
        "productId": "product-1",
        "name": "Giày chạy bộ",
        "slug": "giay-chay-bo",
        "category": "Giày chạy bộ",
        "brand": "Nike",
        "images": [],
        "variants": [{
            "id": f"variant-{i}", "sku": sku, "color": "Đen", "size": str(42 + i),
            "price": 900000, "availableQuantity": 4, "status": "ACTIVE",
        } for i, sku in enumerate(skus)],
    }


@pytest.mark.asyncio
async def test_exact_lookup_does_not_run_partial(monkeypatch):
    async def fake_search(*_args, **_kwargs):
        return {"items": [{"productId": "product-1"}]}
    async def fake_documents(_ids):
        return [_document("SEED-BOOT-RED-43", "SEED-BOOT-RED-42")]
    monkeypatch.setattr(sku_lookup_service.search_api_client, "search_products", fake_search)
    monkeypatch.setattr(sku_lookup_service.catalog_api_client, "get_index_documents", fake_documents)
    result = await sku_lookup_service.lookup("seed-boot-red-43")

    assert result.matchType == "exact"
    assert result.items[0].sku == "SEED-BOOT-RED-43"


@pytest.mark.asyncio
async def test_partial_lookup_runs_only_after_exact_miss(monkeypatch):
    async def fake_search(*_args, **_kwargs):
        return {"items": [{"productId": "product-1"}]}
    async def fake_documents(_ids):
        return [_document("SEED-BOOT-RED-42", "SEED-BOOT-RED-43")]
    monkeypatch.setattr(sku_lookup_service.search_api_client, "search_products", fake_search)
    monkeypatch.setattr(sku_lookup_service.catalog_api_client, "get_index_documents", fake_documents)
    result = await sku_lookup_service.lookup("boot-red")

    assert result.matchType == "partial"
    assert result.total == 2
