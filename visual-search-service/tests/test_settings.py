import pytest
from pydantic import ValidationError

from app.config.settings import Settings


def enabled_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "visual_search_enabled": True,
        "visual_embedding_provider": "fake",
        "database_url": "postgresql://example.invalid/catalog",
        "rabbitmq_url": "amqp://visual_search:change-me@localhost:5672/",
        "internal_service_token": "local-only-token",
        "cloudinary_cloud_name": "demo-cloud",
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_enabled_configuration_accepts_complete_safe_local_config() -> None:
    settings = enabled_settings()

    assert settings.visual_search_enabled is True
    assert settings.allowed_hosts == frozenset({"res.cloudinary.com", "cdn.shopify.com"})
    assert settings.max_image_pixels == 16_000_000
    assert settings.reconciliation_enabled is True
    assert settings.reconciliation_interval_seconds == 3600


@pytest.mark.parametrize(
    ("field", "environment_name"),
    [
        ("database_url", "DATABASE_URL"),
        ("rabbitmq_url", "RABBITMQ_URL"),
        ("internal_service_token", "INTERNAL_SERVICE_TOKEN"),
        ("cloudinary_cloud_name", "CLOUDINARY_CLOUD_NAME"),
    ],
)
def test_enabled_configuration_rejects_missing_required_value(
    field: str, environment_name: str
) -> None:
    with pytest.raises(ValidationError, match=environment_name):
        enabled_settings(**{field: ""})


def test_voyage_requires_api_key_when_feature_is_enabled() -> None:
    with pytest.raises(ValidationError, match="VOYAGE_API_KEY"):
        enabled_settings(visual_embedding_provider="voyage", voyage_api_key="")


def test_siglip2_configuration_must_match_v2_index() -> None:
    with pytest.raises(ValidationError, match="VISUAL_EMBEDDING_DIMS"):
        enabled_settings(visual_embedding_provider="hugging_face_local", visual_embedding_dims=1024)


def test_catalog_allowlist_requires_both_supported_sources() -> None:
    with pytest.raises(ValidationError, match="CATALOG_IMAGE_ALLOWED_HOSTS"):
        enabled_settings(catalog_image_allowed_hosts="res.cloudinary.com")
