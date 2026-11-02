import io
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.config import Settings, get_settings
from app.main import app
from app.persistence.repository import ModelVersion, SearchCandidate
from app.providers.base import EmbeddingResult, EmbeddingUsage
from app.providers.voyage import VoyageProviderError


def png_bytes() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (4, 4), "red").save(output, "PNG")
    return output.getvalue()


def enabled_settings() -> Settings:
    return Settings(
        _env_file=None,
        visual_search_enabled=True,
        visual_embedding_provider="fake",
        visual_embedding_dims=4,
        database_url="postgresql://configured",
        rabbitmq_url="amqp://configured",
        internal_service_token="secret",
        cloudinary_cloud_name="demo",
    )


@pytest.mark.parametrize(
    "headers",
    [{}, {"X-Internal-Service-Token": "wrong-token"}],
)
def test_search_requires_correct_internal_token_without_leaking_secret(headers: dict[str, str]) -> None:
    app.dependency_overrides[get_settings] = enabled_settings
    try:
        response = TestClient(app).post(
            "/internal/v1/search",
            headers=headers,
            files={"image": ("query.png", png_bytes(), "image/png")},
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 401
    assert response.json()["detail"] == "Invalid internal service token"
    assert "secret" not in response.text


def test_search_normalizes_embeds_and_returns_candidates() -> None:
    model = ModelVersion(uuid4(), "fake", "fake-multimodal", 4)
    candidate = SearchCandidate(uuid4(), uuid4(), "https://cdn.shopify.com/example.jpg", 0.91)
    provider = AsyncMock()
    provider.embed_query.return_value = EmbeddingResult((0.5, 0.5, 0.5, 0.5), "fake", 4, EmbeddingUsage(16, 0))
    connection = AsyncMock()
    connection.__aenter__.return_value = connection
    search_on = AsyncMock(return_value=[candidate])

    app.dependency_overrides[get_settings] = enabled_settings
    try:
        with (
            patch("app.api.search.build_provider", return_value=provider),
            patch("app.api.search.VisualSearchRepository.current_month_cost", new=AsyncMock(return_value=0)),
            patch("app.api.search.VisualSearchRepository._connect", new=AsyncMock(return_value=connection)),
            patch("app.api.search.VisualSearchRepository.active_model_on", new=AsyncMock(return_value=model)),
            patch("app.api.search.VisualSearchRepository.search_on", new=search_on),
            patch("app.api.search.VisualSearchRepository.record_query_usage_on", new=AsyncMock()),
        ):
            response = TestClient(app).post(
                "/internal/v1/search?limit=50",
                headers={"X-Internal-Service-Token": "secret"},
                files={"image": ("query.png", png_bytes(), "image/png")},
            )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert search_on.await_args.args[3] == 50
    assert len(search_on.await_args.args[4]) == 14
    assert search_on.await_args.args[5] == 0.15
    assert search_on.await_args.args[6] == 0.10
    assert response.json()["candidates"][0]["product_id"] == str(candidate.product_id)
    assert response.json()["candidates"][0]["similarity"] == 0.91


def test_search_rejects_invalid_image() -> None:
    app.dependency_overrides[get_settings] = enabled_settings
    try:
        response = TestClient(app).post(
            "/internal/v1/search",
            headers={"X-Internal-Service-Token": "secret"},
            files={"image": ("query.png", b"not-an-image", "image/png")},
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 422


def test_search_returns_controlled_error_when_provider_is_unavailable() -> None:
    model = ModelVersion(uuid4(), "voyage", "voyage-multimodal-3.5", 4)
    provider = AsyncMock()
    provider.embed_query.side_effect = VoyageProviderError("upstream detail must not leak")
    connection = AsyncMock()
    connection.__aenter__.return_value = connection
    app.dependency_overrides[get_settings] = enabled_settings
    try:
        with (
            patch("app.api.search.build_provider", return_value=provider),
            patch("app.api.search.VisualSearchRepository.current_month_cost", new=AsyncMock(return_value=0)),
            patch("app.api.search.VisualSearchRepository._connect", new=AsyncMock(return_value=connection)),
            patch("app.api.search.VisualSearchRepository.active_model_on", new=AsyncMock(return_value=model)),
        ):
            response = TestClient(app).post(
                "/internal/v1/search",
                headers={"X-Internal-Service-Token": "secret"},
                files={"image": ("query.png", png_bytes(), "image/png")},
            )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 503
    assert response.json()["detail"] == "Visual embedding provider is unavailable"
    assert "upstream detail" not in response.text


def test_search_rejects_active_model_revision_mismatch_before_embedding() -> None:
    settings = enabled_settings()
    settings = settings.model_copy(
        update={
            "visual_embedding_provider": "hugging_face_local",
            "visual_embedding_model": "google/siglip2-base-patch16-224",
            "visual_embedding_revision": "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2",
            "visual_embedding_preprocessing": "siglip2-autoprocessor-original-v1",
            "visual_embedding_dims": 768,
        }
    )
    model = ModelVersion(
        uuid4(),
        "HUGGING_FACE_LOCAL",
        settings.visual_embedding_model,
        768,
        "stale-revision",
        settings.visual_embedding_preprocessing,
    )
    connection = AsyncMock()
    connection.__aenter__.return_value = connection

    app.dependency_overrides[get_settings] = lambda: settings
    try:
        with (
            patch("app.api.search.build_provider") as build_provider,
            patch("app.api.search.VisualSearchRepository._connect", new=AsyncMock(return_value=connection)),
            patch("app.api.search.VisualSearchRepository.active_model_on", new=AsyncMock(return_value=model)),
        ):
            response = TestClient(app).post(
                "/internal/v1/search",
                headers={"X-Internal-Service-Token": "secret"},
                files={"image": ("query.png", png_bytes(), "image/png")},
            )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 503
    assert response.json()["detail"] == "Active visual model does not match the configured SigLIP2 index"
    build_provider.assert_not_called()


def test_search_stops_when_monthly_budget_is_exhausted() -> None:
    app.dependency_overrides[get_settings] = enabled_settings
    try:
        with patch("app.api.search.VisualSearchRepository.current_month_cost", new=AsyncMock(return_value=20)):
            response = TestClient(app).post(
                "/internal/v1/search", headers={"X-Internal-Service-Token": "secret"},
                files={"image": ("query.png", png_bytes(), "image/png")},
            )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 429
