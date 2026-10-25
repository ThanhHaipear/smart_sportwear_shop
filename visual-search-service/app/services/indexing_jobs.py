import asyncio
from collections import Counter
from dataclasses import dataclass
from datetime import timedelta
import logging
from uuid import UUID

from app.persistence.repository import IndexingJob, ReconciliationCandidate, VisualSearchRepository

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class ReconciliationReport:
    total: int
    reasons: dict[str, int]
    candidates: tuple[ReconciliationCandidate, ...]


class IndexingJobService:
    def __init__(self, repository: VisualSearchRepository):
        self.repository = repository

    async def inspect(
        self,
        include_failed: bool = True,
        processing_timeout_minutes: int = 15,
        retryable_failed_only: bool = False,
    ) -> ReconciliationReport:
        model = await self.repository.active_model()
        if model is None:
            raise RuntimeError("No active visual embedding model is configured")
        candidates = await self.repository.reconciliation_candidates(
            model.id, timedelta(minutes=processing_timeout_minutes), include_failed
        )
        if retryable_failed_only:
            candidates = [item for item in candidates if item.reason == "FAILED_RETRYABLE"]
        return ReconciliationReport(
            total=len(candidates),
            reasons=dict(sorted(Counter(item.reason for item in candidates).items())),
            candidates=tuple(candidates),
        )

    async def enqueue(
        self,
        job_type: str,
        report: ReconciliationReport,
        requested_by: UUID | None = None,
        limit: int | None = None,
        *,
        audit_action: str | None = None,
        audit_target_type: str | None = None,
        audit_target_id: str | None = None,
    ) -> IndexingJob:
        candidates = list(report.candidates[:limit] if limit is not None else report.candidates)
        return await self.repository.create_indexing_job(
            job_type,
            candidates,
            requested_by,
            audit_action=audit_action,
            audit_target_type=audit_target_type,
            audit_target_id=audit_target_id,
        )


class ReconciliationScheduler:
    def __init__(
        self,
        repository: VisualSearchRepository,
        *,
        interval_seconds: int,
        initial_delay_seconds: int,
        processing_timeout_minutes: int,
        batch_size: int,
    ) -> None:
        self.repository = repository
        self.service = IndexingJobService(repository)
        self.interval_seconds = interval_seconds
        self.initial_delay_seconds = initial_delay_seconds
        self.processing_timeout_minutes = processing_timeout_minutes
        self.batch_size = batch_size

    async def run_once(self) -> IndexingJob | None:
        async with self.repository.reconciliation_lock() as acquired:
            if not acquired:
                return None
            if await self.repository.has_recent_reconciliation_job(
                timedelta(seconds=self.interval_seconds)
            ):
                return None
            report = await self.service.inspect(
                include_failed=True,
                processing_timeout_minutes=self.processing_timeout_minutes,
            )
            candidates = tuple(
                item for item in report.candidates if item.reason != "FAILED_PERMANENT"
            )
            if not candidates:
                return None
            filtered = ReconciliationReport(
                total=len(candidates),
                reasons=dict(sorted(Counter(item.reason for item in candidates).items())),
                candidates=candidates,
            )
            job = await self.service.enqueue(
                "RECONCILIATION", filtered, limit=self.batch_size
            )
            logger.info("Scheduled reconciliation job %s with %s images", job.id, job.total_count)
            return job

    async def run(self) -> None:
        if self.initial_delay_seconds:
            await asyncio.sleep(self.initial_delay_seconds)
        while True:
            try:
                await self.run_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Scheduled reconciliation cycle failed")
            await asyncio.sleep(self.interval_seconds)
