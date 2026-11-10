import asyncio

from app.config import get_settings
from app.messaging import RecommendationConsumer


async def main():
    settings = get_settings()
    if not settings.recommendation_enabled:
        raise RuntimeError("RECOMMENDATION_ENABLED must be true for the worker")
    consumer = RecommendationConsumer(settings)
    await consumer.run()
    await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
