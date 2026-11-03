from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    service_name: str = "visual-search-service"
    visual_search_enabled: bool = False
    visual_embedding_provider: Literal["fake", "voyage", "hugging_face_local"] = "hugging_face_local"
    visual_embedding_model: str = "google/siglip2-base-patch16-224"
    visual_embedding_revision: str = "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2"
    visual_embedding_preprocessing: str = "siglip2-autoprocessor-original-v1"
    visual_embedding_dims: int = Field(default=768, gt=0)
    visual_embedding_device: Literal["auto", "cpu", "cuda"] = "auto"
    visual_embedding_cache_dir: str = ""
    voyage_api_key: str = ""
    voyage_api_url: str = "https://api.voyageai.com/v1/multimodalembeddings"
    voyage_timeout_seconds: float = Field(default=30, gt=0)
    voyage_max_attempts: int = Field(default=3, ge=1, le=5)
    voyage_min_interval_seconds: float = Field(default=21, ge=0, le=120)
    database_url: str = ""
    rabbitmq_url: str = "amqp://visual_search:change-me@rabbitmq:5672/"
    rabbitmq_prefetch_count: int = Field(default=5, ge=1, le=100)
    rabbitmq_consumer_queue: str = "visual-search.indexing"
    rabbitmq_retry_queues: str = (
        "visual-search.indexing.retry.30s,visual-search.indexing.retry.5m,"
        "visual-search.indexing.retry.1h"
    )
    rabbitmq_dlq: str = "visual-search.indexing.dlq"
    reconciliation_enabled: bool = True
    reconciliation_interval_seconds: int = Field(default=3600, ge=60)
    reconciliation_initial_delay_seconds: int = Field(default=60, ge=0)
    reconciliation_processing_timeout_minutes: int = Field(default=15, ge=1)
    reconciliation_batch_size: int = Field(default=100, ge=1, le=1000)
    readiness_timeout_seconds: float = Field(default=10, gt=0, le=30)
    internal_service_token: str = ""
    cloudinary_cloud_name: str = ""
    catalog_image_allowed_hosts: str = "res.cloudinary.com,cdn.shopify.com"
    max_upload_bytes: int = Field(default=5_242_880, gt=0)
    max_image_pixels: int = Field(default=16_000_000, gt=0)
    target_image_max_width: int = Field(default=1024, gt=0)
    target_image_max_height: int = Field(default=1024, gt=0)
    catalog_download_timeout_seconds: float = Field(default=10, gt=0)
    catalog_download_max_redirects: int = Field(default=3, ge=0, le=5)
    search_rate_limit_per_minute: int = Field(default=10, gt=0)
    visual_color_rerank_weight: float = Field(default=0.0, ge=0, le=0.30)
    # Real-domain photos often contain people and background context. Allow
    # the zero-shot category signal to outweigh that context, while keeping a
    # strict ceiling so category support remains a reranker.
    visual_category_consensus_weight: float = Field(default=0.60, ge=0, le=0.60)
    monthly_budget_usd: float = Field(default=20, ge=0)
    image_cost_per_megapixel_usd: float = Field(default=0.0006, ge=0)
    store_query_images: bool = False

    @model_validator(mode="after")
    def validate_enabled_configuration(self) -> "Settings":
        if not self.visual_search_enabled:
            return self

        missing = [
            name
            for name, value in (
                ("DATABASE_URL", self.database_url),
                ("RABBITMQ_URL", self.rabbitmq_url),
                ("INTERNAL_SERVICE_TOKEN", self.internal_service_token),
                ("CLOUDINARY_CLOUD_NAME", self.cloudinary_cloud_name),
            )
            if not value.strip()
        ]
        if self.visual_embedding_provider == "voyage" and not self.voyage_api_key.strip():
            missing.append("VOYAGE_API_KEY")
        if missing:
            raise ValueError(
                "Visual search is enabled but required configuration is missing: "
                + ", ".join(missing)
            )

        if self.visual_embedding_provider == "hugging_face_local":
            expected = {
                "VISUAL_EMBEDDING_MODEL": (
                    self.visual_embedding_model,
                    "google/siglip2-base-patch16-224",
                ),
                "VISUAL_EMBEDDING_REVISION": (
                    self.visual_embedding_revision,
                    "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2",
                ),
                "VISUAL_EMBEDDING_PREPROCESSING": (
                    self.visual_embedding_preprocessing,
                    "siglip2-autoprocessor-original-v1",
                ),
                "VISUAL_EMBEDDING_DIMS": (self.visual_embedding_dims, 768),
            }
            mismatches = [name for name, (actual, required) in expected.items() if actual != required]
            if mismatches:
                raise ValueError(
                    "SigLIP2 configuration must match the active V2 index: " + ", ".join(mismatches)
                )

        required_hosts = {"res.cloudinary.com", "cdn.shopify.com"}
        if not required_hosts.issubset(self.allowed_hosts):
            raise ValueError(
                "CATALOG_IMAGE_ALLOWED_HOSTS must include res.cloudinary.com and cdn.shopify.com"
            )
        return self

    @property
    def allowed_hosts(self) -> frozenset[str]:
        return frozenset(host.strip().lower() for host in self.catalog_image_allowed_hosts.split(",") if host.strip())

    @property
    def retry_queues(self) -> tuple[str, ...]:
        return tuple(queue.strip() for queue in self.rabbitmq_retry_queues.split(",") if queue.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
