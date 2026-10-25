import asyncio
from dataclasses import dataclass

from app.config import Settings
from app.persistence import VisualSearchRepository


@dataclass(frozen=True, slots=True)
class ReadinessResult:
    ready: bool
    database: str
    active_model: str
    coverage: str
    rabbitmq: str


class ReadinessChecker:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.repository = VisualSearchRepository(settings)

    async def check(self) -> ReadinessResult:
        database, model, coverage = await self._check_database_model_and_coverage()
        return ReadinessResult(
            # The synchronous query API does not use RabbitMQ. Queue health is
            # exposed by the admin operations endpoint and must not take image
            # search out of service when database/model retrieval still works.
            ready=database == "up" and model == "active" and coverage == "complete",
            database=database,
            active_model=model,
            coverage=coverage,
            rabbitmq="not_required",
        )

    async def _check_database_model_and_coverage(self) -> tuple[str, str, str]:
        try:
            model = await asyncio.wait_for(
                self.repository.active_model(), timeout=self.settings.readiness_timeout_seconds
            )
        except Exception:
            return "down", "unknown", "unknown"
        if model is None:
            return "up", "missing", "unknown"
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
        if not matches:
            return "up", "mismatch", "unknown"
        try:
            stats = await asyncio.wait_for(
                self.repository.coverage_stats(), timeout=self.settings.readiness_timeout_seconds
            )
        except Exception:
            return "up", "active", "unavailable"
        complete = (
            stats.total_active_images > 0
            and stats.ready == stats.total_active_images
            and stats.pending == 0
            and stats.processing == 0
            and stats.failed == 0
            and stats.missing == 0
        )
        return "up", "active", "complete" if complete else "incomplete"
