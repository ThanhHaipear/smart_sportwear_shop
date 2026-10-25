from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
import json
from typing import Any
from uuid import UUID, uuid4

import psycopg
from psycopg.errors import OperationalError

from app.config import Settings
from app.providers.base import EmbeddingResult


class RepositoryUnavailableError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class ModelVersion:
    id: UUID
    provider: str
    model: str
    dimensions: int
    model_version: str = ""
    preprocessing_version: str = ""


@dataclass(frozen=True, slots=True)
class CatalogImage:
    id: UUID
    product_id: UUID
    image_url: str
    public_id: str | None
    product_status: str
    asset_version: str | None = None


@dataclass(frozen=True, slots=True)
class ExistingEmbedding:
    status: str
    image_hash: str | None
    color_signature: tuple[float, ...] | None = None


@dataclass(frozen=True, slots=True)
class SearchCandidate:
    product_id: UUID
    image_id: UUID
    matched_image_url: str
    similarity: float


# Shape/content embeddings are the primary retrieval signal. Colour is useful
# for separating otherwise similar garments, but must not make a dark pair of
# shorts outrank a long-sleeved tee merely because both are black.
VISUAL_SIMILARITY_WEIGHT = 0.85
COLOR_SIMILARITY_WEIGHT = 0.15


def blend_visual_and_color_similarity(visual_score: float, color_score: float) -> float:
    return VISUAL_SIMILARITY_WEIGHT * visual_score + COLOR_SIMILARITY_WEIGHT * color_score


@dataclass(frozen=True, slots=True)
class ReconciliationCandidate:
    image_id: UUID
    product_id: UUID
    reason: str


@dataclass(frozen=True, slots=True)
class IndexingJob:
    id: UUID
    job_type: str
    total_count: int


@dataclass(frozen=True, slots=True)
class CoverageStats:
    total_active_images: int
    ready: int
    pending: int
    processing: int
    failed: int
    missing: int
    coverage_pct: float


@dataclass(frozen=True, slots=True)
class UsageDayStat:
    day: date
    operation: str
    requests: int
    image_pixels: int
    text_tokens: int
    estimated_cost_usd: float
    success_count: int
    failure_count: int


@dataclass(frozen=True, slots=True)
class RecentJob:
    id: UUID
    job_type: str
    status: str
    total_count: int
    completed_count: int
    failed_count: int
    pending_count: int
    created_at: datetime
    completed_at: datetime | None
    source_counts: dict[str, int] = field(default_factory=dict)
    error_summary: dict[str, int] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class OperationsStats:
    model: ModelVersion | None
    outbox_pending: int
    outbox_publishing: int
    outbox_failed: int


@dataclass(frozen=True, slots=True)
class ModelVersionStats:
    id: UUID
    provider: str
    model: str
    dimensions: int
    status: str
    target_image_count: int
    ready_image_count: int
    failed_image_count: int
    activated_at: datetime | None
    revision: int = 1


class VisualSearchRepository:
    RECONCILIATION_ADVISORY_LOCK_ID = 7_410_057_007
    ADMIN_AUDIT_ENTITY_TYPES = frozenset(
        {"VISUAL_EMBEDDING_MODEL", "VISUAL_INDEXING_JOB"}
    )
    ADMIN_AUDIT_FIELDS = frozenset(
        {
            "active_model_id",
            "dimensions",
            "enqueued_count",
            "job_type",
            "model",
            "model_version",
            "preprocessing_version",
            "provider",
            "status",
            "target_id",
            "target_status",
            "target_type",
        }
    )

    def __init__(self, settings: Settings):
        self.database_url = settings.database_url
        self.image_cost_per_megapixel_usd = settings.image_cost_per_megapixel_usd

    def _estimated_cost(self, image_pixels: int) -> float:
        return max(0, image_pixels) / 1_000_000 * self.image_cost_per_megapixel_usd

    @staticmethod
    def _validate_admin_audit_payload(
        action: str,
        entity_type: str,
        before: dict[str, Any] | None,
        after: dict[str, Any] | None,
    ) -> None:
        if not action.startswith("VISUAL_"):
            raise ValueError("Visual-search audit actions must use the VISUAL_ prefix")
        if entity_type not in VisualSearchRepository.ADMIN_AUDIT_ENTITY_TYPES:
            raise ValueError("Unsupported visual-search audit entity type")
        fields = set((before or {}).keys()) | set((after or {}).keys())
        unsupported = fields - VisualSearchRepository.ADMIN_AUDIT_FIELDS
        if unsupported:
            raise ValueError(
                "Unsupported visual-search audit fields: " + ", ".join(sorted(unsupported))
            )

    @staticmethod
    async def _insert_admin_audit(
        cursor: Any,
        *,
        action: str,
        entity_type: str,
        entity_id: str,
        before: dict[str, Any] | None = None,
        after: dict[str, Any] | None = None,
    ) -> None:
        """Write an allowlisted, non-PII admin event to the append-only audit log."""
        VisualSearchRepository._validate_admin_audit_payload(
            action, entity_type, before, after
        )
        await cursor.execute(
            """
            insert into public.audit_logs
                (action, entity_type, entity_id, before_json, after_json, details_redacted)
            values (%s, %s, %s, %s::jsonb, %s::jsonb,
                    '{"source":"visual-search-service","result":"SUCCESS"}'::jsonb)
            """,
            (
                action,
                entity_type,
                entity_id,
                json.dumps(before) if before is not None else None,
                json.dumps(after) if after is not None else None,
            ),
        )

    async def record_admin_audit(
        self,
        *,
        action: str,
        entity_type: str,
        entity_id: str,
        after: dict[str, Any],
    ) -> None:
        """Record a successful admin no-op when there is no business write transaction."""
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await self._insert_admin_audit(
                        cursor,
                        action=action,
                        entity_type=entity_type,
                        entity_id=entity_id,
                        after=after,
                    )

    async def _connect(self) -> psycopg.AsyncConnection[Any]:
        try:
            return await psycopg.AsyncConnection.connect(self.database_url, connect_timeout=10)
        except OperationalError as exc:
            raise RepositoryUnavailableError("Visual search database is unavailable") from exc

    async def is_processed(self, event_id: UUID) -> bool:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """select exists(
                           select 1 from ai_retrieval.processed_events
                           where consumer_name = 'visual-search-indexer' and event_id = %s
                       )""",
                    (event_id,),
                )
                return bool((await cursor.fetchone())[0])

    async def active_model(self) -> ModelVersion | None:
        async with await self._connect() as connection:
            return await self.active_model_on(connection)

    async def current_month_cost(self) -> float:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """select coalesce(sum(estimated_cost_usd), 0)
                       from ai_retrieval.usage_events
                       where occurred_at >= date_trunc('month', now())"""
                )
                return float((await cursor.fetchone())[0])

    async def model_versions(self) -> list[ModelVersionStats]:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select m.id, m.provider, m.model_name, m.dimensions, m.status,
                           (select count(*) from product_images pi join products p on p.id=pi.product_id
                            where p.status='ACTIVE')::int,
                           (select count(*) from ai_retrieval.product_image_embeddings e
                            join product_images pi on pi.id=e.image_id and pi.product_id=e.product_id
                              and pi.public_id=e.source_public_id and pi.asset_version=e.source_asset_version
                            join products p on p.id=e.product_id
                            where e.model_id=m.id and e.status='READY' and p.status='ACTIVE')::int,
                           (select count(*) from ai_retrieval.product_image_embeddings e
                            join product_images pi on pi.id=e.image_id and pi.product_id=e.product_id
                              and pi.public_id=e.source_public_id and pi.asset_version=e.source_asset_version
                            join products p on p.id=e.product_id
                            where e.model_id=m.id and e.status='FAILED' and p.status='ACTIVE')::int,
                           m.activated_at,
                           dense_rank() over (partition by m.provider, m.model_name order by m.created_at)::int
                    from ai_retrieval.embedding_models m
                    where m.modality = 'PRODUCT_IMAGE'
                    order by m.created_at desc
                    """
                )
                return [ModelVersionStats(*row) for row in await cursor.fetchall()]

    async def create_model_version(
        self, provider: str, model: str, model_version: str,
        preprocessing_version: str, dimensions: int,
    ) -> ModelVersionStats:
        if dimensions <= 0:
            raise ValueError("Model dimensions must be positive")
        model_id = uuid4()
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        insert into ai_retrieval.embedding_models
                            (id, modality, provider, model_name, model_version, dimensions,
                             distance_metric, normalization, preprocessing_version, status)
                        values (%s, 'PRODUCT_IMAGE', %s, %s, %s, %s,
                                'COSINE', 'L2', %s, 'DRAFT')
                        """,
                        (model_id, provider, model, model_version, dimensions, preprocessing_version),
                    )
                    await self._insert_admin_audit(
                        cursor,
                        action="VISUAL_MODEL_CREATE",
                        entity_type="VISUAL_EMBEDDING_MODEL",
                        entity_id=str(model_id),
                        after={
                            "provider": provider,
                            "model": model,
                            "model_version": model_version,
                            "preprocessing_version": preprocessing_version,
                            "dimensions": dimensions,
                            "status": "DRAFT",
                        },
                    )
        versions = await self.model_versions()
        return next(version for version in versions if version.id == model_id)

    async def activate_model_version(self, model_id: UUID) -> ModelVersionStats:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """select status from ai_retrieval.embedding_models
                           where id=%s and modality='PRODUCT_IMAGE' for update""", (model_id,)
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise ValueError("Model version not found")
                    previous_status = row[0]
                    await cursor.execute(
                        """select id from ai_retrieval.embedding_models
                           where modality='PRODUCT_IMAGE' and status='ACTIVE' for update"""
                    )
                    active_row = await cursor.fetchone()
                    previous_active_model_id = str(active_row[0]) if active_row else None
                    await cursor.execute(
                        """select count(*)::int,
                                  count(*) filter(where e.status='READY')::int
                           from product_images pi join products p on p.id=pi.product_id
                           left join ai_retrieval.product_image_embeddings e
                             on e.image_id=pi.id and e.model_id=%s
                            and e.product_id=pi.product_id
                            and e.source_public_id=pi.public_id
                            and e.source_asset_version=pi.asset_version
                           where p.status='ACTIVE'""", (model_id,)
                    )
                    total, ready = await cursor.fetchone()
                    if total == 0 or ready / total < 0.98:
                        raise ValueError(f"Model coverage {ready}/{total} is below the 98% activation gate")
                    await cursor.execute(
                        """update ai_retrieval.embedding_models
                           set status='RETIRED', updated_at=now()
                           where modality='PRODUCT_IMAGE' and status='ACTIVE' and id<>%s""",
                        (model_id,),
                    )
                    await self._insert_admin_audit(
                        cursor,
                        action="VISUAL_MODEL_ACTIVATE",
                        entity_type="VISUAL_EMBEDDING_MODEL",
                        entity_id=str(model_id),
                        before={
                            "active_model_id": previous_active_model_id,
                            "target_status": previous_status,
                        },
                        after={"active_model_id": str(model_id), "target_status": "ACTIVE"},
                    )
                    await cursor.execute(
                        """update ai_retrieval.embedding_models
                           set status='ACTIVE', activated_at=coalesce(activated_at, now()), updated_at=now()
                           where id=%s""",
                        (model_id,),
                    )
        versions = await self.model_versions()
        return next(version for version in versions if version.id == model_id)

    @staticmethod
    async def active_model_on(connection: psycopg.AsyncConnection[Any]) -> ModelVersion | None:
        async with connection.cursor() as cursor:
            await cursor.execute(
                """
                select id, provider, model_name, dimensions, model_version, preprocessing_version
                from ai_retrieval.embedding_models
                where modality = 'PRODUCT_IMAGE' and status = 'ACTIVE'
                """
            )
            row = await cursor.fetchone()
            return ModelVersion(*row) if row else None

    async def get_image(self, image_id: UUID) -> CatalogImage | None:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select pi.id, pi.product_id, pi.secure_url, pi.public_id, p.status, pi.asset_version
                    from product_images pi join products p on p.id = pi.product_id
                    where pi.id = %s
                    """,
                    (image_id,),
                )
                row = await cursor.fetchone()
                return CatalogImage(*row) if row else None

    async def product_image_ids(self, product_id: UUID) -> list[UUID]:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute("select id from product_images where product_id = %s order by created_at", (product_id,))
                return [row[0] for row in await cursor.fetchall()]

    async def existing_embedding(self, image_id: UUID, model_id: UUID) -> ExistingEmbedding | None:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """select status, image_hash, color_signature
                       from ai_retrieval.product_image_embeddings
                       where image_id = %s and model_id = %s""",
                    (image_id, model_id),
                )
                row = await cursor.fetchone()
                return ExistingEmbedding(*row) if row else None

    async def mark_color_signature_and_processed(
        self, event_id: UUID | None, event_type: str | None, event_version: int | None,
        image_id: UUID, model_id: UUID, signature: tuple[float, ...],
    ) -> None:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """update ai_retrieval.product_image_embeddings
                           set color_signature=%s, updated_at=now()
                           where image_id=%s and model_id=%s""",
                        (list(signature), image_id, model_id),
                    )
                    if event_id is not None and event_type is not None and event_version is not None:
                        await self._insert_processed(cursor, event_id, event_type, event_version)
                        await self._complete_job_item(cursor, event_id)

    async def mark_processing(self, image: CatalogImage, model_id: UUID) -> bool:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        insert into ai_retrieval.product_image_embeddings
                            (image_id, product_id, model_id, source_public_id, source_asset_version,
                             status, attempts, last_attempt_at)
                        values (%s, %s, %s, %s, %s, 'PROCESSING', 1, now())
                        on conflict (image_id, model_id) do update
                        set product_id = excluded.product_id,
                            source_public_id = excluded.source_public_id,
                            source_asset_version = excluded.source_asset_version,
                            status = 'PROCESSING',
                            attempts = ai_retrieval.product_image_embeddings.attempts + 1,
                            last_attempt_at = now(), last_error = null, failure_code = null, updated_at = now()
                        where ai_retrieval.product_image_embeddings.status <> 'PROCESSING'
                           or ai_retrieval.product_image_embeddings.last_attempt_at < now() - interval '15 minutes'
                        returning image_id
                        """,
                        (image.id, image.product_id, model_id, image.public_id, image.asset_version),
                    )
                    changed = await cursor.fetchone() is not None
                    if changed:
                        await self._refresh_model_counts(cursor, model_id)
                    return changed

    async def mark_ready_and_processed(
        self,
        event_id: UUID | None,
        event_type: str | None,
        event_version: int | None,
        image: CatalogImage,
        model: ModelVersion,
        image_hash: str,
        color_signature: tuple[float, ...],
        result: EmbeddingResult,
        latency_ms: int,
    ) -> None:
        vector = "[" + ",".join(str(value) for value in result.vector) + "]"
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        update ai_retrieval.product_image_embeddings
                        set embedding = %s::extensions.vector, image_hash = %s, status = 'READY', last_error = null,
                            failure_code = null, color_signature = %s,
                            embedded_at = now(), updated_at = now()
                        where image_id = %s and model_id = %s
                        """,
                        (vector, image_hash, list(color_signature), image.id, model.id),
                    )
                    await cursor.execute(
                        """
                        insert into ai_retrieval.usage_events
                            (id, model_id, provider, model_name, operation, image_pixels, text_tokens,
                             estimated_cost_usd, latency_ms, success)
                        values (%s, %s, %s, %s, 'DOCUMENT_EMBEDDING', %s, %s, %s, %s, true)
                        """,
                        (uuid4(), model.id, model.provider, model.model, result.usage.image_pixels,
                         result.usage.text_tokens, self._estimated_cost(result.usage.image_pixels), latency_ms),
                    )
                    if event_id is not None and event_type is not None and event_version is not None:
                        await self._insert_processed(cursor, event_id, event_type, event_version)
                        await self._complete_job_item(cursor, event_id)
                    await self._refresh_model_counts(cursor, model.id)

    async def mark_processed(self, event_id: UUID, event_type: str, event_version: int) -> None:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await self._insert_processed(cursor, event_id, event_type, event_version)
                    await self._complete_job_item(cursor, event_id)
                    if event_type in {"PRODUCT_ACTIVATED", "PRODUCT_DEACTIVATED", "PRODUCT_IMAGE_DELETED"}:
                        await self._refresh_all_model_counts(cursor)

    @staticmethod
    async def _insert_processed(cursor: Any, event_id: UUID, event_type: str, event_version: int) -> None:
        await cursor.execute(
            """
            insert into ai_retrieval.processed_events(consumer_name, event_id, aggregate_version)
            values ('visual-search-indexer', %s, %s)
            on conflict (consumer_name, event_id) do nothing
            """,
            (event_id, event_version),
        )

    @staticmethod
    async def _complete_job_item(cursor: Any, event_id: UUID) -> None:
        await cursor.execute(
            """
            update ai_retrieval.indexing_job_items
            set status = 'SUCCEEDED', updated_at = now()
            where event_id = %s and status <> 'SUCCEEDED'
            returning job_id
            """,
            (event_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return
        await VisualSearchRepository._refresh_job(cursor, row[0])

    @staticmethod
    async def _refresh_job(cursor: Any, job_id: UUID) -> None:
        await cursor.execute(
            """
            with counts as (
                select count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'PROCESSING') as processing,
                       count(*) filter (where status = 'SUCCEEDED') as completed,
                       count(*) filter (where status = 'FAILED') as failed
                from ai_retrieval.indexing_job_items where job_id = %s
            )
            update ai_retrieval.indexing_jobs j
            set success_count = counts.completed,
                failed_count = counts.failed,
                status = case
                    when counts.pending = 0 and counts.processing = 0 and counts.failed = 0 then 'COMPLETED'
                    when counts.pending = 0 and counts.processing = 0 and counts.completed > 0 then 'PARTIALLY_COMPLETED'
                    when counts.pending = 0 and counts.processing = 0 then 'FAILED'
                    else 'RUNNING'
                end,
                started_at = coalesce(started_at, now()),
                finished_at = case
                    when counts.pending = 0 and counts.processing = 0 then now()
                    else null
                end,
                updated_at = now()
            from counts where j.id = %s
            """,
            (job_id, job_id),
        )
        await cursor.execute(
            """
            update ai_retrieval.indexing_jobs j
            set source_counts = coalesce((
                    select jsonb_object_agg(source_provider, amount)
                    from (select coalesce(source_provider, 'unknown') source_provider, count(*) amount
                          from ai_retrieval.indexing_job_items where job_id = %s
                          group by coalesce(source_provider, 'unknown')) sources
                ), '{}'::jsonb),
                error_summary = coalesce((
                    select jsonb_object_agg(failure_code, amount)
                    from (select coalesce(failure_code, 'UNKNOWN') failure_code, count(*) amount
                          from ai_retrieval.indexing_job_items
                          where job_id = %s and status = 'FAILED'
                          group by coalesce(failure_code, 'UNKNOWN')) failures
                ), '{}'::jsonb)
            where j.id = %s
            """,
            (job_id, job_id, job_id),
        )

    @staticmethod
    async def _refresh_model_counts(cursor: Any, model_id: UUID) -> None:
        # V2 computes model coverage from immutable vectors instead of caching
        # counters on the model registry row.
        await cursor.execute("select 1 from ai_retrieval.embedding_models where id=%s", (model_id,))

    @staticmethod
    async def _refresh_all_model_counts(cursor: Any) -> None:
        await cursor.execute("select id from ai_retrieval.embedding_models where modality='PRODUCT_IMAGE'")
        for row in await cursor.fetchall():
            await VisualSearchRepository._refresh_model_counts(cursor, row[0])

    async def mark_failed(
        self, image_id: UUID | None, model_id: UUID | None, error: str,
        failure_code: str = "PermanentEventError",
    ) -> None:
        if image_id is None or model_id is None:
            return
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        insert into ai_retrieval.product_image_embeddings
                            (image_id, product_id, model_id, source_public_id, source_asset_version,
                             status, attempts, last_error, failure_code, last_attempt_at)
                        select pi.id, pi.product_id, %s, pi.public_id, pi.asset_version,
                               'FAILED', 1, %s, %s, now()
                        from product_images pi where pi.id = %s
                        on conflict (image_id, model_id) do update
                        set status = 'FAILED', last_error = excluded.last_error,
                            failure_code = excluded.failure_code, updated_at = now()
                        """,
                        (model_id, error[:2000], failure_code[:100], image_id),
                    )
                    await self._refresh_model_counts(cursor, model_id)

    async def mark_retry_pending(self, image_id: UUID, model_id: UUID, error: str) -> None:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        update ai_retrieval.product_image_embeddings
                        set status = 'PENDING', last_error = %s, updated_at = now()
                        where image_id = %s and model_id = %s
                          and status = 'PROCESSING'
                        """,
                        (error[:2000], image_id, model_id),
                    )
                    await self._refresh_model_counts(cursor, model_id)

    async def mark_job_event_failed(self, event_id: UUID, error: str, failure_code: str = "UNKNOWN") -> None:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        update ai_retrieval.indexing_job_items
                        set status = 'FAILED', last_error = %s, failure_code = %s, updated_at = now()
                        where event_id = %s returning job_id
                        """,
                        (error[:2000], failure_code[:100], event_id),
                    )
                    row = await cursor.fetchone()
                    if row is not None:
                        await self._refresh_job(cursor, row[0])

    async def reconciliation_candidates(
        self, model_id: UUID, processing_timeout: timedelta, include_failed: bool = True
    ) -> list[ReconciliationCandidate]:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select pi.id, pi.product_id,
                           case
                               when e.image_id is null then 'MISSING'
                               when e.source_public_id is distinct from pi.public_id
                                 or e.source_asset_version is distinct from pi.asset_version then 'SOURCE_CHANGED'
                               when e.status = 'PROCESSING' then 'PROCESSING_TIMEOUT'
                               when e.status = 'READY' and e.color_signature is null then 'COLOR_MISSING'
                               when e.status = 'FAILED' and e.failure_code = 'RetryableEventError'
                                   then 'FAILED_RETRYABLE'
                               when e.status = 'FAILED' then 'FAILED_PERMANENT'
                               else e.status
                           end as reason
                    from product_images pi
                    join products p on p.id = pi.product_id
                    left join ai_retrieval.product_image_embeddings e
                      on e.image_id = pi.id and e.model_id = %s
                    where p.status = 'ACTIVE'
                      and (
                        e.image_id is null
                        or e.source_public_id is distinct from pi.public_id
                        or e.source_asset_version is distinct from pi.asset_version
                        or (e.status = 'READY' and e.color_signature is null)
                        or e.status = 'PENDING'
                        or e.status = 'STALE'
                        or (%s and e.status = 'FAILED')
                        or (e.status = 'PROCESSING' and e.last_attempt_at < now() - %s::interval)
                      )
                    order by pi.created_at, pi.id
                    """,
                    (model_id, include_failed, processing_timeout),
                )
                return [ReconciliationCandidate(*row) for row in await cursor.fetchall()]

    async def targeted_candidates(
        self, *, image_id: UUID | None = None, product_id: UUID | None = None
    ) -> list[ReconciliationCandidate]:
        if (image_id is None) == (product_id is None):
            raise ValueError("Exactly one of image_id or product_id is required")
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select pi.id, pi.product_id, 'REQUESTED'
                    from product_images pi
                    join products p on p.id = pi.product_id
                    where p.status = 'ACTIVE'
                      and (%s::uuid is null or pi.id = %s)
                      and (%s::uuid is null or pi.product_id = %s)
                    order by pi.created_at, pi.id
                    """,
                    (image_id, image_id, product_id, product_id),
                )
                return [ReconciliationCandidate(*row) for row in await cursor.fetchall()]

    @asynccontextmanager
    async def reconciliation_lock(self):
        connection = await self._connect()
        acquired = False
        try:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "select pg_try_advisory_lock(%s)",
                    (self.RECONCILIATION_ADVISORY_LOCK_ID,),
                )
                acquired = bool((await cursor.fetchone())[0])
            yield acquired
        finally:
            if acquired:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        "select pg_advisory_unlock(%s)",
                        (self.RECONCILIATION_ADVISORY_LOCK_ID,),
                    )
            await connection.close()

    async def has_recent_reconciliation_job(self, interval: timedelta) -> bool:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select exists(
                        select 1 from ai_retrieval.indexing_jobs
                        where job_type = 'RECONCILIATION'
                          and created_at >= now() - %s::interval
                    )
                    """,
                    (interval,),
                )
                return bool((await cursor.fetchone())[0])

    async def create_indexing_job(
        self,
        job_type: str,
        candidates: list[ReconciliationCandidate],
        requested_by: UUID | None = None,
        *,
        audit_action: str | None = None,
        audit_target_type: str | None = None,
        audit_target_id: str | None = None,
    ) -> IndexingJob:
        job_id = uuid4()
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        insert into ai_retrieval.indexing_jobs
                            (id, job_type, status, model_id, requested_by,
                             total_count, success_count, failed_count, source_counts,
                             started_at, finished_at)
                        select %s, %s, %s, id, %s, %s, 0, 0, %s::jsonb,
                               case when %s = 0 then now() else null end,
                               case when %s = 0 then now() else null end
                        from ai_retrieval.embedding_models
                        where modality='PRODUCT_IMAGE' and status = 'ACTIVE'
                        """,
                        (
                            job_id,
                            job_type,
                            "COMPLETED" if not candidates else "PENDING",
                            requested_by,
                            len(candidates),
                            "{}",
                            len(candidates),
                            len(candidates),
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise RepositoryUnavailableError("No active visual embedding model is configured")
                    for candidate in candidates:
                        event_id = uuid4()
                        event_type = "PRODUCT_REINDEX_REQUESTED"
                        payload = {
                            "eventId": str(event_id),
                            "eventType": event_type,
                            "eventVersion": 1,
                            "productId": str(candidate.product_id),
                            "imageId": str(candidate.image_id),
                            "occurredAt": datetime.now(timezone.utc).isoformat(),
                            "traceId": str(job_id),
                        }
                        await cursor.execute(
                            """
                            insert into integration_outbox
                                (id, idempotency_key, event_type, event_version,
                                 aggregate_type, aggregate_id, aggregate_version, payload)
                            values (%s, %s, %s, 1, 'PRODUCT', %s, 1, %s::jsonb)
                            """,
                            (
                                event_id, f"VISUAL_REINDEX:{event_id}", event_type,
                                candidate.product_id, json.dumps(payload),
                            ),
                        )
                        await cursor.execute(
                            """
                            insert into ai_retrieval.indexing_job_items
                                (job_id, entity_type, entity_id, event_id, source_provider)
                            select %s, 'PRODUCT_IMAGE', %s, %s, case
                                when secure_url like 'https://res.cloudinary.com/%%' then 'cloudinary'
                                when secure_url like 'https://cdn.shopify.com/%%' then 'shopify'
                                when secure_url like 'https://%%' then 'unsupported_host'
                                else 'relative_or_non_https' end
                            from product_images where id = %s
                            """,
                            (job_id, candidate.image_id, event_id, candidate.image_id),
                        )
                    if audit_action is not None:
                        await self._insert_admin_audit(
                            cursor,
                            action=audit_action,
                            entity_type="VISUAL_INDEXING_JOB",
                            entity_id=str(job_id),
                            after={
                                "job_type": job_type,
                                "enqueued_count": len(candidates),
                                "target_type": audit_target_type,
                                "target_id": audit_target_id,
                            },
                        )
        return IndexingJob(job_id, job_type, len(candidates))

    async def search(
        self, model_id: UUID, vector: tuple[float, ...], limit: int,
        query_color: tuple[float, ...] = (),
    ) -> list[SearchCandidate]:
        async with await self._connect() as connection:
            return await self.search_on(connection, model_id, vector, limit, query_color)

    @staticmethod
    async def search_on(
        connection: psycopg.AsyncConnection[Any], model_id: UUID, vector: tuple[float, ...], limit: int,
        query_color: tuple[float, ...] = (),
    ) -> list[SearchCandidate]:
        encoded = "[" + ",".join(str(value) for value in vector) + "]"
        image_limit = min(max(limit * 10, 50), 100)
        async with connection.cursor() as cursor:
            await cursor.execute(
                """
                with ranked_images as (
                    select result.product_id, result.image_id, image.secure_url,
                           result.similarity,
                           row_number() over (
                               partition by result.product_id
                               order by result.similarity desc, result.image_id
                           ) as product_rank
                    from ai_retrieval.search_product_images_exact(
                        %s, %s::extensions.vector, %s
                    ) result
                    join public.product_images image on image.id = result.image_id
                )
                select product_id, image_id, secure_url, similarity
                from ranked_images
                where product_rank = 1
                order by similarity desc, product_id, image_id
                limit %s
                """,
                (model_id, encoded, image_limit, limit),
            )
            rows = await cursor.fetchall()
            return [SearchCandidate(row[0], row[1], row[2], float(row[3])) for row in rows]

    async def record_query_usage(
        self,
        model: ModelVersion,
        result: EmbeddingResult,
        latency_ms: int,
        success: bool = True,
        error_code: str | None = None,
    ) -> None:
        async with await self._connect() as connection:
            async with connection.transaction():
                await self.record_query_usage_on(connection, model, result, latency_ms, success, error_code)

    async def record_query_usage_on(
        self, connection: psycopg.AsyncConnection[Any], model: ModelVersion, result: EmbeddingResult,
        latency_ms: int, success: bool = True, error_code: str | None = None,
    ) -> None:
        async with connection.cursor() as cursor:
            await cursor.execute(
                        """
                        insert into ai_retrieval.usage_events
                            (id, model_id, provider, model_name, operation, image_pixels, text_tokens,
                             estimated_cost_usd, latency_ms, success, error_code)
                        values (%s, %s, %s, %s, 'QUERY_EMBEDDING', %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            uuid4(), model.id, model.provider, model.model, result.usage.image_pixels,
                            result.usage.text_tokens, self._estimated_cost(result.usage.image_pixels),
                            latency_ms, success, error_code,
                        ),
            )

    async def coverage_stats(self) -> CoverageStats:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select
                        count(pi.id) as total_active,
                        count(e.image_id) filter (where e.status = 'READY') as ready,
                        count(e.image_id) filter (where e.status = 'PENDING') as pending,
                        count(e.image_id) filter (where e.status = 'PROCESSING') as processing,
                        count(e.image_id) filter (where e.status = 'FAILED') as failed,
                        count(pi.id) filter (where e.image_id is null) as missing
                    from product_images pi
                    join products p on p.id = pi.product_id
                    left join ai_retrieval.product_image_embeddings e
                      on e.image_id = pi.id
                     and e.model_id = (
                           select id from ai_retrieval.embedding_models
                           where modality='PRODUCT_IMAGE' and status = 'ACTIVE' limit 1
                       )
                     and e.product_id = pi.product_id
                     and e.source_public_id = pi.public_id
                     and e.source_asset_version = pi.asset_version
                    where p.status = 'ACTIVE'
                    """
                )
                row = await cursor.fetchone()
                if row is None:
                    return CoverageStats(0, 0, 0, 0, 0, 0, 0.0)
                total, ready, pending, processing, failed, missing = (
                    int(row[0]), int(row[1]), int(row[2]), int(row[3]), int(row[4]), int(row[5])
                )
                pct = round(ready / total * 100, 2) if total > 0 else 0.0
                return CoverageStats(total, ready, pending, processing, failed, missing, pct)

    async def usage_stats(self, days: int = 30) -> list[UsageDayStat]:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select
                        occurred_at::date as day,
                        operation,
                        count(*) as requests,
                        coalesce(sum(image_pixels), 0) as image_pixels,
                        coalesce(sum(text_tokens), 0) as text_tokens,
                        coalesce(sum(estimated_cost_usd), 0) as estimated_cost_usd,
                        count(*) filter (where success) as success_count,
                        count(*) filter (where not success) as failure_count
                    from ai_retrieval.usage_events
                    where occurred_at >= now() - (%s || ' days')::interval
                    group by day, operation
                    order by day desc, operation
                    """,
                    (days,),
                )
                return [
                    UsageDayStat(
                        day=row[0],
                        operation=row[1],
                        requests=int(row[2]),
                        image_pixels=int(row[3]),
                        text_tokens=int(row[4]),
                        estimated_cost_usd=float(row[5]),
                        success_count=int(row[6]),
                        failure_count=int(row[7]),
                    )
                    for row in await cursor.fetchall()
                ]

    async def recent_jobs(self, limit: int = 10) -> list[RecentJob]:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select id, job_type, status, total_count, success_count,
                           failed_count, greatest(total_count-success_count-failed_count, 0),
                           created_at, finished_at, source_counts, error_summary
                    from ai_retrieval.indexing_jobs
                    order by created_at desc
                    limit %s
                    """,
                    (limit,),
                )
                return [
                    RecentJob(
                        id=row[0],
                        job_type=row[1],
                        status=row[2],
                        total_count=int(row[3]),
                        completed_count=int(row[4]),
                        failed_count=int(row[5]),
                        pending_count=int(row[6]),
                        created_at=row[7],
                        completed_at=row[8],
                        source_counts=dict(row[9] or {}),
                        error_summary=dict(row[10] or {}),
                    )
                    for row in await cursor.fetchall()
                ]

    async def operations_stats(self) -> OperationsStats:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select id, provider, model_name, dimensions, model_version, preprocessing_version
                    from ai_retrieval.embedding_models
                    where modality='PRODUCT_IMAGE' and status = 'ACTIVE'
                    limit 1
                    """
                )
                model_row = await cursor.fetchone()
                model = (
                    ModelVersion(
                        model_row[0], model_row[1], model_row[2], int(model_row[3]),
                        model_row[4], model_row[5],
                    )
                    if model_row else None
                )
                await cursor.execute(
                    """
                    select
                        count(*) filter (where status = 'PENDING'),
                        count(*) filter (where status = 'PUBLISHING'),
                        count(*) filter (where status = 'FAILED')
                    from integration_outbox
                    """
                )
                row = await cursor.fetchone()
                return OperationsStats(
                    model=model,
                    outbox_pending=int(row[0]) if row else 0,
                    outbox_publishing=int(row[1]) if row else 0,
                    outbox_failed=int(row[2]) if row else 0,
                )
