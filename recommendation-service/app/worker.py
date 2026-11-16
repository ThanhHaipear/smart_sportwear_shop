import asyncio
import logging

from app.config import get_settings
from app.messaging import RecommendationConsumer

logger = logging.getLogger(__name__)

# RabbitMQ may still be starting when the launcher brings the worker up. connect_robust only
# reconnects after a first successful connection, so the initial connect is retried here.
STARTUP_RETRY_DELAYS_SECONDS = (2, 5, 10, 20, 30)


async def connect_with_retry(consumer: RecommendationConsumer) -> None:
    for attempt, delay in enumerate((*STARTUP_RETRY_DELAYS_SECONDS, None), start=1):
        try:
            await consumer.run()
            return
        except Exception as error:  # aio-pika raises several connection error types
            if delay is None:
                raise
            logger.warning("RabbitMQ not ready (attempt %s: %s); retrying in %ss", attempt, type(error).__name__, delay)
            await asyncio.sleep(delay)


async def main():
    logging.basicConfig(level=logging.INFO)
    settings = get_settings()
    if not settings.recommendation_enabled:
        raise RuntimeError("RECOMMENDATION_ENABLED must be true for the worker")
    consumer = RecommendationConsumer(settings)
    await connect_with_retry(consumer)
    await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
