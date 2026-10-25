import asyncio
from contextlib import asynccontextmanager
from datetime import timedelta
from uuid import uuid4

from app.persistence.repository import IndexingJob, ModelVersion, ReconciliationCandidate
from app.services.indexing_jobs import IndexingJobService, ReconciliationScheduler


class FakeRepository:
    def __init__(self):
        self.model = ModelVersion(uuid4(), "voyage", "voyage-multimodal-3.5", 1024)
        self.candidates = [
            ReconciliationCandidate(uuid4(), uuid4(), "MISSING"),
            ReconciliationCandidate(uuid4(), uuid4(), "FAILED_RETRYABLE"),
            ReconciliationCandidate(uuid4(), uuid4(), "PENDING"),
            ReconciliationCandidate(uuid4(), uuid4(), "MISSING"),
        ]
        self.created = None
        self.lock_acquired = True
        self.recent_job = False

    async def active_model(self):
        return self.model

    async def reconciliation_candidates(self, model_id, timeout, include_failed):
        assert model_id == self.model.id
        assert timeout == timedelta(minutes=30)
        return [
            item
            for item in self.candidates
            if include_failed or not item.reason.startswith("FAILED")
        ]

    async def create_indexing_job(self, job_type, candidates, requested_by=None, **audit):
        self.created = (job_type, candidates, requested_by)
        return IndexingJob(uuid4(), job_type, len(candidates))

    @asynccontextmanager
    async def reconciliation_lock(self):
        yield self.lock_acquired

    async def has_recent_reconciliation_job(self, interval):
        assert interval == timedelta(seconds=3600)
        return self.recent_job


def test_inspect_is_dry_run_and_groups_reasons():
    repository = FakeRepository()
    report = asyncio.run(
        IndexingJobService(repository).inspect(include_failed=False, processing_timeout_minutes=30)
    )
    assert report.total == 3
    assert report.reasons == {"MISSING": 2, "PENDING": 1}
    assert repository.created is None


def test_enqueue_respects_limit():
    repository = FakeRepository()
    service = IndexingJobService(repository)
    report = asyncio.run(service.inspect(processing_timeout_minutes=30))
    job = asyncio.run(service.enqueue("RECONCILIATION", report, limit=2))
    assert job.total_count == 2
    assert repository.created[0] == "RECONCILIATION"
    assert len(repository.created[1]) == 2


def test_inspect_can_select_only_retryable_failures():
    repository = FakeRepository()
    report = asyncio.run(
        IndexingJobService(repository).inspect(
            processing_timeout_minutes=30, retryable_failed_only=True
        )
    )
    assert report.total == 1
    assert report.reasons == {"FAILED_RETRYABLE": 1}


def make_scheduler(repository):
    return ReconciliationScheduler(
        repository,
        interval_seconds=3600,
        initial_delay_seconds=0,
        processing_timeout_minutes=30,
        batch_size=2,
    )


def test_scheduler_skips_when_another_worker_holds_lock():
    repository = FakeRepository()
    repository.lock_acquired = False

    assert asyncio.run(make_scheduler(repository).run_once()) is None
    assert repository.created is None


def test_scheduler_skips_when_recent_job_exists():
    repository = FakeRepository()
    repository.recent_job = True

    assert asyncio.run(make_scheduler(repository).run_once()) is None
    assert repository.created is None


def test_scheduler_excludes_permanent_failures_and_respects_batch_size():
    repository = FakeRepository()
    repository.candidates.append(
        ReconciliationCandidate(uuid4(), uuid4(), "FAILED_PERMANENT")
    )

    job = asyncio.run(make_scheduler(repository).run_once())

    assert job is not None
    assert job.total_count == 2
    assert repository.created[0] == "RECONCILIATION"
    assert all(item.reason != "FAILED_PERMANENT" for item in repository.created[1])
