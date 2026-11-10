from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.api.routes import router
from app.config.settings import settings
from app.observability.trace_logger import get_logger, setup_logging
from app.tools.registry import setup_registry
from app.db import pool as db_pool
from app.memory.store_factory import init_store


@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging(settings.LOG_LEVEL)
    logger = get_logger(__name__)

    if settings.CHATBOT_ENV.lower() == "production":
        required = {
            "JWT_ACCESS_SECRET": settings.JWT_ACCESS_SECRET,
            "CHATBOT_DATABASE_URL": settings.CHATBOT_DATABASE_URL or settings.DB_WRITE_URL,
            "SEARCH_SERVICE_TOKEN": settings.SEARCH_SERVICE_TOKEN,
            "CATALOG_SERVICE_TOKEN": settings.CATALOG_SERVICE_TOKEN,
        }
        missing = [name for name, value in required.items() if not value]
        if missing:
            raise RuntimeError("Missing production configuration: " + ", ".join(missing))
    if not settings.JWT_ACCESS_SECRET:
        logger.warning("chatbot-service | JWT_ACCESS_SECRET not set — authenticated tools are disabled")

    database_url = settings.CHATBOT_DATABASE_URL or settings.DB_WRITE_URL
    if database_url:
        await db_pool.init_write_pool(database_url)
        logger.info("chatbot-service | db_write_pool=ready")
    else:
        logger.warning("chatbot-service | DB_WRITE_URL not set — durable chat history disabled")

    # Session store (Phase 9) — Redis if configured, else in-memory fallback
    await init_store(
        redis_url=settings.REDIS_URL,
        ttl=settings.SESSION_TTL_SECONDS,
    )

    setup_registry()

    logger.info(
        f"chatbot-service starting | env={settings.CHATBOT_ENV} "
        f"host={settings.CHATBOT_HOST} port={settings.CHATBOT_PORT}"
    )
    yield

    from app.graph.nodes.save_result import drain_pending_saves
    from app.services.embedder import close_clients
    await drain_pending_saves()
    await close_clients()
    await db_pool.close_pool()
    logger.info("chatbot-service shutting down")


app = FastAPI(
    title="chatbot-service",
    description="AI chatbot service — Phase 10",
    version="0.10.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ALLOWED_ORIGINS.split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from app.observability.http_middleware import CorrelationIdMiddleware
app.add_middleware(CorrelationIdMiddleware)

app.include_router(router)
