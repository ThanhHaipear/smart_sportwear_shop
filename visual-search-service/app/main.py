from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.admin import router as admin_router
from app.api.health import router as health_router
from app.api.search import router as search_router
from app.config import get_settings
from app.providers.factory import build_provider, close_provider_clients
from app.providers.siglip2 import Siglip2EmbeddingProvider


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    if settings.visual_search_enabled and settings.visual_embedding_provider == "hugging_face_local":
        provider = build_provider(settings)
        if not isinstance(provider, Siglip2EmbeddingProvider):
            raise RuntimeError("Local embedding provider was not initialized correctly")
        await provider.prepare()
    try:
        yield
    finally:
        await close_provider_clients()


def create_app() -> FastAPI:
    settings = get_settings()
    application = FastAPI(title="Visual Product Search", version="0.1.0", lifespan=lifespan)
    application.include_router(health_router)
    application.include_router(search_router)
    application.include_router(admin_router)
    return application


app = create_app()
