import asyncio

from app.config import get_settings
from app.messaging.consumer import CatalogConsumer
from app.providers.factory import build_provider, close_provider_clients


async def main() -> None:
    settings = get_settings()
    if not settings.search_enabled:
        raise RuntimeError("SEARCH_ENABLED must be true")
    consumer = CatalogConsumer(settings)
    try:
        if settings.visual_search_enabled and settings.visual_embedding_provider == "hugging_face_local":
            await build_provider(settings).prepare()
        await consumer.run()
        await asyncio.Future()
    finally:
        await consumer.close()
        await close_provider_clients()


if __name__ == "__main__":
    asyncio.run(main())
