import asyncio
import json
import os
from datetime import datetime, timezone
from uuid import uuid4

import aio_pika
import pytest

from app.config import Settings
from app.messaging.consumer import CatalogEventConsumer
from app.messaging.retry import RetryableEventError


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_RABBITMQ_INTEGRATION") != "1",
    reason="set RUN_RABBITMQ_INTEGRATION=1 when the local RabbitMQ broker is available",
)


def test_real_broker_publish_consume_ack_and_retry_route():
    asyncio.run(_scenario())


async def _scenario():
    connection = await aio_pika.connect_robust("amqp://visual_search:change-me@localhost:5672/")
    channel = await connection.channel()
    source = await channel.declare_queue(exclusive=True, auto_delete=True)
    retry = await channel.declare_queue(exclusive=True, auto_delete=True)
    dlq = await channel.declare_queue(exclusive=True, auto_delete=True)
    routed = asyncio.Event()

    async def handler(_event):
        raise RetryableEventError("integration-test")

    consumer = CatalogEventConsumer(
        Settings(rabbitmq_retry_queues=retry.name, rabbitmq_dlq=dlq.name),
        handler,
    )
    consumer.channel = channel

    async def callback(message):
        await consumer.process_message(message)
        routed.set()

    await source.consume(callback, no_ack=False)
    payload = {
        "eventId": str(uuid4()),
        "eventType": "PRODUCT_IMAGE_CREATED",
        "eventVersion": 1,
        "productId": str(uuid4()),
        "sourceVersion": 1,
        "imageId": str(uuid4()),
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "traceId": "rabbit-integration",
    }
    await channel.default_exchange.publish(
        aio_pika.Message(json.dumps(payload).encode(), delivery_mode=aio_pika.DeliveryMode.PERSISTENT),
        routing_key=source.name,
    )
    await asyncio.wait_for(routed.wait(), timeout=5)
    retried = await retry.get(timeout=5)
    assert retried.headers["x-visual-retry-count"] == 1
    await retried.ack()
    await connection.close()
