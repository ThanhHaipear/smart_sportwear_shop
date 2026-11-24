"""recommendation_api_client turns every way the service can be unreachable into RecommendationApiError.

These are the cases behind the Compose wiring (RECOMMENDATION_API_BASE_URL=http://recommendation-service:8091):
the profile may be off (host does not resolve) or the service may be disabled (503). Either way the caller
must get RecommendationApiError so "related" mode falls back to Catalog.
"""
from __future__ import annotations

from functools import partial

import httpx
import pytest

from app.clients import recommendation_api_client as client
from app.clients.recommendation_api_client import RecommendationApiError, get_product_recommendations
from app.config.settings import settings

REAL_ASYNC_CLIENT = httpx.AsyncClient


def _use_transport(monkeypatch, handler) -> list[httpx.Request]:
    seen: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    monkeypatch.setattr(client.httpx, "AsyncClient",
                        partial(REAL_ASYNC_CLIENT, transport=httpx.MockTransport(record)))
    return seen


@pytest.fixture
def compose_url(monkeypatch):
    monkeypatch.setattr(settings, "RECOMMENDATION_API_BASE_URL", "http://recommendation-service:8091")


async def test_calls_configured_base_url(monkeypatch, compose_url):
    seen = _use_transport(monkeypatch, lambda request: httpx.Response(200, json={"items": []}))
    assert await get_product_recommendations("p-1", 4) == {"items": []}
    assert str(seen[0].url) == "http://recommendation-service:8091/api/v1/recommendations/products/p-1?limit=4"


async def test_disabled_service_raises(monkeypatch, compose_url):
    _use_transport(monkeypatch, lambda request: httpx.Response(503, json={"detail": "disabled"}))
    with pytest.raises(RecommendationApiError):
        await get_product_recommendations("p-1", 4)


async def test_unresolvable_host_raises(monkeypatch, compose_url):
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("Name or service not known", request=request)

    _use_transport(monkeypatch, refuse)
    with pytest.raises(RecommendationApiError):
        await get_product_recommendations("p-1", 4)


async def test_unknown_product_returns_none(monkeypatch, compose_url):
    _use_transport(monkeypatch, lambda request: httpx.Response(404))
    assert await get_product_recommendations("p-1", 4) is None
