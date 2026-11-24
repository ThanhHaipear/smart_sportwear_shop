import asyncio
import json
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest

from app import worker
from app.config import Settings
from app.messaging import RecommendationConsumer
from app.repository import MemoryRepository
from tests.test_contracts import browser_event


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
