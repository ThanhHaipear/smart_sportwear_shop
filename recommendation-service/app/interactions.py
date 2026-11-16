"""Rules for behaviour events sent by browsers.

The interaction endpoint is public, so every field a browser controls is either validated
against an allow-list or replaced by a server-side value before it reaches `user_interactions`.
"""
from __future__ import annotations

import json
import re
from datetime import datetime

from fastapi import HTTPException, status

from app.schemas import InteractionEvent

# PURCHASE is derived from Sales ORDER_CREATED events, never accepted from a browser.
BROWSER_EVENT_TYPES = frozenset({
    "VIEW", "ADD_TO_CART", "WISHLIST", "SEARCH_IMPRESSION", "SEARCH_CLICK",
    "RECOMMENDATION_IMPRESSION", "RECOMMENDATION_CLICK",
})
# SYNTHETIC data is generated offline for evaluation and must not arrive through the website.
BROWSER_DATA_SOURCES = frozenset({"REAL", "DEMO"})
# Events that only make sense for a concrete product.
PRODUCT_EVENT_TYPES = frozenset({"VIEW", "ADD_TO_CART", "WISHLIST", "SEARCH_CLICK",
                                 "RECOMMENDATION_IMPRESSION", "RECOMMENDATION_CLICK"})
# Recommendation feedback must point at the request that produced the list (CTR by model).
REQUEST_EVENT_TYPES = frozenset({"RECOMMENDATION_IMPRESSION", "RECOMMENDATION_CLICK"})
MAX_METADATA_BYTES = 2048
UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
ANONYMOUS_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{8,100}$")


def _reject(message: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=message)


def sanitize_browser_interaction(event: InteractionEvent, verified_user_id: str | None,
                                 received_at: datetime) -> InteractionEvent:
    if event.event_type not in BROWSER_EVENT_TYPES:
        raise _reject(f"eventType {event.event_type} is not accepted from browsers")
    if event.data_source not in BROWSER_DATA_SOURCES:
        raise _reject("dataSource must be REAL or DEMO")
    if event.event_type in PRODUCT_EVENT_TYPES and event.product_id is None:
        raise _reject(f"productId is required for {event.event_type}")
    if event.event_type in REQUEST_EVENT_TYPES and event.request_id is None:
        raise _reject(f"requestId is required for {event.event_type}")
    if event.anonymous_id is not None and not ANONYMOUS_ID_PATTERN.fullmatch(event.anonymous_id):
        raise _reject("anonymousId has an invalid format")
    if len(json.dumps(event.metadata, ensure_ascii=False).encode("utf-8")) > MAX_METADATA_BYTES:
        raise _reject(f"metadata must not exceed {MAX_METADATA_BYTES} bytes")

    user_ref = verified_user_id if verified_user_id and UUID_PATTERN.fullmatch(verified_user_id) else None
    if user_ref is None and not event.anonymous_id:
        raise _reject("anonymousId is required when the request is not authenticated")
    # Never trust a browser-supplied userRef; use server time so temporal splits are not
    # affected by client clocks.
    return event.model_copy(update={"user_ref": user_ref, "occurred_at": received_at})
