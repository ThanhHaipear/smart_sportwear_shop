import asyncio
from dataclasses import dataclass

import aio_pika

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
        rabbitmq = await self._check_rabbitmq()
        return ReadinessResult(
            ready=database == "up" and model == "active" and rabbitmq == "up",
            database=database,
            active_model=model,
            rabbitmq=rabbitmq,
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

    async def _check_rabbitmq(self) -> str:
        connection = None
        try:
            connection = await aio_pika.connect(
                self.settings.rabbitmq_url,
                timeout=self.settings.readiness_timeout_seconds,
            )
            channel = await connection.channel()
            await channel.declare_queue(self.settings.rabbitmq_consumer_queue, passive=True)
            return "up"
        except Exception:
            return "down"
        finally:
            if connection is not None:
                await connection.close()
