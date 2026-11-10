import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts" / "catalog-event-v1.schema.json").read_text())


def test_contract_is_closed_and_versioned() -> None:
    assert SCHEMA["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert SCHEMA["additionalProperties"] is False
    assert SCHEMA["properties"]["eventVersion"] == {"const": 1}


def test_contract_contains_only_approved_message_metadata() -> None:
    assert set(SCHEMA["properties"]) == {
        "eventId",
        "eventType",
        "eventVersion",
        "productId",
        "sourceVersion",
        "imageId",
        "occurredAt",
        "traceId",
    }
    assert set(SCHEMA["required"]) == {
        "eventId",
        "eventType",
        "eventVersion",
        "productId",
        "sourceVersion",
        "occurredAt",
        "traceId",
    }


def test_contract_has_exact_supported_event_types() -> None:
    assert set(SCHEMA["properties"]["eventType"]["enum"]) == {
        "PRODUCT_IMAGE_CREATED",
        "PRODUCT_IMAGE_DELETED",
        "PRODUCT_ACTIVATED",
        "PRODUCT_DEACTIVATED",
        "PRODUCT_DELETED",
        "PRODUCT_REINDEX_REQUESTED",
    }


def test_image_events_require_image_id() -> None:
    conditional = SCHEMA["allOf"][0]

    assert set(conditional["if"]["properties"]["eventType"]["enum"]) == {
        "PRODUCT_IMAGE_CREATED",
        "PRODUCT_IMAGE_DELETED",
    }
    assert conditional["then"]["required"] == ["imageId"]
    assert conditional["then"]["properties"]["imageId"]["type"] == "string"
