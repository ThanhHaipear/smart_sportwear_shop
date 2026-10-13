from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    CHATBOT_ENV: str = "development"
    CHATBOT_HOST: str = "0.0.0.0"
    CHATBOT_PORT: int = 8002
    CHATBOT_RELOAD: bool = False

    MODEL_PROVIDER: str = "anthropic"
    MODEL_NAME: str = "claude-sonnet-4-6"

    DB_READ_URL: str = ""
    DB_WRITE_URL: str = ""       # Optional write connection for durable chat history
    BACKEND_API_BASE_URL: str = "http://localhost:8082"
    JWT_ACCESS_SECRET: str = ""
    REDIS_URL: str = "redis://localhost:6379"
    SESSION_TTL_SECONDS: int = 3600          # Phase 9: Redis session TTL
    OBSERVABILITY_ENABLED: bool = True       # Phase 9: structured trace/tool logging
    EVALUATION_LOGGING_ENABLED: bool = True  # Phase 9: evaluation event logging

    CORS_ALLOWED_ORIGINS: str = "http://localhost:3000,http://localhost:3001"
    LOG_LEVEL: str = "INFO"
    ANTHROPIC_API_KEY: str = ""
    OPENAI_API_KEY: str = ""        # Used for LLM (when MODEL_PROVIDER=openai) AND embeddings

    # Vector search — OpenAI embeddings (independent of MODEL_PROVIDER)
    EMBEDDING_MODEL: str = "text-embedding-3-small"
    EMBEDDING_DIMS: int = 1536
    PRODUCT_SEARCH_INTERNAL_TOKEN: str = ""
    PRODUCT_SEARCH_SEMANTIC_ENABLED: bool = True
    PRODUCT_SEARCH_LLM_REWRITE_ENABLED: bool = False
    PRODUCT_SEARCH_RRF_K: int = 60
    PRODUCT_SEARCH_KEYWORD_WEIGHT: float = 1.2
    PRODUCT_SEARCH_SEMANTIC_WEIGHT: float = 1.0
    PRODUCT_SEARCH_MIN_SIMILARITY: float = 0.40
    PRODUCT_SEARCH_QUERY_CACHE_TTL_SECONDS: int = 604800
    PRODUCT_SEARCH_CANDIDATE_CACHE_TTL_SECONDS: int = 180
    PRODUCT_SEARCH_INDEXING_ENABLED: bool = False
    RABBITMQ_URL: str = "amqp://visual_search:change-me@localhost:5672/"
    PRODUCT_SEARCH_INDEXING_QUEUE: str = "product-search.indexing"
    PRODUCT_SEARCH_INDEXING_RETRY_QUEUES: str = (
        "product-search.indexing.retry.30s,product-search.indexing.retry.5m,"
        "product-search.indexing.retry.1h"
    )
    PRODUCT_SEARCH_INDEXING_DLQ: str = "product-search.indexing.dlq"
    PRODUCT_SEARCH_INDEXING_PREFETCH: int = 5
    PRODUCT_SEARCH_RECONCILIATION_INTERVAL_SECONDS: int = 3600
    PRODUCT_SEARCH_RECONCILIATION_BATCH_SIZE: int = 100
    CATALOG_INDEX_BACKFILL_ON_STARTUP: bool = True
    CATALOG_INDEXING_MAX_ITEM_ATTEMPTS: int = 3

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
