from datetime import datetime
from enum import Enum
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class CatalogEventType(str, Enum):
    PRODUCT_IMAGE_CREATED = "PRODUCT_IMAGE_CREATED"
    PRODUCT_IMAGE_DELETED = "PRODUCT_IMAGE_DELETED"
    PRODUCT_ACTIVATED = "PRODUCT_ACTIVATED"
    PRODUCT_DEACTIVATED = "PRODUCT_DEACTIVATED"
    PRODUCT_DELETED = "PRODUCT_DELETED"
    PRODUCT_REINDEX_REQUESTED = "PRODUCT_REINDEX_REQUESTED"


class CatalogEvent(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    event_id: UUID = Field(alias="eventId")
    event_type: CatalogEventType = Field(alias="eventType")
    event_version: int = Field(alias="eventVersion")
    product_id: UUID = Field(alias="productId")
    source_version: int = Field(default=0, alias="sourceVersion", ge=0)
    image_id: UUID | None = Field(default=None, alias="imageId")
    occurred_at: datetime = Field(alias="occurredAt")
    trace_id: str = Field(alias="traceId", min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_contract(self) -> "CatalogEvent":
        if self.event_version != 1:
            raise ValueError("Unsupported catalog event version")
        if self.event_type in {
            CatalogEventType.PRODUCT_IMAGE_CREATED,
            CatalogEventType.PRODUCT_IMAGE_DELETED,
        } and self.image_id is None:
            raise ValueError("imageId is required for image events")
        return self
