"""
Admin API – internal endpoints for coverage, usage, retry and job history.
All endpoints require the X-Internal-Service-Token header; never expose to
public internet in production.
"""
import hmac
from dataclasses import dataclass
from datetime import date
from uuid import UUID

import aio_pika
from psycopg import Error as PsycopgError
from fastapi import APIRouter, Depends, Header, HTTPException, Query, status
from pydantic import BaseModel, Field

from app.config import Settings, get_settings
from app.persistence.repository import (
    CoverageStats,
    OperationsStats,
    RecentJob,
    RepositoryUnavailableError,
    UsageDayStat,
    ModelVersionStats,
    VisualSearchRepository,
)
from app.services.indexing_jobs import IndexingJobService, ReconciliationReport

router = APIRouter(prefix="/internal/v1/admin", tags=["admin"])


def get_repository(settings: Settings = Depends(get_settings)) -> VisualSearchRepository:
    return VisualSearchRepository(settings)


@dataclass(frozen=True, slots=True)
class QueueStats:
    available: bool
    main: int | None = None
    retry: int | None = None
    dlq: int | None = None


async def get_queue_stats(settings: Settings = Depends(get_settings)) -> QueueStats:
    connection = None
    try:
        connection = await aio_pika.connect_robust(
            settings.rabbitmq_url, timeout=settings.readiness_timeout_seconds
        )
        channel = await connection.channel()
        main = await channel.declare_queue(settings.rabbitmq_consumer_queue, passive=True)
        retries = [await channel.declare_queue(name, passive=True) for name in settings.retry_queues]
        dlq = await channel.declare_queue(settings.rabbitmq_dlq, passive=True)
        return QueueStats(
            True,
            main.declaration_result.message_count,
            sum(queue.declaration_result.message_count for queue in retries),
            dlq.declaration_result.message_count,
        )
    except Exception:
        return QueueStats(False)
    finally:
        if connection is not None:
            await connection.close()


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------


def require_internal_token(
    x_internal_service_token: str = Header(default=""),
    settings: Settings = Depends(get_settings),
) -> None:
    if not settings.internal_service_token or not hmac.compare_digest(
        x_internal_service_token, settings.internal_service_token
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal service token")


# ---------------------------------------------------------------------------
# Response models
# ---------------------------------------------------------------------------


class CoverageResponse(BaseModel):
    total_active_images: int
    ready: int
    pending: int
    processing: int
    failed: int
    missing: int
    coverage_pct: float


class UsageDayStatResponse(BaseModel):
    day: date
    operation: str
    requests: int
    image_pixels: int
    text_tokens: int
    estimated_cost_usd: float
    success_count: int
    failure_count: int


class UsageResponse(BaseModel):
    days: int
    rows: list[UsageDayStatResponse]


class RecentJobResponse(BaseModel):
    id: str
    job_type: str
    status: str
    total_count: int
    completed_count: int
    failed_count: int
    pending_count: int
    created_at: str
    completed_at: str | None
    source_counts: dict[str, int] = Field(default_factory=dict)
    error_summary: dict[str, int] = Field(default_factory=dict)


class JobsResponse(BaseModel):
    jobs: list[RecentJobResponse]


class RetryFailedResponse(BaseModel):
    job_id: str
    enqueued_count: int


class OperationsResponse(BaseModel):
    provider: str | None
    model: str | None
    dimensions: int | None
    outbox_pending: int
    outbox_publishing: int
    outbox_failed: int
    rabbitmq_available: bool
    main_queue_messages: int | None
    retry_queue_messages: int | None
    dlq_messages: int | None
    monthly_cost_usd: float
    monthly_budget_usd: float
    budget_usage_pct: float
    budget_exhausted: bool


class ModelVersionResponse(BaseModel):
    id: str
    provider: str
    model: str
    dimensions: int
    status: str
    target_image_count: int
    ready_image_count: int
    failed_image_count: int
    activated_at: str | None
    revision: int = 1


class CreateModelVersionRequest(BaseModel):
    provider: str
    model: str
    model_version: str
    preprocessing_version: str
    dimensions: int


class ModelVersionsResponse(BaseModel):
    models: list[ModelVersionResponse]


class ReindexRequest(BaseModel):
    image_id: UUID | None = None
    product_id: UUID | None = None


class EnqueueResponse(BaseModel):
    job_id: str
    enqueued_count: int


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/operations",
    response_model=OperationsResponse,
    dependencies=[Depends(require_internal_token)],
    summary="Active model and transactional outbox status",
)
async def get_operations(
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
    queues: QueueStats = Depends(get_queue_stats),
) -> OperationsResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    try:
        stats: OperationsStats = await repo.operations_stats()
        monthly_cost = await repo.current_month_cost()
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database unavailable") from exc
    return OperationsResponse(
        provider=stats.model.provider if stats.model else None,
        model=stats.model.model if stats.model else None,
        dimensions=stats.model.dimensions if stats.model else None,
        outbox_pending=stats.outbox_pending,
        outbox_publishing=stats.outbox_publishing,
        outbox_failed=stats.outbox_failed,
        rabbitmq_available=queues.available,
        main_queue_messages=queues.main,
        retry_queue_messages=queues.retry,
        dlq_messages=queues.dlq,
        monthly_cost_usd=monthly_cost,
        monthly_budget_usd=settings.monthly_budget_usd,
        budget_usage_pct=(monthly_cost / settings.monthly_budget_usd * 100) if settings.monthly_budget_usd else 100.0,
        budget_exhausted=settings.monthly_budget_usd == 0 or monthly_cost >= settings.monthly_budget_usd,
    )


def _model_response(item: ModelVersionStats) -> ModelVersionResponse:
    return ModelVersionResponse(
        id=str(item.id), provider=item.provider, model=item.model, dimensions=item.dimensions,
        status=item.status, target_image_count=item.target_image_count,
        ready_image_count=item.ready_image_count, failed_image_count=item.failed_image_count,
        activated_at=item.activated_at.isoformat() if item.activated_at else None,
        revision=item.revision,
    )


@router.get("/models", response_model=ModelVersionsResponse, dependencies=[Depends(require_internal_token)])
async def get_models(repo: VisualSearchRepository = Depends(get_repository)) -> ModelVersionsResponse:
    try:
        return ModelVersionsResponse(models=[_model_response(item) for item in await repo.model_versions()])
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=503, detail="Database unavailable") from exc


@router.post("/models", response_model=ModelVersionResponse, dependencies=[Depends(require_internal_token)])
async def create_model(
    request: CreateModelVersionRequest,
    repo: VisualSearchRepository = Depends(get_repository),
) -> ModelVersionResponse:
    provider = request.provider.strip().upper()
    model = request.model.strip()
    model_version = request.model_version.strip()
    preprocessing_version = request.preprocessing_version.strip()
    if not provider or not model or not model_version or not preprocessing_version or request.dimensions <= 0:
        raise HTTPException(
            status_code=422,
            detail="provider, model, model_version, preprocessing_version and positive dimensions are required",
        )
    try:
        return _model_response(await repo.create_model_version(
            provider, model, model_version, preprocessing_version, request.dimensions
        ))
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=503, detail="Database unavailable") from exc


@router.post("/models/{model_id}/activate", response_model=ModelVersionResponse,
             dependencies=[Depends(require_internal_token)])
async def activate_model(model_id: UUID, repo: VisualSearchRepository = Depends(get_repository)) -> ModelVersionResponse:
    try:
        return _model_response(await repo.activate_model_version(model_id))
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=503, detail="Database unavailable") from exc


@router.post(
    "/backfill-missing",
    response_model=EnqueueResponse,
    dependencies=[Depends(require_internal_token)],
    summary="Enqueue ACTIVE catalog images missing an embedding",
)
async def backfill_missing(
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
) -> EnqueueResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    try:
        service = IndexingJobService(repo)
        report = await service.inspect(include_failed=False)
        candidates = tuple(item for item in report.candidates if item.reason == "MISSING")
        filtered = ReconciliationReport(total=len(candidates), reasons={"MISSING": len(candidates)}, candidates=candidates)
        job = await service.enqueue(
            "BACKFILL",
            filtered,
            audit_action="VISUAL_BACKFILL_MISSING",
            audit_target_type="ACTIVE_CATALOG",
            audit_target_id="all",
        )
        return EnqueueResponse(job_id=str(job.id), enqueued_count=job.total_count)
    except (RepositoryUnavailableError, PsycopgError, RuntimeError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc


@router.post(
    "/reindex",
    response_model=EnqueueResponse,
    dependencies=[Depends(require_internal_token)],
    summary="Reindex one image or all images of one ACTIVE product",
)
async def reindex(
    request: ReindexRequest,
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
) -> EnqueueResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    if (request.image_id is None) == (request.product_id is None):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Provide exactly one image_id or product_id")
    try:
        candidates = await repo.targeted_candidates(image_id=request.image_id, product_id=request.product_id)
        if not candidates:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No ACTIVE catalog image matched")
        service = IndexingJobService(repo)
        report = ReconciliationReport(total=len(candidates), reasons={"REQUESTED": len(candidates)}, candidates=tuple(candidates))
        target_type = "PRODUCT_IMAGE" if request.image_id else "PRODUCT"
        target_id = str(request.image_id or request.product_id)
        job = await service.enqueue(
            "IMAGE" if request.image_id else "PRODUCT",
            report,
            audit_action=f"VISUAL_REINDEX_{target_type}",
            audit_target_type=target_type,
            audit_target_id=target_id,
        )
        return EnqueueResponse(job_id=str(job.id), enqueued_count=job.total_count)
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database unavailable") from exc


@router.get(
    "/coverage",
    response_model=CoverageResponse,
    dependencies=[Depends(require_internal_token)],
    summary="Embedding coverage for the active model",
)
async def get_coverage(
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
) -> CoverageResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    try:
        stats: CoverageStats = await repo.coverage_stats()
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database unavailable") from exc
    return CoverageResponse(
        total_active_images=stats.total_active_images,
        ready=stats.ready,
        pending=stats.pending,
        processing=stats.processing,
        failed=stats.failed,
        missing=stats.missing,
        coverage_pct=stats.coverage_pct,
    )


@router.get(
    "/usage",
    response_model=UsageResponse,
    dependencies=[Depends(require_internal_token)],
    summary="AI API usage and estimated cost",
)
async def get_usage(
    days: int = Query(default=30, ge=1, le=365),
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
) -> UsageResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    try:
        rows: list[UsageDayStat] = await repo.usage_stats(days)
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database unavailable") from exc
    return UsageResponse(
        days=days,
        rows=[
            UsageDayStatResponse(
                day=row.day,
                operation=row.operation,
                requests=row.requests,
                image_pixels=row.image_pixels,
                text_tokens=row.text_tokens,
                estimated_cost_usd=row.estimated_cost_usd,
                success_count=row.success_count,
                failure_count=row.failure_count,
            )
            for row in rows
        ],
    )


@router.get(
    "/jobs",
    response_model=JobsResponse,
    dependencies=[Depends(require_internal_token)],
    summary="Recent indexing/backfill/reconciliation jobs",
)
async def get_jobs(
    limit: int = Query(default=10, ge=1, le=50),
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
) -> JobsResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    try:
        jobs: list[RecentJob] = await repo.recent_jobs(limit)
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database unavailable") from exc
    return JobsResponse(
        jobs=[
            RecentJobResponse(
                id=str(job.id),
                job_type=job.job_type,
                status=job.status,
                total_count=job.total_count,
                completed_count=job.completed_count,
                failed_count=job.failed_count,
                pending_count=job.pending_count,
                created_at=job.created_at.isoformat(),
                completed_at=job.completed_at.isoformat() if job.completed_at else None,
                source_counts=job.source_counts,
                error_summary=job.error_summary,
            )
            for job in jobs
        ]
    )


@router.post(
    "/retry-failed",
    response_model=RetryFailedResponse,
    dependencies=[Depends(require_internal_token)],
    summary="Enqueue a RECONCILIATION job for all FAILED embeddings",
)
async def retry_failed(
    settings: Settings = Depends(get_settings),
    repo: VisualSearchRepository = Depends(get_repository),
) -> RetryFailedResponse:
    if not settings.visual_search_enabled:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Visual search is disabled")
    try:
        service = IndexingJobService(repo)
        report = await service.inspect(include_failed=True)
        retryable = tuple(c for c in report.candidates if c.reason == "FAILED_RETRYABLE")
        failed_only_report = report.__class__(
            total=len(retryable),
            reasons={"FAILED_RETRYABLE": len(retryable)},
            candidates=retryable,
        )
        if failed_only_report.total == 0:
            await repo.record_admin_audit(
                action="VISUAL_RETRY_FAILED",
                entity_type="VISUAL_INDEXING_JOB",
                entity_id="NO_JOB",
                after={"job_type": "RECONCILIATION", "enqueued_count": 0},
            )
            return RetryFailedResponse(job_id="", enqueued_count=0)
        job = await service.enqueue(
            "RECONCILIATION",
            failed_only_report,
            audit_action="VISUAL_RETRY_FAILED",
            audit_target_type="FAILED_RETRYABLE",
            audit_target_id="all",
        )
    except (RepositoryUnavailableError, PsycopgError) as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database unavailable") from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    return RetryFailedResponse(job_id=str(job.id), enqueued_count=job.total_count)
