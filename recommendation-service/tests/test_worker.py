import asyncio

import pytest

from app import worker


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
