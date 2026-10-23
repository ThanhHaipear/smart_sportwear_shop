import httpx

from app.config import Settings

from .base import MultimodalEmbeddingProvider
from .fake import FakeEmbeddingProvider
from .siglip2 import Siglip2EmbeddingProvider
from .voyage import VoyageEmbeddingProvider

_http_client: httpx.AsyncClient | None = None
_local_provider: Siglip2EmbeddingProvider | None = None


def _shared_http_client(settings: Settings) -> httpx.AsyncClient:
    global _http_client
    if _http_client is None:
        timeout = httpx.Timeout(
            settings.voyage_timeout_seconds,
            connect=min(2.0, settings.voyage_timeout_seconds),
        )
        _http_client = httpx.AsyncClient(timeout=timeout)
    return _http_client


async def close_provider_clients() -> None:
    global _http_client, _local_provider
    if _http_client is not None:
        await _http_client.aclose()
        _http_client = None
    _local_provider = None


def build_provider(settings: Settings) -> MultimodalEmbeddingProvider:
    global _local_provider
    if settings.visual_embedding_provider == "fake":
        return FakeEmbeddingProvider(settings.visual_embedding_dims)
    if settings.visual_embedding_provider == "voyage":
        return VoyageEmbeddingProvider(settings, client=_shared_http_client(settings))
    if settings.visual_embedding_provider == "hugging_face_local":
        if _local_provider is None:
            _local_provider = Siglip2EmbeddingProvider(settings)
        return _local_provider
    raise RuntimeError("Unsupported visual embedding provider")
