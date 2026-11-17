from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ProductSnapshot(BaseModel):
    id: UUID
    name: str
    slug: str
    thumbnailUrl: str | None = None
    # Storefront ProductListItem reads `thumbnail`; keep both names for compatibility.
    thumbnail: str | None = None
    productType: str | None = None
    minPrice: float | None = None
    maxPrice: float | None = None
    availableQuantity: int = 0
    status: str = "ACTIVE"
    categoryId: UUID | None = None
    brandId: UUID | None = None
    # Content-based features (docs/recommendation-design.md 5.4). Catalog currently returns them empty.
    gender: str | None = None
    sportCodes: list[str] = Field(default_factory=list)
    colorFamilies: list[str] = Field(default_factory=list)


class RecommendationItem(BaseModel):
    product: ProductSnapshot
    score: float
    algorithm: str
    reason: str
    support: float = 0
    confidence: float = 0
    lift: float = 0
    pairCount: int = 0


class RecommendationResponse(BaseModel):
    requestId: UUID = Field(default_factory=uuid4)
    sourceProductId: UUID | None = None
    sourceProductIds: list[UUID] = Field(default_factory=list)
    type: str
    modelVersion: str
    items: list[RecommendationItem]


class CartRecommendationRequest(BaseModel):
    sourceProductIds: list[UUID] = Field(min_length=1, max_length=100)
    limit: int | None = Field(default=None, ge=1, le=100)


class InteractionEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    event_id: UUID = Field(alias="eventId")
    event_type: str = Field(alias="eventType")
    event_version: int = Field(alias="eventVersion")
    occurred_at: datetime = Field(alias="occurredAt")
    trace_id: str = Field(alias="traceId", min_length=1, max_length=128)
    user_ref: str | None = Field(default=None, alias="userRef", max_length=100)
    anonymous_id: str | None = Field(default=None, alias="anonymousId", max_length=100)
    product_id: UUID | None = Field(default=None, alias="productId")
    variant_id: UUID | None = Field(default=None, alias="variantId")
    request_id: UUID | None = Field(default=None, alias="requestId")
    source: str = Field(min_length=1, max_length=50)
    data_source: str = Field(alias="dataSource", pattern="^(REAL|DEMO|SYNTHETIC)$")
    metadata: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_identity(self) -> "InteractionEvent":
        if not self.user_ref and not self.anonymous_id:
            raise ValueError("userRef or anonymousId is required")
        return self


class InteractionBatch(BaseModel):
    """Several browser events sent in one request (e.g. all impressions of one recommendation list)."""
    model_config = ConfigDict(extra="forbid")
    # The upper bound is enforced in app.interactions so the API returns a clear 422 message.
    events: list[InteractionEvent] = Field(min_length=1, max_length=50)


class IntegrationEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="allow")
    event_id: UUID = Field(alias="eventId")
    event_type: str = Field(alias="eventType")
    event_version: int = Field(alias="eventVersion")
    occurred_at: datetime = Field(alias="occurredAt")
    trace_id: str = Field(alias="traceId", min_length=1, max_length=128)
    product_id: UUID | None = Field(default=None, alias="productId")
    source_version: int = Field(default=0, alias="sourceVersion", ge=0)
    product: ProductSnapshot | None = None
    order_id: UUID | None = Field(default=None, alias="orderId")
    user_ref: str | None = Field(default=None, alias="userRef")
    items: list[UUID] = Field(default_factory=list)


class EvaluationRequest(BaseModel):
    modelVersion: str
    datasetVersion: str
    k: int = Field(default=5, ge=1, le=100)
    cases: list[dict[str, list[UUID]]] = Field(min_length=1)


class EvaluationResult(BaseModel):
    modelVersion: str
    datasetVersion: str
    k: int
    precisionAtK: float
    recallAtK: float
    # Added in migration V3 (phase 9); None for results computed before.
    hitRateAtK: float | None = None
    ndcgAtK: float | None = None
    coverageAtK: float | None = None
    task: str | None = None
    segment: str | None = None
    caseCount: int | None = None
    reportSha256: str | None = None
    evaluatedAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
