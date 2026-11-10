import logging

import aio_pika
from aio_pika import DeliveryMode, Message

from app.config import Settings
from app.messaging.events import CatalogEvent
from app.services.indexer import CatalogIndexer

logger = logging.getLogger(__name__)


class CatalogConsumer:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.indexer = CatalogIndexer(settings)
        self.connection = None
        self.channel = None

    async def run(self) -> None:
        self.connection = await aio_pika.connect_robust(self.settings.rabbitmq_url)
        self.channel = await self.connection.channel()
        await self.channel.set_qos(prefetch_count=self.settings.rabbitmq_prefetch_count)
        queue = await self.channel.declare_queue(self.settings.rabbitmq_consumer_queue, passive=True)
        await queue.consume(self.process, no_ack=False)

    async def close(self) -> None:
        if self.connection:
            await self.connection.close()

    async def process(self, message: aio_pika.abc.AbstractIncomingMessage) -> None:
        try:
            event = CatalogEvent.model_validate_json(message.body)
            await self.indexer.index(event.product_id, event.source_version, event.event_id)
            await message.ack()
        except Exception as error:
            headers = dict(message.headers or {})
            attempt = int(headers.get("x-search-retry-count", 0))
            if attempt < len(self.settings.retry_queues):
                target = self.settings.retry_queues[attempt]
                headers["x-search-retry-count"] = attempt + 1
            else:
                target = self.settings.rabbitmq_dlq
                headers["x-search-error-type"] = type(error).__name__
            await self.channel.default_exchange.publish(Message(
                body=message.body, headers=headers, delivery_mode=DeliveryMode.PERSISTENT,
                content_type="application/json", correlation_id=message.correlation_id,
            ), routing_key=target, mandatory=True)
            logger.warning("Catalog event routed to %s: %s", target, type(error).__name__)
            await message.ack()
