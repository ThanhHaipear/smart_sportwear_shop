"""Review R4 (PR #1): browser metadata is limited to an allow-list per event type, the same on both endpoints."""
from uuid import uuid4

import pytest

from app.interactions import BROWSER_EVENT_TYPES, allowed_metadata_fields
from app.repository import MemoryRepository
from tests.test_contracts import BATCH_URL, browser_event, client

SINGLE_URL = "/api/v1/recommendations/interactions"


def feedback(event_type="RECOMMENDATION_IMPRESSION", **metadata):
    return browser_event(event_type, requestId=str(uuid4()), metadata=metadata)


def post(http, url, event):
    """Send one event through either endpoint so every rule is checked on both."""
    return http.post(url, json=event if url == SINGLE_URL else {"events": [event]})


@pytest.fixture(params=[SINGLE_URL, BATCH_URL], ids=["single", "batch"])
def url(request):
    return request.param


def test_every_browser_event_type_has_an_allow_list():
    assert {t for t in BROWSER_EVENT_TYPES if allowed_metadata_fields(t) is not None} == BROWSER_EVENT_TYPES
    assert allowed_metadata_fields("VIEW") == frozenset()
    assert allowed_metadata_fields("RECOMMENDATION_CLICK") == {
        "position", "algorithm", "modelVersion", "sourceProductId", "sourceProductIds"}


def test_storefront_payloads_are_stored(url):
    repo = MemoryRepository(); http = client(repo); cart = [str(uuid4()), str(uuid4())]
    events = [
        feedback(position=0, algorithm="ASSOCIATION_RULE", modelVersion="rules-v3", sourceProductId=str(uuid4())),
        feedback("RECOMMENDATION_CLICK", position=3, algorithm=None, modelVersion=None, sourceProductIds=cart),
        browser_event("ADD_TO_CART", variantId=str(uuid4()), metadata={"quantity": 2}),
        browser_event("VIEW"),
    ]
    for event in events:
        assert post(http, url, event).status_code == 202
    stored = [event.metadata for event in repo.interactions]
    assert stored[0] == {"position": 0, "algorithm": "ASSOCIATION_RULE",
                         "sourceProductId": events[0]["metadata"]["sourceProductId"]}
    assert stored[1] == {"position": 3, "sourceProductIds": cart}  # nulls are not stored
    assert stored[2] == {"quantity": 2}
    assert stored[3] == {}


@pytest.mark.parametrize("event", [
    pytest.param(lambda: browser_event(metadata={"email": "a@example.com"}), id="field on VIEW"),
    pytest.param(lambda: browser_event("WISHLIST", metadata={"position": 1}), id="position on WISHLIST"),
    pytest.param(lambda: browser_event("ADD_TO_CART", metadata={"quantity": 2, "price": 100}), id="extra on cart"),
    pytest.param(lambda: feedback(position=0, query="giay chay bo nam"), id="free text on impression"),
    pytest.param(lambda: browser_event("SEARCH_CLICK", metadata={"position": 0, "query": "x"}), id="raw query"),
])
def test_unknown_fields_are_rejected(url, event):
    repo = MemoryRepository(); http = client(repo)
    response = post(http, url, event())
    assert response.status_code == 422
    assert "invalid metadata" in response.json()["detail"]
    assert repo.interactions == []


@pytest.mark.parametrize("metadata", [
    pytest.param({"position": "1"}, id="position as string"),
    pytest.param({"position": True}, id="position as boolean"),
    pytest.param({"position": 1.5}, id="position as float"),
    pytest.param({"position": -1}, id="negative position"),
    pytest.param({"position": 100}, id="position too large"),
    pytest.param({"algorithm": "COLLABORATIVE"}, id="unknown algorithm"),
    pytest.param({"modelVersion": ""}, id="empty model version"),
    pytest.param({"modelVersion": "v" * 51}, id="model version too long"),
    pytest.param({"sourceProductId": "not-a-uuid"}, id="source id not a uuid"),
    pytest.param({"sourceProductIds": []}, id="empty source list"),
    pytest.param({"sourceProductIds": ["not-a-uuid"]}, id="source list item not a uuid"),
    pytest.param({"sourceProductId": "6f2c1b7e-0d3a-4f5b-9c8e-1a2b3c4d5e6f",
                  "sourceProductIds": ["6f2c1b7e-0d3a-4f5b-9c8e-1a2b3c4d5e6f"]}, id="both source shapes"),
])
def test_wrong_types_are_rejected(url, metadata):
    repo = MemoryRepository()
    assert post(client(repo), url, feedback(**metadata)).status_code == 422
    assert repo.interactions == []


@pytest.mark.parametrize("quantity", [0, 100, "2", True])
def test_add_to_cart_quantity_is_a_small_integer(url, quantity):
    event = browser_event("ADD_TO_CART", metadata={"quantity": quantity})
    assert post(client(), url, event).status_code == 422


def test_batch_with_one_bad_metadata_writes_nothing():
    repo = MemoryRepository(); http = client(repo); request_id = str(uuid4())
    good = [browser_event("RECOMMENDATION_IMPRESSION", requestId=request_id, metadata={"position": p})
            for p in range(3)]
    bad = browser_event("RECOMMENDATION_IMPRESSION", requestId=request_id, metadata={"position": 3, "email": "x"})
    response = http.post(BATCH_URL, json={"events": good + [bad]})
    assert response.status_code == 422
    assert "invalid metadata" in response.json()["detail"]
    assert repo.interactions == []


@pytest.mark.parametrize("data_source", ["REAL", "DEMO"])
def test_browser_collection_is_disabled_by_default(url, data_source):
    repo = MemoryRepository()
    http = client(repo, interaction_real_enabled=False)
    response = post(http, url, browser_event(dataSource=data_source))
    assert response.status_code == 422
    assert f"INTERACTION_{data_source}_ENABLED" in response.json()["detail"]
    assert repo.interactions == []


def test_demo_opt_in_does_not_store_the_account_id(url):
    repo = MemoryRepository()
    http = client(repo, interaction_real_enabled=False, interaction_demo_enabled=True)
    event = browser_event(dataSource="DEMO")
    body = event if url == SINGLE_URL else {"events": [event]}
    assert http.post(url, json=body, headers={"X-User-Id": str(uuid4())}).status_code == 202
    assert repo.interactions[0].user_ref is None
    assert repo.interactions[0].anonymous_id == event["anonymousId"]


def test_tracking_flags_are_off_by_default():
    from app.config import Settings
    settings = Settings(_env_file=None)
    assert settings.interaction_real_enabled is False
    assert settings.interaction_demo_enabled is False


def test_recommendation_requests_omit_account_when_real_tracking_is_off():
    repo = MemoryRepository()
    http = client(repo, interaction_real_enabled=False)
    product_id, user_id = str(uuid4()), str(uuid4())
    headers = {"X-User-Id": user_id}
    assert http.get(f"/api/v1/recommendations/products/{product_id}", headers=headers).status_code == 200
    assert http.post("/api/v1/recommendations/cart", headers=headers,
                     json={"sourceProductIds": [product_id]}).status_code == 200
    assert [entry["userRef"] for entry in repo.request_log] == [None, None]
