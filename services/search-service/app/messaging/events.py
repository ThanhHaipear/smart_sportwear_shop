from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class CatalogEvent(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)
    event_id: UUID = Field(alias="eventId")
    event_type: str = Field(alias="eventType")
    event_version: int = Field(alias="eventVersion")
    product_id: UUID = Field(alias="productId")
    source_version: int = Field(default=0, alias="sourceVersion", ge=0)
    occurred_at: datetime = Field(alias="occurredAt")
    trace_id: str = Field(alias="traceId")
