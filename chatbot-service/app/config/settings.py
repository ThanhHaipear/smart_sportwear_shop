from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    CHATBOT_ENV: str = "development"
    CHATBOT_HOST: str = "0.0.0.0"
    CHATBOT_PORT: int = 8002
    CHATBOT_RELOAD: bool = False

    MODEL_PROVIDER: str = "anthropic"
    MODEL_NAME: str = "claude-sonnet-4-6"

    # CHATBOT_DATABASE_URL is the only runtime database and must use chatbot_app.
    # DB_WRITE_URL remains a temporary compatibility alias during cut-over.
    CHATBOT_DATABASE_URL: str = ""
    DB_WRITE_URL: str = ""
    SEARCH_API_BASE_URL: str = "http://localhost:8090"
    SEARCH_SERVICE_TOKEN: str = ""
    SEARCH_API_TIMEOUT_SECONDS: float = 5.0
    CATALOG_API_BASE_URL: str = "http://localhost:8092"
    CATALOG_SERVICE_TOKEN: str = ""
    CATALOG_API_TIMEOUT_SECONDS: float = 5.0
    BACKEND_API_BASE_URL: str = "http://localhost:8085"
    JWT_ACCESS_SECRET: str = ""
    REDIS_URL: str = "redis://localhost:6379"
    SESSION_TTL_SECONDS: int = 3600          # Phase 9: Redis session TTL
    OBSERVABILITY_ENABLED: bool = True       # Phase 9: structured trace/tool logging
    EVALUATION_LOGGING_ENABLED: bool = True  # Phase 9: evaluation event logging
    CHATBOT_METRICS_ENABLED: bool = True

    CORS_ALLOWED_ORIGINS: str = "http://localhost:3000,http://localhost:3001"
    LOG_LEVEL: str = "INFO"
    ANTHROPIC_API_KEY: str = ""
    OPENAI_API_KEY: str = ""        # Used for LLM (when MODEL_PROVIDER=openai) AND embeddings

    # Vector search — OpenAI embeddings (independent of MODEL_PROVIDER)
    EMBEDDING_MODEL: str = "text-embedding-3-small"
    EMBEDDING_DIMS: int = 1536
    PRODUCT_SEARCH_QUERY_CACHE_TTL_SECONDS: int = 604800

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
