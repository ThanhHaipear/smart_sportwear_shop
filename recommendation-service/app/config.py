from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    service_name: str = "recommendation-service"
    recommendation_enabled: bool = False
    recommendation_database_url: str = ""
    recommendation_internal_token: str = ""
    recommendation_fallback_mode: Literal["POPULAR", "EMPTY"] = "POPULAR"
    recommendation_default_limit: int = Field(default=8, ge=1, le=20)
    recommendation_max_limit: int = Field(default=20, ge=1, le=100)
    rabbitmq_url: str = "amqp://visual_search:change-me@localhost:5672/"
    rabbitmq_consumer_queue: str = "recommendation.events"
    rabbitmq_retry_queues: str = "recommendation.events.retry.30s,recommendation.events.retry.5m,recommendation.events.retry.1h"
    rabbitmq_dlq: str = "recommendation.events.dlq"
    rabbitmq_prefetch_count: int = Field(default=10, ge=1, le=100)

    @model_validator(mode="after")
    def validate_enabled(self) -> "Settings":
        if self.recommendation_enabled:
            missing = [name for name, value in (
                ("RECOMMENDATION_DATABASE_URL", self.recommendation_database_url),
                ("RECOMMENDATION_INTERNAL_TOKEN", self.recommendation_internal_token),
            ) if not value.strip()]
            if missing:
                raise ValueError("Recommendation is enabled but configuration is missing: " + ", ".join(missing))
        if self.recommendation_default_limit > self.recommendation_max_limit:
            raise ValueError("RECOMMENDATION_DEFAULT_LIMIT must not exceed RECOMMENDATION_MAX_LIMIT")
        return self

    @property
    def retry_queues(self) -> tuple[str, ...]:
        return tuple(value.strip() for value in self.rabbitmq_retry_queues.split(",") if value.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
