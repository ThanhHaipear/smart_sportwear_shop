import logging

import aio_pika
from aio_pika import DeliveryMode, Message

from app.config import Settings
from app.repository import PostgresRepository
from app.schemas import IntegrationEvent, InteractionEvent

logger = logging.getLogger(__name__)


class RecommendationConsumer:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.repository = PostgresRepository(settings.recommendation_database_url)
        self.connection = None
        self.channel = None

    async def run(self):
        self.connection = await aio_pika.connect_robust(self.settings.rabbitmq_url)
        self.channel = await self.connection.channel()
        await self.channel.set_qos(prefetch_count=self.settings.rabbitmq_prefetch_count)
        queue = await self.channel.declare_queue(self.settings.rabbitmq_consumer_queue, passive=True)
        await queue.consume(self.process, no_ack=False)

    async def process(self, message: aio_pika.abc.AbstractIncomingMessage):
        try:
            raw = message.body.decode("utf-8")
            event = IntegrationEvent.model_validate_json(raw)
            if event.event_type == "INTERACTION_RECORDED":
                self.repository.save_interaction(InteractionEvent.model_validate_json(raw))
            else:
                self.repository.apply_event(event)
            await message.ack()
        except Exception as error:
            headers = dict(message.headers or {})
            attempt = int(headers.get("x-recommendation-retry-count", 0))
            if attempt < len(self.settings.retry_queues):
                target = self.settings.retry_queues[attempt]
                headers["x-recommendation-retry-count"] = attempt + 1
            else:
                target = self.settings.rabbitmq_dlq
                headers["x-recommendation-error-type"] = type(error).__name__
            await self.channel.default_exchange.publish(Message(body=message.body, headers=headers,
                delivery_mode=DeliveryMode.PERSISTENT, content_type="application/json",
                correlation_id=message.correlation_id), routing_key=target, mandatory=True)
            logger.warning("Recommendation event routed to %s: %s", target, type(error).__name__)
            await message.ack()
