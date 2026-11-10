from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    service_name: str = "search-service"
    search_enabled: bool = False
    database_url: str = ""
    catalog_api_url: str = "http://127.0.0.1:8080"
    internal_service_token: str = ""
    catalog_request_timeout_seconds: float = Field(default=5, gt=0, le=30)
    redis_url: str = ""

    rabbitmq_url: str = "amqp://visual_search:change-me@localhost:5672/"
    rabbitmq_consumer_queue: str = "search.indexing"
    rabbitmq_retry_queues: str = "search.indexing.retry.30s,search.indexing.retry.5m,search.indexing.retry.1h"
    rabbitmq_dlq: str = "search.indexing.dlq"
    rabbitmq_prefetch_count: int = Field(default=5, ge=1, le=100)

    text_embedding_provider: Literal["disabled", "openai"] = "disabled"
    openai_api_key: str = ""
    text_embedding_model: str = "text-embedding-3-small"
    text_embedding_dims: int = Field(default=1536, gt=0)
    text_min_similarity: float = Field(default=0.40, ge=-1, le=1)
    rrf_k: int = Field(default=60, ge=1)
    keyword_weight: float = Field(default=1.2, gt=0)
    semantic_weight: float = Field(default=1.0, gt=0)

    visual_search_enabled: bool = True
    visual_embedding_provider: Literal["fake", "voyage", "hugging_face_local"] = "hugging_face_local"
    visual_embedding_model: str = "google/siglip2-base-patch16-224"
    visual_embedding_revision: str = "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2"
    visual_embedding_preprocessing: str = "siglip2-autoprocessor-original-v1"
    visual_embedding_dims: int = 768
    visual_embedding_device: Literal["auto", "cpu", "cuda"] = "auto"
    visual_embedding_cache_dir: str = ""
    voyage_api_key: str = ""
    voyage_api_url: str = "https://api.voyageai.com/v1/multimodalembeddings"
    voyage_timeout_seconds: float = 30
    voyage_max_attempts: int = 3
    voyage_min_interval_seconds: float = 21
    cloudinary_cloud_name: str = ""
    catalog_image_allowed_hosts: str = "res.cloudinary.com,cdn.shopify.com"
    max_upload_bytes: int = 5_242_880
    max_image_pixels: int = 16_000_000
    target_image_max_width: int = 1024
    target_image_max_height: int = 1024
    catalog_download_timeout_seconds: float = 10
    catalog_download_max_redirects: int = 3
    visual_color_rerank_weight: float = 0
    visual_category_consensus_weight: float = 0.60
    # Locked on DEV v2.2. Any change requires a new DEV/independent TEST cycle.
    visual_min_similarity: float = Field(default=0.755936, ge=-1, le=1)
    monthly_budget_usd: float = 0
    image_cost_per_megapixel_usd: float = 0
    store_query_images: bool = False

    @model_validator(mode="after")
    def validate_enabled(self) -> "Settings":
        if not self.search_enabled:
            return self
        missing = [name for name, value in (
            ("DATABASE_URL", self.database_url),
            ("CATALOG_API_URL", self.catalog_api_url),
            ("INTERNAL_SERVICE_TOKEN", self.internal_service_token),
        ) if not value.strip()]
        if self.text_embedding_provider == "openai" and not self.openai_api_key.strip():
            missing.append("OPENAI_API_KEY")
        if missing:
            raise ValueError("Search is enabled but configuration is missing: " + ", ".join(missing))
        if self.text_embedding_dims != 1536:
            raise ValueError("TEXT_EMBEDDING_DIMS must match ai_search.text_embeddings vector(1536)")
        if self.visual_embedding_dims != 768:
            raise ValueError("VISUAL_EMBEDDING_DIMS must match ai_search.image_embeddings vector(768)")
        return self

    @property
    def allowed_hosts(self) -> frozenset[str]:
        return frozenset(v.strip().lower() for v in self.catalog_image_allowed_hosts.split(",") if v.strip())

    @property
    def retry_queues(self) -> tuple[str, ...]:
        return tuple(v.strip() for v in self.rabbitmq_retry_queues.split(",") if v.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
