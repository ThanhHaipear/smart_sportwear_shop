import asyncio
import json
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest
from aiormq.abc import DeliveredMessage
from pamqp.commands import Basic

from app import worker
from app import messaging
from app.config import Settings
from app.messaging import RecommendationConsumer
from app.repository import MemoryRepository
from tests.test_contracts import browser_event, envelope


class FlakyConsumer:
    def __init__(self, failures):
        self.failures = failures
        self.calls = 0

    async def run(self):
        self.calls += 1
        if self.calls <= self.failures:
            raise ConnectionError("broker not ready")


def test_worker_retries_initial_rabbitmq_connection(monkeypatch):
    monkeypatch.setattr(worker, "STARTUP_RETRY_DELAYS_SECONDS", (0, 0))
    consumer = FlakyConsumer(failures=2)
    asyncio.run(worker.connect_with_retry(consumer))
    assert consumer.calls == 3


def test_worker_gives_up_after_last_retry(monkeypatch):
    monkeypatch.setattr(worker, "STARTUP_RETRY_DELAYS_SECONDS", (0,))
    with pytest.raises(ConnectionError):
        asyncio.run(worker.connect_with_retry(FlakyConsumer(failures=5)))


@pytest.mark.parametrize("source, demo_enabled, stored", [("DEMO", False, 0), ("REAL", True, 0), ("DEMO", True, 1)])
def test_worker_honors_interaction_flags(source, demo_enabled, stored):
    settings = Settings(catalog_service_token="test-token", interaction_real_enabled=False,
                        interaction_demo_enabled=demo_enabled)
    consumer = RecommendationConsumer(settings)
    consumer.repository = MemoryRepository()
    event = browser_event("INTERACTION_RECORDED", dataSource=source, userRef=str(uuid4()))
    message = Mock(body=json.dumps(event).encode(), ack=AsyncMock())

    asyncio.run(consumer.process(message))

    message.ack.assert_awaited_once()
    assert len(consumer.repository.interactions) == stored
    if stored:
        assert consumer.repository.interactions[0].user_ref is None


@pytest.mark.parametrize("dead_letter", [False, True], ids=["retry", "dlq"])
@pytest.mark.parametrize("confirmed", [False, True], ids=["no-route", "ack"])
def test_worker_settles_original_only_after_confirmed_forward(monkeypatch, dead_letter, confirmed):
    monkeypatch.setattr(messaging, "REQUEUE_DELAY_SECONDS", 0)
    settings = Settings(catalog_service_token="test-token")
    consumer = RecommendationConsumer(settings)
    consumer.enricher = Mock()
    consumer.enricher.enrich.side_effect = RuntimeError("Catalog is unavailable")
    target = settings.rabbitmq_dlq if dead_letter else settings.retry_queues[0]
    result = Basic.Ack(delivery_tag=1) if confirmed else DeliveredMessage(
        Basic.Return(reply_code=312, reply_text="NO_ROUTE", exchange="", routing_key=target),
        Mock(), b"", Mock())
    publish = AsyncMock(return_value=result)
    consumer.channel = Mock(default_exchange=Mock(publish=publish))
    message = Mock(body=json.dumps(envelope("ORDER_CREATED")).encode(), correlation_id="integration-test",
                   headers={"x-recommendation-retry-count": len(settings.retry_queues)} if dead_letter else {},
                   ack=AsyncMock(), nack=AsyncMock())

    asyncio.run(consumer.process(message))

    assert publish.await_args.kwargs["routing_key"] == target
    assert publish.await_args.kwargs["mandatory"] is True
    if confirmed:
        message.ack.assert_awaited_once()
        message.nack.assert_not_awaited()
    else:
        message.ack.assert_not_awaited()
        message.nack.assert_awaited_once_with(requeue=True)
