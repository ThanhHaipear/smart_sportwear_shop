from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from app.config import get_settings
from app.main import create_app


def configured_settings():
    settings = get_settings()
    settings.search_enabled = False
    settings.internal_service_token = "test-token"
    settings.visual_embedding_provider = "fake"
    return settings


def enabled_settings():
    settings = configured_settings()
    settings.search_enabled = True
    settings.database_url = "postgresql://unused"
    return settings


def test_liveness_and_disabled_readiness(monkeypatch):
    app = create_app()
    app.dependency_overrides[get_settings] = configured_settings
    client = TestClient(app)
    assert client.get("/health/live").json()["service"] == "search-service"
    assert client.get("/health/ready").json() == {"status": "disabled", "enabled": False}


def test_search_requires_internal_token(monkeypatch):
    app = create_app()
    app.dependency_overrides[get_settings] = configured_settings
    client = TestClient(app)
    response = client.post("/internal/v1/search", json={"query": "áo chạy bộ"})
    assert response.status_code == 401


def test_legacy_product_search_alias_uses_same_contract(monkeypatch):
    app = create_app()
    app.dependency_overrides[get_settings] = configured_settings
    client = TestClient(app)
    response = client.post(
        "/internal/v1/product-search",
        headers={"X-Internal-Token": "test-token"},
        json={"query": "áo chạy bộ"},
    )
    assert response.status_code == 503


def test_image_route_is_separate_from_json_search(monkeypatch):
    app = create_app()
    app.dependency_overrides[get_settings] = configured_settings
    image = Image.new("RGB", (8, 8), "red")
    payload = BytesIO()
    image.save(payload, "PNG")
    client = TestClient(app)
    response = client.post(
        "/internal/v1/search/image",
        headers={"X-Internal-Service-Token": "test-token"},
        files={"image": ("query.png", payload.getvalue(), "image/png")},
    )
    assert response.status_code == 503


def test_invalid_image_is_rejected_before_repository():
    app = create_app()
    app.dependency_overrides[get_settings] = enabled_settings
    client = TestClient(app)
    response = client.post(
        "/internal/v1/search/image",
        headers={"X-Internal-Service-Token": "test-token"},
        files={"image": ("query.txt", b"not-an-image", "text/plain")},
    )
    assert response.status_code == 422
