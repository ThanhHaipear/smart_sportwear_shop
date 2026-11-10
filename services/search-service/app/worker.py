import asyncio

from app.config import get_settings
from app.messaging.consumer import CatalogConsumer


async def main() -> None:
    settings = get_settings()
    if not settings.search_enabled:
        raise RuntimeError("SEARCH_ENABLED must be true")
    consumer = CatalogConsumer(settings)
    try:
        await consumer.run()
        await asyncio.Future()
    finally:
        await consumer.close()


if __name__ == "__main__":
    asyncio.run(main())
