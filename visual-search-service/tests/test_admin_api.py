"""
Unit tests for the admin API endpoints.
All tests use a fake repository so no real database or RabbitMQ is needed.
"""
import asyncio
from dataclasses import dataclass
from datetime import date, datetime, timezone
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from app.api.admin import router
from app.config import Settings
from app.main import create_app
from app.persistence.repository import (
    CoverageStats,
    IndexingJob,
    ModelVersion,
    OperationsStats,
    ReconciliationCandidate,
    RecentJob,
    RepositoryUnavailableError,
    UsageDayStat,
    VisualSearchRepository,
)
from app.services.indexing_jobs import IndexingJobService


VALID_TOKEN = "test-internal-token"
VALID_SETTINGS = Settings(
    visual_search_enabled=True,
    internal_service_token=VALID_TOKEN,
    database_url="postgresql://fake",
    rabbitmq_url="amqp://fake",
    cloudinary_cloud_name="fake",
)


# ---------------------------------------------------------------------------
# Fake repository
# ---------------------------------------------------------------------------


class FakeRepository:
    def __init__(
        self,
        coverage: CoverageStats | None = None,
        usage: list[UsageDayStat] | None = None,
        jobs: list[RecentJob] | None = None,
        model: ModelVersion | None = None,
        candidates: list[ReconciliationCandidate] | None = None,
        raise_unavailable: bool = False,
    ):
        self._coverage = coverage or CoverageStats(576, 400, 50, 10, 20, 96, 69.44)
        self._usage = usage or []
        self._jobs = jobs or []
        self._model = model or ModelVersion(uuid4(), "voyage", "voyage-multimodal-3.5", 1024)
        self._candidates = candidates or []
        self._raise_unavailable = raise_unavailable
        self.created_job: tuple | None = None
        self.audit_events: list[dict] = []

    async def operations_stats(self) -> OperationsStats:
        if self._raise_unavailable:
            raise RepositoryUnavailableError("down")
        return OperationsStats(self._model, 3, 1, 2)

    async def current_month_cost(self) -> float:
        return 5.0

    async def coverage_stats(self) -> CoverageStats:
        if self._raise_unavailable:
            raise RepositoryUnavailableError("down")
        return self._coverage

    async def usage_stats(self, days: int = 30) -> list[UsageDayStat]:
        if self._raise_unavailable:
            raise RepositoryUnavailableError("down")
        return self._usage

    async def recent_jobs(self, limit: int = 10) -> list[RecentJob]:
        if self._raise_unavailable:
            raise RepositoryUnavailableError("down")
        return self._jobs

    async def active_model(self) -> ModelVersion | None:
        return self._model

    async def reconciliation_candidates(self, model_id, timeout, include_failed):
        return self._candidates

    async def targeted_candidates(self, *, image_id=None, product_id=None):
        if image_id is not None:
            return [ReconciliationCandidate(image_id, uuid4(), "REQUESTED")]
        return self._candidates

    async def create_indexing_job(
        self, job_type: str, candidates: list, requested_by=None, **audit
    ) -> IndexingJob:
        self.created_job = (job_type, candidates)
        if audit.get("audit_action"):
            self.audit_events.append(audit)
        return IndexingJob(uuid4(), job_type, len(candidates))

    async def record_admin_audit(self, **event) -> None:
        self.audit_events.append(event)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_client(repo: FakeRepository) -> TestClient:
    """Create a TestClient with the fake repository injected via DI override."""
    from app.api.admin import QueueStats, get_queue_stats, get_repository
    from app.config import get_settings

    app = create_app()

    app.dependency_overrides[get_settings] = lambda: VALID_SETTINGS
    app.dependency_overrides[get_repository] = lambda: repo
    app.dependency_overrides[get_queue_stats] = lambda: QueueStats(True, 4, 3, 2)
    return TestClient(app, raise_server_exceptions=True)


def headers():
    return {"X-Internal-Service-Token": VALID_TOKEN}


# ---------------------------------------------------------------------------
# Tests – coverage
# ---------------------------------------------------------------------------


def test_coverage_returns_correct_fields():
    repo = FakeRepository(
        coverage=CoverageStats(576, 400, 50, 10, 20, 96, 69.44)
    )
    client = _make_client(repo)
    resp = client.get("/internal/v1/admin/coverage", headers=headers())

    assert resp.status_code == 200
    assert resp.json() == {
        "total_active_images": 576,
        "ready": 400,
        "pending": 50,
        "processing": 10,
        "failed": 20,
        "missing": 96,
        "coverage_pct": 69.44,
    }


def test_operations_returns_model_and_outbox_counts():
    client = _make_client(FakeRepository())
    resp = client.get("/internal/v1/admin/operations", headers=headers())
    assert resp.status_code == 200
    assert resp.json() == {
        "provider": "voyage",
        "model": "voyage-multimodal-3.5",
        "dimensions": 1024,
        "outbox_pending": 3,
        "outbox_publishing": 1,
        "outbox_failed": 2,
        "rabbitmq_available": True,
        "main_queue_messages": 4,
        "retry_queue_messages": 3,
        "dlq_messages": 2,
        "monthly_cost_usd": 5.0,
        "monthly_budget_usd": 20.0,
        "budget_usage_pct": 25.0,
        "budget_exhausted": False,
    }


def test_backfill_missing_only_enqueues_missing_candidates():
    candidates = [
        ReconciliationCandidate(uuid4(), uuid4(), "MISSING"),
        ReconciliationCandidate(uuid4(), uuid4(), "PENDING"),
    ]
    repo = FakeRepository(candidates=candidates)
    resp = _make_client(repo).post("/internal/v1/admin/backfill-missing", headers=headers())
    assert resp.status_code == 200
    assert resp.json()["enqueued_count"] == 1
    assert repo.created_job[0] == "BACKFILL"
    assert repo.audit_events == [{
        "audit_action": "VISUAL_BACKFILL_MISSING",
        "audit_target_type": "ACTIVE_CATALOG",
        "audit_target_id": "all",
    }]


def test_reindex_image_enqueues_image_job():
    image_id = uuid4()
    repo = FakeRepository()
    resp = _make_client(repo).post(
        "/internal/v1/admin/reindex", headers=headers(), json={"image_id": str(image_id)}
    )
    assert resp.status_code == 200
    assert resp.json()["enqueued_count"] == 1
    assert repo.created_job[0] == "IMAGE"
    assert repo.audit_events[0]["audit_action"] == "VISUAL_REINDEX_PRODUCT_IMAGE"
    assert repo.audit_events[0]["audit_target_id"] == str(image_id)


def test_reindex_rejects_ambiguous_target():
    resp = _make_client(FakeRepository()).post(
        "/internal/v1/admin/reindex",
        headers=headers(),
        json={"image_id": str(uuid4()), "product_id": str(uuid4())},
    )
    assert resp.status_code == 422


def test_coverage_unauthorized_without_token():
    from app.config import get_settings
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: VALID_SETTINGS
    client = TestClient(app)
    resp = client.get("/internal/v1/admin/coverage")
    assert resp.status_code == 401


def test_coverage_503_when_disabled():
    from app.config import get_settings
    app = create_app()
    disabled = Settings(visual_search_enabled=False, internal_service_token=VALID_TOKEN)
    app.dependency_overrides[get_settings] = lambda: disabled
    client = TestClient(app)
    resp = client.get("/internal/v1/admin/coverage", headers=headers())
    assert resp.status_code == 503


def test_usage_unauthorized_without_token():
    from app.config import get_settings
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: VALID_SETTINGS
    client = TestClient(app)
    resp = client.get("/internal/v1/admin/usage")
    assert resp.status_code == 401


def test_usage_503_when_disabled():
    from app.config import get_settings
    app = create_app()
    disabled = Settings(visual_search_enabled=False, internal_service_token=VALID_TOKEN)
    app.dependency_overrides[get_settings] = lambda: disabled
    client = TestClient(app)
    resp = client.get("/internal/v1/admin/usage", headers=headers())
    assert resp.status_code == 503


def test_jobs_unauthorized_without_token():
    from app.config import get_settings
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: VALID_SETTINGS
    client = TestClient(app)
    resp = client.get("/internal/v1/admin/jobs")
    assert resp.status_code == 401


def test_retry_failed_unauthorized_without_token():
    from app.config import get_settings
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: VALID_SETTINGS
    client = TestClient(app)
    resp = client.post("/internal/v1/admin/retry-failed")
    assert resp.status_code == 401


def test_retry_failed_503_when_disabled():
    from app.config import get_settings
    app = create_app()
    disabled = Settings(visual_search_enabled=False, internal_service_token=VALID_TOKEN)
    app.dependency_overrides[get_settings] = lambda: disabled
    client = TestClient(app)
    resp = client.post("/internal/v1/admin/retry-failed", headers=headers())
    assert resp.status_code == 503


def test_coverage_response_schema():
    """Verify the CoverageResponse model has all required keys."""
    from app.api.admin import CoverageResponse
    cr = CoverageResponse(
        total_active_images=576,
        ready=400,
        pending=50,
        processing=10,
        failed=20,
        missing=96,
        coverage_pct=69.44,
    )
    data = cr.model_dump()
    assert set(data.keys()) == {
        "total_active_images", "ready", "pending", "processing", "failed", "missing", "coverage_pct"
    }
    assert data["coverage_pct"] == pytest.approx(69.44)


def test_usage_response_schema():
    """Verify UsageResponse and nested row schema."""
    from app.api.admin import UsageDayStatResponse, UsageResponse
    row = UsageDayStatResponse(
        day=date(2026, 7, 31),
        operation="DOCUMENT_EMBEDDING",
        requests=100,
        image_pixels=1_000_000,
        text_tokens=0,
        estimated_cost_usd=0.06,
        success_count=98,
        failure_count=2,
    )
    resp = UsageResponse(days=30, rows=[row])
    data = resp.model_dump()
    assert data["days"] == 30
    assert len(data["rows"]) == 1
    assert data["rows"][0]["operation"] == "DOCUMENT_EMBEDDING"
    assert data["rows"][0]["estimated_cost_usd"] == pytest.approx(0.06)


def test_retry_failed_response_schema_no_failures():
    """RetryFailedResponse with zero failures returns empty job_id and count 0."""
    from app.api.admin import RetryFailedResponse
    resp = RetryFailedResponse(job_id="", enqueued_count=0)
    data = resp.model_dump()
    assert data["enqueued_count"] == 0
    assert data["job_id"] == ""


def test_retry_failed_noop_is_audited_without_token_or_pii():
    repo = FakeRepository(candidates=[])

    resp = _make_client(repo).post("/internal/v1/admin/retry-failed", headers=headers())

    assert resp.status_code == 200
    assert resp.json() == {"job_id": "", "enqueued_count": 0}
    assert repo.audit_events == [{
        "action": "VISUAL_RETRY_FAILED",
        "entity_type": "VISUAL_INDEXING_JOB",
        "entity_id": "NO_JOB",
        "after": {"job_type": "RECONCILIATION", "enqueued_count": 0},
    }]
    serialized = str(repo.audit_events).lower()
    assert VALID_TOKEN.lower() not in serialized
    assert "image_url" not in serialized
    assert "ip_address" not in serialized
    assert "user_agent" not in serialized


def test_admin_audit_payload_rejects_non_allowlisted_sensitive_fields():
    with pytest.raises(ValueError, match="Unsupported visual-search audit fields"):
        VisualSearchRepository._validate_admin_audit_payload(
            "VISUAL_REINDEX_PRODUCT",
            "VISUAL_INDEXING_JOB",
            None,
            {"job_type": "PRODUCT", "internal_token": "must-not-be-recorded"},
        )


def test_retry_failed_response_schema_with_failures():
    """RetryFailedResponse with enqueued jobs."""
    from app.api.admin import RetryFailedResponse
    job_id = str(uuid4())
    resp = RetryFailedResponse(job_id=job_id, enqueued_count=5)
    data = resp.model_dump()
    assert data["enqueued_count"] == 5
    assert data["job_id"] == job_id


def test_retry_failed_enqueues_only_retryable_failures():
    candidates = [
        ReconciliationCandidate(uuid4(), uuid4(), "FAILED_RETRYABLE"),
        ReconciliationCandidate(uuid4(), uuid4(), "FAILED_PERMANENT"),
    ]
    repo = FakeRepository(candidates=candidates)

    resp = _make_client(repo).post("/internal/v1/admin/retry-failed", headers=headers())

    assert resp.status_code == 200
    assert resp.json()["enqueued_count"] == 1
    assert repo.created_job[1][0].reason == "FAILED_RETRYABLE"
    assert repo.audit_events[0]["audit_action"] == "VISUAL_RETRY_FAILED"


def test_recent_job_response_schema():
    from app.api.admin import RecentJobResponse
    jid = str(uuid4())
    rj = RecentJobResponse(
        id=jid,
        job_type="BACKFILL",
        status="RUNNING",
        total_count=576,
        completed_count=200,
        failed_count=5,
        pending_count=371,
        created_at="2026-07-31T10:00:00+00:00",
        completed_at=None,
    )
    data = rj.model_dump()
    assert data["job_type"] == "BACKFILL"
    assert data["completed_at"] is None


def test_indexing_job_service_enqueue_failed_only():
    """IndexingJobService.enqueue with only FAILED candidates from a mixed report."""
    from app.services.indexing_jobs import ReconciliationReport

    model_id = uuid4()
    candidates = [
        ReconciliationCandidate(uuid4(), uuid4(), "FAILED"),
        ReconciliationCandidate(uuid4(), uuid4(), "MISSING"),
        ReconciliationCandidate(uuid4(), uuid4(), "FAILED"),
    ]
    failed_candidates = tuple(c for c in candidates if c.reason == "FAILED")
    report = ReconciliationReport(total=len(failed_candidates), reasons={"FAILED": 2}, candidates=failed_candidates)

    repo = FakeRepository()
    service = IndexingJobService(repo)
    job = asyncio.run(service.enqueue("RECONCILIATION", report))
    assert job.total_count == 2
    assert repo.created_job[0] == "RECONCILIATION"
    assert len(repo.created_job[1]) == 2
