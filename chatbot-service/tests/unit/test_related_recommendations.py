"""Chatbot "related" mode reads recommendation-service and falls back to Catalog relatedProducts."""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

from app.clients.catalog_api_client import CatalogApiError
from app.clients.recommendation_api_client import RecommendationApiError
from app.services.recommendation_service import recommend_related

MODULE = "app.services.recommendation_service"


def _item(pid: str, algorithm: str) -> dict:
    return {"product": {"id": pid, "name": f"P {pid}", "slug": f"p-{pid}", "minPrice": 100.0,
                        "thumbnail": f"https://img/{pid}.png"}, "score": 1.0, "algorithm": algorithm}


async def test_uses_recommendation_service_and_explains_each_layer():
    body = {"requestId": "r-1", "items": [_item("a", "ASSOCIATION_RULE"), _item("b", "SIMILAR_CONTENT"),
                                          _item("c", "POPULARITY")]}
    with patch(f"{MODULE}.get_product_recommendations", AsyncMock(return_value=body)) as call, \
            patch(f"{MODULE}.get_public_detail", AsyncMock()) as catalog:
        result = await recommend_related("src", limit=3)
    call.assert_awaited_once_with("src", 3)
    catalog.assert_not_awaited()
    assert [i.productId for i in result.items] == ["a", "b", "c"]
    assert [i.reason for i in result.items] == ["Thường được mua cùng", "Hợp với sản phẩm bạn đang xem",
                                                "Đang được nhiều người mua"]
    assert result.requestId == "r-1" and result.items[0].primaryImage == "https://img/a.png"


async def test_falls_back_to_catalog_when_service_is_down():
    detail = {"relatedProducts": [{"id": "x", "name": "X", "slug": "x", "minPrice": 5}]}
    with patch(f"{MODULE}.get_product_recommendations", AsyncMock(side_effect=RecommendationApiError("down"))), \
            patch(f"{MODULE}.get_public_detail", AsyncMock(return_value=detail)):
        result = await recommend_related("src")
    assert [i.productId for i in result.items] == ["x"] and result.requestId is None


async def test_falls_back_to_catalog_when_service_returns_nothing():
    with patch(f"{MODULE}.get_product_recommendations", AsyncMock(return_value={"items": []})), \
            patch(f"{MODULE}.get_public_detail", AsyncMock(return_value={"relatedProducts": []})) as catalog:
        result = await recommend_related("src")
    catalog.assert_awaited_once()
    assert result.items == [] and result.error is None


async def test_both_sources_down_returns_friendly_error():
    with patch(f"{MODULE}.get_product_recommendations", AsyncMock(side_effect=RecommendationApiError("down"))), \
            patch(f"{MODULE}.get_public_detail", AsyncMock(side_effect=CatalogApiError("down"))):
        result = await recommend_related("src")
    assert result.items == [] and result.error


async def test_missing_product_id_is_rejected_without_calls():
    with patch(f"{MODULE}.get_product_recommendations", AsyncMock()) as call:
        result = await recommend_related("")
    call.assert_not_awaited()
    assert result.error
