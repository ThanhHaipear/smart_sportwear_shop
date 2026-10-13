from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class CatalogEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    eventId: UUID
    eventType: Literal[
        "PRODUCT_ACTIVATED",
        "PRODUCT_DEACTIVATED",
        "PRODUCT_DELETED",
        "PRODUCT_REINDEX_REQUESTED",
    ]
    eventVersion: Literal[1]
    productId: UUID
    # Queued legacy events did not contain a source version. Version zero is
    # accepted only so they can safely materialize the current DB snapshot.
    sourceVersion: int = Field(default=0, ge=0)
    occurredAt: datetime
    traceId: str
