"""Rules for behaviour events sent by browsers.

The interaction endpoint is public, so every field a browser controls is either validated
against an allow-list or replaced by a server-side value before it reaches `user_interactions`.
"""
from __future__ import annotations

import json
import re
import threading
import time
from collections import defaultdict, deque
from datetime import datetime

from typing import Annotated, Literal

from fastapi import HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, ValidationError, model_validator

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


# --- Metadata allow-list (review R4, data minimisation per O01) -------------------------------------------
# Browsers may only send the measurement fields below, per event type, with strict types: no free text, no
# identifiers other than product UUIDs, no PII. Anything else rejects the event (and so the whole batch).
_ProductId = Annotated[StrictStr, Field(pattern=UUID_PATTERN.pattern)]
_Position = Annotated[StrictInt, Field(ge=0, le=99)]


class _NoMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid")


class _PositionMetadata(_NoMetadata):
    position: _Position | None = None


class _RecommendationFeedbackMetadata(_PositionMetadata):
    algorithm: Literal["ASSOCIATION_RULE", "SIMILAR_CONTENT", "POPULARITY"] | None = None
    modelVersion: Annotated[StrictStr, Field(min_length=1, max_length=50)] | None = None
    # Detail page: the one product being viewed; cart: every product in the cart.
    sourceProductId: _ProductId | None = None
    sourceProductIds: Annotated[list[_ProductId], Field(min_length=1, max_length=50)] | None = None

    @model_validator(mode="after")
    def one_source_shape(self) -> "_RecommendationFeedbackMetadata":
        if self.sourceProductId is not None and self.sourceProductIds is not None:
            raise ValueError("send either sourceProductId or sourceProductIds, not both")
        return self


class _AddToCartMetadata(_NoMetadata):
    quantity: Annotated[StrictInt, Field(ge=1, le=99)] | None = None


METADATA_SCHEMAS: dict[str, type[BaseModel]] = {
    "VIEW": _NoMetadata,
    "WISHLIST": _NoMetadata,
    "ADD_TO_CART": _AddToCartMetadata,
    "SEARCH_IMPRESSION": _PositionMetadata,
    "SEARCH_CLICK": _PositionMetadata,
    "RECOMMENDATION_IMPRESSION": _RecommendationFeedbackMetadata,
    "RECOMMENDATION_CLICK": _RecommendationFeedbackMetadata,
}
assert set(METADATA_SCHEMAS) == BROWSER_EVENT_TYPES


def allowed_metadata_fields(event_type: str) -> frozenset[str]:
    return frozenset(METADATA_SCHEMAS[event_type].model_fields)


def sanitize_metadata(event_type: str, metadata: dict) -> dict:
    """Validate browser metadata against the allow-list of its event type; return it without null values."""
    try:
        parsed = METADATA_SCHEMAS[event_type].model_validate(metadata)
    except ValidationError as error:
        problems = "; ".join(f"{'.'.join(str(p) for p in e['loc']) or 'metadata'}: {e['msg']}"
                             for e in error.errors(include_url=False))
        raise _reject(f"invalid metadata for {event_type}: {problems}") from None
    return parsed.model_dump(exclude_none=True)


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
    metadata = sanitize_metadata(event.event_type, event.metadata)

    user_ref = verified_user_id if verified_user_id and UUID_PATTERN.fullmatch(verified_user_id) else None
    if user_ref is None and not event.anonymous_id:
        raise _reject("anonymousId is required when the request is not authenticated")
    # Never trust a browser-supplied userRef; use server time so temporal splits are not
    # affected by client clocks.
    return event.model_copy(update={"user_ref": user_ref, "occurred_at": received_at, "metadata": metadata})


MAX_BATCH_EVENTS = 10


def sanitize_browser_batch(events: list[InteractionEvent], verified_user_id: str | None,
                           received_at: datetime) -> list[InteractionEvent]:
    """Validate a batch all-or-nothing: one invalid event rejects the whole batch.

    A batch must come from a single browser (same anonymousId) so it is charged to one identity
    in the rate limiter and cannot mix behaviour of several visitors.
    """
    if not 1 <= len(events) <= MAX_BATCH_EVENTS:
        raise _reject(f"a batch must contain 1 to {MAX_BATCH_EVENTS} events")
    if len({event.event_id for event in events}) != len(events):
        raise _reject("eventId must be unique within a batch")
    if len({event.anonymous_id for event in events}) != 1:
        raise _reject("all events in a batch must share the same anonymousId")
    return [sanitize_browser_interaction(event, verified_user_id, received_at) for event in events]


class SlidingWindowLimiter:
    """Allow at most `limit` hits per key in the last `window` seconds.

    State is in memory per API process. That is enough for the single local instance; a
    multi-instance deployment would need a shared store (e.g. Redis).
    """

    def __init__(self, limit: int, window_seconds: float = 60.0, clock=time.monotonic):
        self.limit = limit
        self.window = window_seconds
        self.clock = clock
        self.hits: dict[str, deque[float]] = defaultdict(deque)
        self.lock = threading.Lock()

    # The two methods below assume the caller holds `self.lock`.
    def _has_room(self, key: str, cost: int, now: float) -> bool:
        bucket = self.hits[key]
        while bucket and bucket[0] <= now - self.window:
            bucket.popleft()
        return len(bucket) + cost <= self.limit

    def _consume(self, key: str, cost: int, now: float) -> None:
        self.hits[key].extend([now] * cost)
        if len(self.hits) > 50_000:  # drop idle keys so memory stays bounded
            for idle in [k for k, v in self.hits.items() if not v or v[-1] <= now - self.window]:
                del self.hits[idle]

    def allow(self, key: str, cost: int = 1) -> bool:
        """Consume `cost` hits if they fit in the window; otherwise consume nothing."""
        now = self.clock()
        with self.lock:
            if not self._has_room(key, cost, now):
                return False
            self._consume(key, cost, now)
            return True


class InteractionRateLimiter:
    """Separate limits per behaviour identity and per client IP (set by the Gateway).

    Limits count EVENTS, not HTTP requests: a batch of 4 impressions costs 4. Counting
    requests would let one client send `limit x batch size` events per minute.
    """

    def __init__(self, per_identity: int, per_ip: int, clock=time.monotonic):
        self.identity = SlidingWindowLimiter(per_identity, clock=clock)
        self.ip = SlidingWindowLimiter(per_ip, clock=clock)
        self.clock = clock
        self.lock = threading.Lock()

    def check(self, events: InteractionEvent | list[InteractionEvent], client_ip: str | None) -> None:
        batch = events if isinstance(events, list) else [events]
        first = batch[0]  # a batch is validated to belong to one browser/user
        identity = f"user:{first.user_ref}" if first.user_ref else f"anon:{first.anonymous_id}"
        ip_key, cost, now = f"ip:{client_ip or 'unknown'}", len(batch), self.clock()
        # Check both limits before consuming either, so a rejection never charges the other one.
        with self.lock, self.ip.lock, self.identity.lock:
            if not (self.ip._has_room(ip_key, cost, now) and self.identity._has_room(identity, cost, now)):
                raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                                    detail="Too many interaction events", headers={"Retry-After": "60"})
            self.ip._consume(ip_key, cost, now)
            self.identity._consume(identity, cost, now)
