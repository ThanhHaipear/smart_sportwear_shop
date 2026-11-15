import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock, Mock
from uuid import UUID

from app.config import Settings
from app.messaging.consumer import CatalogConsumer
from app.messaging.events import CatalogEvent


ROOT = Path(__file__).resolve().parents[3]
FIXTURE = (ROOT / "contracts/examples/catalog-product-reindex-event-v1.json").read_bytes()


def message(headers=None):
    value = Mock()
    value.body = FIXTURE
    value.headers = headers or {}
    value.correlation_id = "hai-11-event"
    value.ack = AsyncMock()
    return value


def consumer() -> CatalogConsumer:
    value = CatalogConsumer(Settings(
        search_enabled=False,
        rabbitmq_retry_queues="search.indexing.retry.30s,search.indexing.retry.5m",
        rabbitmq_dlq="search.indexing.dlq",
    ))
    value.indexer.index = AsyncMock(return_value="UPDATED")
    value.channel = Mock()
    value.channel.default_exchange.publish = AsyncMock()
    return value


def test_shared_catalog_event_fixture_matches_consumer_contract():
    event = CatalogEvent.model_validate_json(FIXTURE)

    assert event.event_type == "PRODUCT_REINDEX_REQUESTED"
    assert event.event_version == 1
    assert event.product_id == UUID("10000000-0000-0000-0000-000000000001")
    assert event.source_version == 2


def test_catalog_producer_and_rabbit_binding_match_search_contract():
    migration = (
        ROOT
        / "catalog-service/src/main/resources/db/migration/V5__align_catalog_search_events.sql"
    ).read_text(encoding="utf-8")
    for field in (
        "eventId", "eventType", "eventVersion", "productId",
        "sourceVersion", "occurredAt", "traceId",
    ):
        assert f"'{field}'" in migration
    assert "'product.reindex.requested'" in migration

    definitions = json.loads((ROOT / "rabbitmq/definitions.json").read_text(encoding="utf-8"))
    assert any(
        binding["source"] == "catalog.events"
        and binding["destination"] == "search.indexing"
        and binding["routing_key"] == "product.#"
        for binding in definitions["bindings"]
    )


def test_valid_event_is_indexed_then_acknowledged():
    target = consumer()
    incoming = message()

    asyncio.run(target.process(incoming))

    target.indexer.index.assert_awaited_once_with(
        UUID("10000000-0000-0000-0000-000000000001"),
        2,
        UUID("90000000-0000-0000-0000-000000000001"),
    )
    incoming.ack.assert_awaited_once()
    target.channel.default_exchange.publish.assert_not_awaited()


def test_failed_event_is_routed_to_next_retry_queue_then_acknowledged():
    target = consumer()
    target.indexer.index.side_effect = RuntimeError("controlled failure")
    incoming = message()

    asyncio.run(target.process(incoming))

    publish = target.channel.default_exchange.publish
    publish.assert_awaited_once()
    assert publish.await_args.kwargs["routing_key"] == "search.indexing.retry.30s"
    assert publish.await_args.args[0].headers["x-search-retry-count"] == 1
    incoming.ack.assert_awaited_once()


def test_exhausted_event_is_routed_to_dlq_then_acknowledged():
    target = consumer()
    target.indexer.index.side_effect = RuntimeError("controlled failure")
    incoming = message({"x-search-retry-count": 2})

    asyncio.run(target.process(incoming))

    publish = target.channel.default_exchange.publish
    publish.assert_awaited_once()
    assert publish.await_args.kwargs["routing_key"] == "search.indexing.dlq"
    assert publish.await_args.args[0].headers["x-search-error-type"] == "RuntimeError"
    incoming.ack.assert_awaited_once()
