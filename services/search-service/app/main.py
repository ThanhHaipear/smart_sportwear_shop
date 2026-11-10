import logging
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request

from app.api.health import router as health_router
from app.api.search import router as search_router
from app.config import get_settings
from app.providers.factory import build_provider, close_provider_clients


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    if settings.search_enabled and settings.visual_search_enabled and settings.visual_embedding_provider == "hugging_face_local":
        provider = build_provider(settings)
        prepare = getattr(provider, "prepare", None)
        if prepare:
            await prepare()
    yield
    await close_provider_clients()


def create_app() -> FastAPI:
    application = FastAPI(
        title="Smart Sportswear Search Service",
        description="Unified attribute, semantic and image retrieval",
        version="1.0.0",
        lifespan=lifespan,
    )
    application.include_router(health_router)
    application.include_router(search_router)

    @application.middleware("http")
    async def correlation(request: Request, call_next):
        correlation_id = request.headers.get("X-Correlation-ID") or str(uuid.uuid4())
        started = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Correlation-ID"] = correlation_id
        logging.getLogger("search.access").info(
            "request method=%s path=%s status=%s correlationId=%s durationMs=%s",
            request.method, request.url.path, response.status_code, correlation_id,
            round((time.perf_counter() - started) * 1000),
        )
        return response
    return application


app = create_app()
