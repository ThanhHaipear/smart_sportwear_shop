from datetime import datetime, timezone
from uuid import uuid4

from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.dependencies import catalog_client, interaction_limiter, repository
from app.interactions import InteractionRateLimiter, SlidingWindowLimiter
from tests.fakes import FakeCatalog
from app.main import create_app
from app.repository import MemoryRepository
from app.schemas import ProductSnapshot


def settings():
    return Settings(recommendation_enabled=True, recommendation_database_url="postgresql://unused",
                    recommendation_internal_token="test-token")


def client(repo=None, limiter=None):
    app = create_app()
    app.dependency_overrides[get_settings] = settings
    app.dependency_overrides[repository] = lambda: repo or MemoryRepository()
    app.dependency_overrides[catalog_client] = FakeCatalog
    shared_limiter = limiter or InteractionRateLimiter(per_identity=1000, per_ip=1000)
    app.dependency_overrides[interaction_limiter] = lambda: shared_limiter
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
    assert response.json()["hitRateAtK"] == 1.0
    assert abs(response.json()["ndcgAtK"] - (1 / 1.585) / (1 + 1 / 1.585)) < 1e-3  # hit at rank 2 of 2 relevant
    assert response.json()["caseCount"] == 1


def test_interactions_are_rate_limited_per_identity():
    http = client(limiter=InteractionRateLimiter(per_identity=2, per_ip=100))
    url = "/api/v1/recommendations/interactions"
    codes = [http.post(url, json=browser_event()).status_code for _ in range(3)]
    assert codes == [202, 202, 429]
    # Another browser (different anonymousId) is not affected.
    assert http.post(url, json=browser_event(anonymousId="other-browser-1")).status_code == 202


def test_interactions_are_rate_limited_per_ip():
    http = client(limiter=InteractionRateLimiter(per_identity=100, per_ip=2))
    url = "/api/v1/recommendations/interactions"
    headers = {"X-Forwarded-For": "203.0.113.7"}
    codes = [http.post(url, json=browser_event(anonymousId=f"rotating-anon-{i}"), headers=headers).status_code
             for i in range(3)]
    assert codes == [202, 202, 429]
    assert http.post(url, json=browser_event(anonymousId="rotating-anon-x"),
                     headers={"X-Forwarded-For": "198.51.100.9"}).status_code == 202


def test_rejected_events_do_not_consume_rate_limit():
    http = client(limiter=InteractionRateLimiter(per_identity=1, per_ip=100))
    url = "/api/v1/recommendations/interactions"
    assert http.post(url, json=browser_event("PURCHASE")).status_code == 422
    assert http.post(url, json=browser_event()).status_code == 202


def test_sliding_window_frees_capacity_after_one_minute():
    now = [0.0]
    limiter = SlidingWindowLimiter(limit=1, clock=lambda: now[0])
    assert limiter.allow("k") is True
    assert limiter.allow("k") is False
    now[0] = 60.5
    assert limiter.allow("k") is True


BATCH_URL = "/api/v1/recommendations/interactions/batch"


def impressions(count, request_id=None, anonymous_id="anon-123456"):
    request_id = request_id or str(uuid4())
    return [browser_event("RECOMMENDATION_IMPRESSION", requestId=request_id, anonymousId=anonymous_id,
                          metadata={"position": position}) for position in range(count)]


def test_batch_stores_every_event_in_one_request():
    repo = MemoryRepository(); http = client(repo); user = str(uuid4())
    response = http.post(BATCH_URL, json={"events": impressions(4)}, headers={"X-User-Id": user})
    assert response.status_code == 202
    assert response.json()["accepted"] == 4 and response.json()["duplicates"] == 0
    assert len(repo.interactions) == 4
    assert {event.user_ref for event in repo.interactions} == {user}
    assert sorted(event.metadata["position"] for event in repo.interactions) == [0, 1, 2, 3]


def test_batch_replay_is_idempotent():
    repo = MemoryRepository(); http = client(repo); events = impressions(3)
    http.post(BATCH_URL, json={"events": events})
    replay = http.post(BATCH_URL, json={"events": events}).json()
    assert (replay["accepted"], replay["duplicates"]) == (0, 3)
    assert len(repo.interactions) == 3


def test_batch_is_all_or_nothing():
    repo = MemoryRepository(); http = client(repo)
    events = impressions(2) + [browser_event("PURCHASE")]
    assert http.post(BATCH_URL, json={"events": events}).status_code == 422
    assert repo.interactions == []


def test_batch_rejects_mixed_browsers_duplicate_ids_and_oversize():
    http = client()
    mixed = impressions(1) + impressions(1, anonymous_id="another-browser")
    assert http.post(BATCH_URL, json={"events": mixed}).status_code == 422
    event = impressions(1)[0]
    assert http.post(BATCH_URL, json={"events": [event, event]}).status_code == 422
    assert http.post(BATCH_URL, json={"events": impressions(11)}).status_code == 422
    assert http.post(BATCH_URL, json={"events": []}).status_code == 422


def test_batch_rate_limit_counts_events_not_requests():
    http = client(limiter=InteractionRateLimiter(per_identity=5, per_ip=100))
    assert http.post(BATCH_URL, json={"events": impressions(4)}).status_code == 202
    # Only 1 slot left: a batch of 2 is refused entirely, a single event still fits.
    assert http.post(BATCH_URL, json={"events": impressions(2)}).status_code == 429
    assert http.post("/api/v1/recommendations/interactions", json=browser_event()).status_code == 202


def test_rejection_by_one_limit_does_not_charge_the_other():
    limiter = InteractionRateLimiter(per_identity=1, per_ip=3)
    http = client(limiter=limiter); headers = {"X-Forwarded-For": "203.0.113.50"}
    assert http.post(BATCH_URL, json={"events": impressions(1)}, headers=headers).status_code == 202
    # Identity is exhausted; the IP must not be charged for the refused batch.
    assert http.post(BATCH_URL, json={"events": impressions(2)}, headers=headers).status_code == 429
    other = impressions(2, anonymous_id="second-browser")
    assert http.post(BATCH_URL, json={"events": other}, headers=headers).status_code == 429  # identity 1 < 2
    assert http.post(BATCH_URL, json={"events": impressions(1, anonymous_id="third-browser")},
                     headers=headers).status_code == 202  # IP used 2 of 3


def test_sliding_window_cost_is_all_or_nothing():
    limiter = SlidingWindowLimiter(limit=3, clock=lambda: 0.0)
    assert limiter.allow("k", cost=2) is True
    assert limiter.allow("k", cost=2) is False
    assert limiter.allow("k", cost=1) is True
