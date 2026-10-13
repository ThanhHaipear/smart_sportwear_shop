import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFINITIONS = json.loads((ROOT / "rabbitmq" / "definitions.json").read_text())


def test_local_visual_search_user_has_vhost_permissions():
    assert any(item["name"] == "visual_search" for item in DEFINITIONS["users"])
    assert any(
        item["user"] == "visual_search" and item["vhost"] == "/"
        for item in DEFINITIONS["permissions"]
    )


def test_catalog_exchange_is_durable_topic() -> None:
    exchange = next(item for item in DEFINITIONS["exchanges"] if item["name"] == "catalog.events")

    assert exchange["type"] == "topic"
    assert exchange["durable"] is True
    assert exchange["auto_delete"] is False


def test_main_queue_has_exact_catalog_bindings() -> None:
    keys = {
        item["routing_key"]
        for item in DEFINITIONS["bindings"]
        if item["source"] == "catalog.events"
        and item["destination"] == "visual-search.indexing"
    }

    assert keys == {
        "product.image.created",
        "product.image.deleted",
        "product.activated",
        "product.deactivated",
        "product.deleted",
        "product.reindex.requested",
    }


def test_retry_queues_have_expected_ttl_and_return_to_main_queue() -> None:
    queues = {item["name"]: item for item in DEFINITIONS["queues"]}
    expected = {
        "visual-search.indexing.retry.30s": 30_000,
        "visual-search.indexing.retry.5m": 300_000,
        "visual-search.indexing.retry.1h": 3_600_000,
    }

    for name, ttl in expected.items():
        arguments = queues[name]["arguments"]
        assert queues[name]["durable"] is True
        assert arguments["x-message-ttl"] == ttl
        assert arguments["x-dead-letter-exchange"] == ""
        assert arguments["x-dead-letter-routing-key"] == "visual-search.indexing"


def test_main_queue_rejections_are_dead_lettered() -> None:
    queue = next(item for item in DEFINITIONS["queues"] if item["name"] == "visual-search.indexing")

    assert queue["arguments"] == {
        "x-dead-letter-exchange": "",
        "x-dead-letter-routing-key": "visual-search.indexing.dlq",
    }
