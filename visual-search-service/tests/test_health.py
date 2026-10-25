import asyncio

from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, patch

from app.config import Settings, get_settings
from app.main import app, lifespan
from app.services.readiness import ReadinessChecker, ReadinessResult


def test_liveness() -> None:
    response = TestClient(app).get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "visual-search-service"}


def test_readiness_is_disabled_by_default() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)
    try:
        response = TestClient(app).get("/health/ready")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["enabled"] is False


def test_readiness_checks_dependencies_when_enabled() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        visual_search_enabled=True,
        database_url="postgresql://configured",
        rabbitmq_url="amqp://configured",
        internal_service_token="configured",
        cloudinary_cloud_name="demo",
    )
    try:
        with patch(
            "app.api.health.ReadinessChecker.check",
            new=AsyncMock(return_value=ReadinessResult(True, "up", "active", "complete", "not_required")),
        ):
            response = TestClient(app).get("/health/ready")
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    assert response.json()["status"] == "ready"
    assert response.json()["checks"]["coverage"] == "complete"


def test_readiness_returns_503_when_database_or_model_is_down() -> None:
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        visual_search_enabled=True,
        database_url="postgresql://configured",
        rabbitmq_url="amqp://configured",
        internal_service_token="configured",
        cloudinary_cloud_name="demo",
    )
    try:
        with patch(
            "app.api.health.ReadinessChecker.check",
            new=AsyncMock(return_value=ReadinessResult(False, "down", "unknown", "unknown", "not_required")),
        ):
            response = TestClient(app).get("/health/ready")
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 503
    assert response.json()["status"] == "not_ready"
    assert response.json()["checks"]["database"] == "down"


def test_query_readiness_does_not_require_rabbitmq() -> None:
    settings = Settings(
        _env_file=None,
        visual_search_enabled=True,
        database_url="postgresql://configured",
        rabbitmq_url="amqp://unavailable",
        internal_service_token="configured",
        cloudinary_cloud_name="demo",
    )
    checker = ReadinessChecker(settings)
    with patch.object(
        checker,
        "_check_database_model_and_coverage",
        new=AsyncMock(return_value=("up", "active", "complete")),
    ):
        result = asyncio.run(checker.check())

    assert result.ready is True
    assert result.rabbitmq == "not_required"


def test_query_readiness_fails_closed_when_coverage_is_incomplete() -> None:
    settings = Settings(
        _env_file=None,
        visual_search_enabled=True,
        database_url="postgresql://configured",
        internal_service_token="configured",
        cloudinary_cloud_name="demo",
    )
    checker = ReadinessChecker(settings)
    with patch.object(
        checker,
        "_check_database_model_and_coverage",
        new=AsyncMock(return_value=("up", "active", "incomplete")),
    ):
        result = asyncio.run(checker.check())

    assert result.ready is False
    assert result.coverage == "incomplete"


def test_lifespan_preloads_local_model_before_serving() -> None:
    settings = Settings(
        _env_file=None,
        visual_search_enabled=True,
        visual_embedding_provider="hugging_face_local",
        database_url="postgresql://configured",
        internal_service_token="configured",
        cloudinary_cloud_name="demo",
    )
    provider = AsyncMock()

    async def run_lifespan() -> None:
        with (
            patch("app.main.get_settings", return_value=settings),
            patch("app.main.build_provider", return_value=provider),
            patch("app.main.Siglip2EmbeddingProvider", object),
            patch("app.main.close_provider_clients", new=AsyncMock()) as close_clients,
        ):
            async with lifespan(app):
                provider.prepare.assert_awaited_once()
            close_clients.assert_awaited_once()

    asyncio.run(run_lifespan())
