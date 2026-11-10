from __future__ import annotations

from pathlib import Path

import pytest

from app.services import product_search_service


def test_runtime_services_do_not_import_commercial_repositories():
    service_dir = Path(__file__).parents[2] / "app" / "services"
    violations = []
    for source in service_dir.glob("*.py"):
        text = source.read_text(encoding="utf-8")
        if "app.repositories" in text or "app.db.pool import get_pool" in text:
            violations.append(source.name)
    assert violations == []


@pytest.mark.asyncio
async def test_rag_uses_search_then_catalog_and_preserves_candidate_order(monkeypatch):
    calls = []

    async def fake_search(query, *, limit, filters):
        calls.append(("search", query))
        return {"items": [{"productId": "p2"}, {"productId": "p1"}],
                "total": 2, "searchMode": "HYBRID"}

    async def fake_documents(ids):
        calls.append(("catalog", ids))
        def doc(product_id):
            return {
                "productId": product_id, "status": "ACTIVE", "name": product_id,
                "slug": product_id, "category": "Giày", "brand": "Test",
                "sportCodes": ["RUNNING"], "gender": "UNISEX", "images": [],
                "variants": [{"id": f"v-{product_id}", "price": 100,
                              "availableQuantity": 1, "status": "ACTIVE"}],
            }
        return [doc("p1"), doc("p2")]

    async def ignore_trace(*_args):
        return None

    monkeypatch.setattr(product_search_service.search_api_client, "search_products", fake_search)
    monkeypatch.setattr(product_search_service.catalog_api_client, "get_index_documents", fake_documents)
    monkeypatch.setattr(product_search_service.chat_history_repository, "save_retrieval_trace", ignore_trace)

    result = await product_search_service.search("giày chạy bộ")

    assert [item.productId for item in result.items] == ["p2", "p1"]
    assert calls == [("search", "giày chạy bộ"), ("catalog", ["p2", "p1"])]


def test_history_sql_is_schema_qualified():
    source = (Path(__file__).parents[2] / "app" / "db" / "chat_history_repository.py").read_text("utf-8")
    assert "chatbot.chat_sessions" in source
    assert "chatbot.chat_messages" in source
    assert "chatbot.retrieval_logs" in source
