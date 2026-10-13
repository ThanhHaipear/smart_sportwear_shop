from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.indexing.consumer import ProductIndexingConsumer
from app.indexing.events import CatalogEvent
from app.indexing.worker import ProductIndexingWorker


def test_catalog_event_rejects_visual_only_event():
    with pytest.raises(ValueError):
        CatalogEvent.model_validate(
            {
                "eventId": str(uuid4()),
                "eventType": "PRODUCT_IMAGE_CREATED",
                "eventVersion": 1,
                "productId": str(uuid4()),
                "occurredAt": "2026-08-05T00:00:00Z",
                "traceId": "trace",
            }
        )


def test_catalog_event_accepts_source_version_and_delete():
    event = CatalogEvent.model_validate(
        {
            "eventId": str(uuid4()),
            "eventType": "PRODUCT_DELETED",
            "eventVersion": 1,
            "productId": str(uuid4()),
            "sourceVersion": 7,
            "occurredAt": "2026-10-13T00:00:00Z",
            "traceId": "trace",
        }
    )
    assert event.sourceVersion == 7


class FakeExchange:
    def __init__(self):
        self.published = []

    async def publish(self, message, routing_key, mandatory):
        self.published.append((message, routing_key, mandatory))


class FakeMessage:
    def __init__(self, headers=None):
        self.body = b"{}"
        self.headers = headers or {}
        self.content_type = "application/json"
        self.correlation_id = "correlation"
        self.acked = False

    async def ack(self):
        self.acked = True


class FakeTransaction:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        self.connection.in_transaction = True

    async def __aexit__(self, exc_type, exc, traceback):
        self.connection.in_transaction = False


class FakeConnection:
    def __init__(self, inserted=True):
        self.inserted = inserted
        self.in_transaction = False

    def transaction(self):
        return FakeTransaction(self)

    async def fetchval(self, *_args):
        assert self.in_transaction
        return self.inserted


class FakeAcquire:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, exc_type, exc, traceback):
        return None


class FakePool:
    def __init__(self, connection):
        self.connection = connection

    def acquire(self):
        return FakeAcquire(self.connection)


@pytest.mark.asyncio
async def test_event_marker_and_materialization_share_transaction(monkeypatch):
    connection = FakeConnection()
    worker = ProductIndexingWorker(FakePool(connection))
    called = False

    async def fake_index(conn, product_id, source_version):
        nonlocal called
        assert conn.in_transaction
        assert source_version == 7
        called = True
        return SimpleNamespace(outcome="UPDATED")

    monkeypatch.setattr("app.indexing.worker.index_product", fake_index)
    event = CatalogEvent.model_validate(
        {
            "eventId": str(uuid4()), "eventType": "PRODUCT_REINDEX_REQUESTED",
            "eventVersion": 1, "productId": str(uuid4()), "sourceVersion": 7,
            "occurredAt": "2026-10-13T00:00:00Z", "traceId": "trace",
        }
    )
    assert await worker.handle(event) == "UPDATED"
    assert called


@pytest.mark.asyncio
async def test_duplicate_event_does_not_materialize(monkeypatch):
    worker = ProductIndexingWorker(FakePool(FakeConnection(inserted=False)))

    async def must_not_run(*_args):
        raise AssertionError("duplicate event must not be materialized")

    monkeypatch.setattr("app.indexing.worker.index_product", must_not_run)
    event = CatalogEvent.model_validate(
        {
            "eventId": str(uuid4()), "eventType": "PRODUCT_REINDEX_REQUESTED",
            "eventVersion": 1, "productId": str(uuid4()), "sourceVersion": 7,
            "occurredAt": "2026-10-13T00:00:00Z", "traceId": "trace",
        }
    )
    assert await worker.handle(event) == "DUPLICATE"


@pytest.mark.asyncio
async def test_retry_then_dlq_routing():
    settings = SimpleNamespace(
        PRODUCT_SEARCH_INDEXING_RETRY_QUEUES="retry.once",
        PRODUCT_SEARCH_INDEXING_DLQ="indexing.dlq",
    )
    consumer = ProductIndexingConsumer(settings, None)
    exchange = FakeExchange()
    consumer.channel = SimpleNamespace(default_exchange=exchange)

    first = FakeMessage()
    await consumer._route(first, permanent=False, error_type="TimeoutError")
    assert exchange.published[0][1] == "retry.once"
    assert first.acked

    exhausted = FakeMessage({"x-product-search-retry-count": 1})
    await consumer._route(exhausted, permanent=False, error_type="TimeoutError")
    assert exchange.published[1][1] == "indexing.dlq"


@pytest.mark.asyncio
async def test_permanent_error_goes_directly_to_dlq():
    settings = SimpleNamespace(
        PRODUCT_SEARCH_INDEXING_RETRY_QUEUES="retry.once",
        PRODUCT_SEARCH_INDEXING_DLQ="indexing.dlq",
    )
    consumer = ProductIndexingConsumer(settings, None)
    exchange = FakeExchange()
    consumer.channel = SimpleNamespace(default_exchange=exchange)
    message = FakeMessage()

    await consumer._route(message, permanent=True, error_type="InvalidEvent")
    assert exchange.published[0][1] == "indexing.dlq"
