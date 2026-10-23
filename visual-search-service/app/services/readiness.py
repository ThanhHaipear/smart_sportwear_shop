import asyncio
from dataclasses import dataclass

from app.config import Settings
from app.persistence import VisualSearchRepository


@dataclass(frozen=True, slots=True)
class ReadinessResult:
    ready: bool
    database: str
    active_model: str
    rabbitmq: str


class ReadinessChecker:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.repository = VisualSearchRepository(settings)

    async def check(self) -> ReadinessResult:
        database, model = await self._check_database_and_model()
        return ReadinessResult(
            # The synchronous query API does not use RabbitMQ. Queue health is
            # exposed by the admin operations endpoint and must not take image
            # search out of service when database/model retrieval still works.
            ready=database == "up" and model == "active",
            database=database,
            active_model=model,
            rabbitmq="not_required",
        )

    async def _check_database_and_model(self) -> tuple[str, str]:
        try:
            model = await asyncio.wait_for(
                self.repository.active_model(), timeout=self.settings.readiness_timeout_seconds
            )
        except Exception:
            return "down", "unknown"
        if model is None:
            return "up", "missing"
        matches = (
            model.provider.lower() == self.settings.visual_embedding_provider
            and model.model == self.settings.visual_embedding_model
            and model.dimensions == self.settings.visual_embedding_dims
        )
        if self.settings.visual_embedding_provider == "hugging_face_local":
            matches = (
                matches
                and model.model_version == self.settings.visual_embedding_revision
                and model.preprocessing_version == self.settings.visual_embedding_preprocessing
            )
        return "up", "active" if matches else "mismatch"
