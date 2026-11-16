from datetime import datetime, timezone
from uuid import uuid4

from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.dependencies import catalog_client, repository
from tests.fakes import FakeCatalog
from app.main import create_app
from app.repository import MemoryRepository
from app.schemas import ProductSnapshot


def settings():
    return Settings(recommendation_enabled=True, recommendation_database_url="postgresql://unused",
                    recommendation_internal_token="test-token")


def client(repo=None):
    app = create_app()
    app.dependency_overrides[get_settings] = settings
    app.dependency_overrides[repository] = lambda: repo or MemoryRepository()
    app.dependency_overrides[catalog_client] = FakeCatalog
    return TestClient(app)


def envelope(event_type):
    return {"eventId": str(uuid4()), "eventType": event_type, "eventVersion": 1,
            "occurredAt": datetime.now(timezone.utc).isoformat(), "traceId": str(uuid4())}


def test_health_and_correlation_contract():
    response = client().get("/health/live", headers={"X-Correlation-ID": "invalid"})
    assert response.status_code == 200
    assert response.json()["service"] == "recommendation-service"
    assert response.headers["X-Correlation-ID"] != "invalid"


def test_internal_event_requires_token_and_is_idempotent():
    repo = MemoryRepository(); http = client(repo); product_id = uuid4()
    event = envelope("PRODUCT_CHANGED") | {"productId": str(product_id), "sourceVersion": 1,
        "product": ProductSnapshot(id=product_id, name="Running shirt", slug="running-shirt",
                                   availableQuantity=5).model_dump(mode="json")}
    assert http.post("/internal/v1/events", json=event).status_code == 401
    first = http.post("/internal/v1/events", json=event, headers={"X-Internal-Service-Token": "test-token"})
    second = http.post("/internal/v1/events", json=event, headers={"X-Internal-Service-Token": "test-token"})
    assert first.json()["processed"] is True
    assert second.json()["processed"] is False


def test_legacy_product_route_keeps_storefront_shape():
    repo = MemoryRepository(); source, fallback = uuid4(), uuid4()
    repo.product_by_id[fallback] = ProductSnapshot(id=fallback, name="Popular", slug="popular", availableQuantity=2)
    repo.popularity[fallback] = 4
    response = client(repo).get(f"/api/v1/products/{source}/frequently-bought-together")
    assert response.status_code == 200
    assert response.json()["items"][0]["product"]["id"] == str(fallback)


def test_interaction_validation_and_duplicate_safety():
    repo = MemoryRepository(); http = client(repo)
    body = envelope("VIEW") | {"anonymousId": "session-1", "productId": str(uuid4()),
        "source": "STOREFRONT", "dataSource": "REAL", "metadata": {}}
    assert http.post("/api/v1/recommendations/interactions", json=body).json()["accepted"] is True
    assert http.post("/api/v1/recommendations/interactions", json=body).json()["accepted"] is False


def browser_event(event_type="VIEW", **fields):
    return envelope(event_type) | {"anonymousId": "anon-123456", "productId": str(uuid4()),
                                   "source": "PRODUCT_DETAIL", "dataSource": "REAL", "metadata": {}} | fields


def test_interaction_user_comes_from_gateway_header_not_body():
    repo = MemoryRepository(); http = client(repo); user = str(uuid4())
    body = browser_event(userRef=str(uuid4()))
    assert http.post("/api/v1/recommendations/interactions", json=body, headers={"X-User-Id": user}).status_code == 202
    assert repo.interactions[0].user_ref == user


def test_anonymous_interaction_ignores_spoofed_user_ref_and_uses_server_time():
    repo = MemoryRepository(); http = client(repo)
    body = browser_event(userRef=str(uuid4()), occurredAt="2020-01-01T00:00:00+00:00")
    assert http.post("/api/v1/recommendations/interactions", json=body).status_code == 202
    stored = repo.interactions[0]
    assert stored.user_ref is None
    assert stored.occurred_at.year >= 2026


def test_browser_cannot_send_purchase_or_synthetic_or_oversized_metadata():
    http = client()
    url = "/api/v1/recommendations/interactions"
    assert http.post(url, json=browser_event("PURCHASE")).status_code == 422
    assert http.post(url, json=browser_event(dataSource="SYNTHETIC")).status_code == 422
    assert http.post(url, json=browser_event(metadata={"x": "a" * 3000})).status_code == 422
    assert http.post(url, json=browser_event(productId=None)).status_code == 422


def test_recommendation_feedback_requires_request_id():
    http = client(); url = "/api/v1/recommendations/interactions"
    assert http.post(url, json=browser_event("RECOMMENDATION_CLICK")).status_code == 422
    assert http.post(url, json=browser_event("RECOMMENDATION_CLICK", requestId=str(uuid4()),
                                             metadata={"position": 0})).status_code == 202


def test_evaluation_api_reports_precision_and_recall():
    one, two, three = uuid4(), uuid4(), uuid4()
    body = {"modelVersion": "v1", "datasetVersion": "test-v1", "k": 2,
            "cases": [{"predicted": [str(one), str(two)], "relevant": [str(two), str(three)]}]}
    response = client().post("/internal/v1/evaluations", json=body,
                             headers={"X-Internal-Service-Token": "test-token"})
    assert response.json()["precisionAtK"] == .5
    assert response.json()["recallAtK"] == .5
