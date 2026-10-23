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

    def __init__(self, settings: Settings):
        self.database_url = settings.database_url
        self.image_cost_per_megapixel_usd = settings.image_cost_per_megapixel_usd

    def _estimated_cost(self, image_pixels: int) -> float:
        return max(0, image_pixels) / 1_000_000 * self.image_cost_per_megapixel_usd

    async def _connect(self) -> psycopg.AsyncConnection[Any]:
        try:
            return await psycopg.AsyncConnection.connect(self.database_url, connect_timeout=10)
        except OperationalError as exc:
            raise RepositoryUnavailableError("Visual search database is unavailable") from exc

    async def is_processed(self, event_id: UUID) -> bool:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "select exists(select 1 from visual_search.processed_events where event_id = %s)",
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
                       from visual_search.usage_events
                       where occurred_at >= date_trunc('month', now())"""
                )
                return float((await cursor.fetchone())[0])

    async def model_versions(self) -> list[ModelVersionStats]:
        async with await self._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    select m.id, m.provider, m.model, m.dimensions, m.status,
                           (select count(*) from product_images pi join products p on p.id=pi.product_id
                            where p.status='ACTIVE')::int,
                           (select count(*) from visual_search.image_embeddings e
                            join products p on p.id=e.product_id
                            where e.model_version_id=m.id and e.status='READY' and p.status='ACTIVE')::int,
                           (select count(*) from visual_search.image_embeddings e
                            join products p on p.id=e.product_id
                            where e.model_version_id=m.id and e.status='FAILED' and p.status='ACTIVE')::int,
                           m.activated_at, m.revision
                    from visual_search.model_versions m order by m.created_at desc
                    """
                )
                return [ModelVersionStats(*row) for row in await cursor.fetchall()]

    async def create_model_version(self, provider: str, model: str, dimensions: int) -> ModelVersionStats:
        if dimensions <= 0:
            raise ValueError("Model dimensions must be positive")
        model_id = uuid4()
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        insert into visual_search.model_versions
                            (id, provider, model, dimensions, revision, status)
                        select %s, %s, %s, %s, coalesce(max(revision), 0) + 1, 'BUILDING'
                        from visual_search.model_versions
                        where provider = %s and model = %s
                        """,
                        (model_id, provider, model, dimensions, provider, model),
                    )
        versions = await self.model_versions()
        return next(version for version in versions if version.id == model_id)

    async def activate_model_version(self, model_id: UUID) -> ModelVersionStats:
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        "select status from visual_search.model_versions where id=%s for update", (model_id,)
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise ValueError("Model version not found")
                    await cursor.execute(
                        """select count(*)::int,
                                  count(*) filter(where e.status='READY')::int
                           from product_images pi join products p on p.id=pi.product_id
                           left join visual_search.image_embeddings e
                             on e.image_id=pi.id and e.model_version_id=%s
                           where p.status='ACTIVE'""", (model_id,)
                    )
                    total, ready = await cursor.fetchone()
                    if total == 0 or ready / total < 0.98:
                        raise ValueError(f"Model coverage {ready}/{total} is below the 98% activation gate")
                    await cursor.execute(
                        "update visual_search.model_versions set status='INACTIVE', updated_at=now() where status='ACTIVE' and id<>%s",
                        (model_id,),
                    )
                    await cursor.execute(
                        """update visual_search.model_versions
                           set status='ACTIVE', target_image_count=%s, ready_image_count=%s,
                               activated_at=now(), updated_at=now() where id=%s""",
                        (total, ready, model_id),
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
                    select pi.id, pi.product_id, pi.image_url, pi.public_id, p.status
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
                    "select status, image_hash, color_signature from visual_search.image_embeddings where image_id = %s and model_version_id = %s",
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
                        "update visual_search.image_embeddings set color_signature=%s, updated_at=now() where image_id=%s and model_version_id=%s",
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
                        insert into visual_search.image_embeddings
                            (image_id, product_id, model_version_id, status, attempts, last_attempt_at)
                        values (%s, %s, %s, 'PROCESSING', 1, now())
                        on conflict (image_id, model_version_id) do update
                        set status = 'PROCESSING', attempts = visual_search.image_embeddings.attempts + 1,
                            last_attempt_at = now(), last_error = null, failure_code = null, updated_at = now()
                        where visual_search.image_embeddings.status <> 'PROCESSING'
                           or visual_search.image_embeddings.last_attempt_at < now() - interval '15 minutes'
                        returning image_id
                        """,
                        (image.id, image.product_id, model_id),
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
                        update visual_search.image_embeddings
                        set embedding = %s::vector, image_hash = %s, status = 'READY', last_error = null,
                            failure_code = null, color_signature = %s,
                            ready_at = now(), updated_at = now()
                        where image_id = %s and model_version_id = %s
                        """,
                        (vector, image_hash, list(color_signature), image.id, model.id),
                    )
                    await cursor.execute(
                        """
                        insert into visual_search.usage_events
                            (id, provider, model, operation, image_pixels, text_tokens,
                             estimated_cost_usd, latency_ms, success)
                        values (%s, %s, %s, 'DOCUMENT_EMBEDDING', %s, %s, %s, %s, true)
                        """,
                        (uuid4(), model.provider, model.model, result.usage.image_pixels,
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
            insert into visual_search.processed_events(event_id, event_type, event_version)
            values (%s, %s, %s) on conflict (event_id) do nothing
            """,
            (event_id, event_type, event_version),
        )

    @staticmethod
    async def _complete_job_item(cursor: Any, event_id: UUID) -> None:
        await cursor.execute(
            """
            update visual_search.indexing_job_items
            set status = 'COMPLETED', completed_at = now(), updated_at = now()
            where event_id = %s and status <> 'COMPLETED'
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
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status = 'FAILED') as failed
                from visual_search.indexing_job_items where job_id = %s
            )
            update visual_search.indexing_jobs j
            set pending_count = counts.pending,
                processing_count = counts.processing,
                completed_count = counts.completed,
                failed_count = counts.failed,
                status = case
                    when counts.pending = 0 and counts.processing = 0 and counts.failed = 0 then 'COMPLETED'
                    when counts.pending = 0 and counts.processing = 0 then 'PARTIAL'
                    else 'RUNNING'
                end,
                started_at = coalesce(started_at, now()),
                completed_at = case
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
            update visual_search.indexing_jobs j
            set source_counts = coalesce((
                    select jsonb_object_agg(source_provider, amount)
                    from (select coalesce(source_provider, 'unknown') source_provider, count(*) amount
                          from visual_search.indexing_job_items where job_id = %s
                          group by coalesce(source_provider, 'unknown')) sources
                ), '{}'::jsonb),
                error_summary = coalesce((
                    select jsonb_object_agg(failure_code, amount)
                    from (select coalesce(failure_code, 'UNKNOWN') failure_code, count(*) amount
                          from visual_search.indexing_job_items
                          where job_id = %s and status = 'FAILED'
                          group by coalesce(failure_code, 'UNKNOWN')) failures
                ), '{}'::jsonb)
            where j.id = %s
            """,
            (job_id, job_id, job_id),
        )

    @staticmethod
    async def _refresh_model_counts(cursor: Any, model_id: UUID) -> None:
        await cursor.execute(
            """
            update visual_search.model_versions m
            set target_image_count = stats.target_count,
                ready_image_count = stats.ready_count,
                failed_image_count = stats.failed_count,
                updated_at = now()
            from (
                select count(pi.id)::int target_count,
                       count(e.image_id) filter(where e.status='READY')::int ready_count,
                       count(e.image_id) filter(where e.status='FAILED')::int failed_count
                from products p join product_images pi on pi.product_id=p.id
                left join visual_search.image_embeddings e
                  on e.image_id=pi.id and e.model_version_id=%s
                where p.status='ACTIVE'
            ) stats where m.id=%s
            """,
            (model_id, model_id),
        )

    @staticmethod
    async def _refresh_all_model_counts(cursor: Any) -> None:
        await cursor.execute("select id from visual_search.model_versions")
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
                        insert into visual_search.image_embeddings
                            (image_id, product_id, model_version_id, status, attempts, last_error,
                             failure_code, last_attempt_at)
                        select pi.id, pi.product_id, %s, 'FAILED', 1, %s, %s, now()
                        from product_images pi where pi.id = %s
                        on conflict (image_id, model_version_id) do update
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
                        update visual_search.image_embeddings
                        set status = 'PENDING', last_error = %s, updated_at = now()
                        where image_id = %s and model_version_id = %s
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
                        update visual_search.indexing_job_items
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
                               when e.status = 'PROCESSING' then 'PROCESSING_TIMEOUT'
                               when e.status = 'READY' and e.color_signature is null then 'COLOR_MISSING'
                               when e.status = 'FAILED' and e.failure_code = 'RetryableEventError'
                                   then 'FAILED_RETRYABLE'
                               when e.status = 'FAILED' then 'FAILED_PERMANENT'
                               else e.status
                           end as reason
                    from product_images pi
                    join products p on p.id = pi.product_id
                    left join visual_search.image_embeddings e
                      on e.image_id = pi.id and e.model_version_id = %s
                    where p.status = 'ACTIVE'
                      and (
                        e.image_id is null
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
                        select 1 from visual_search.indexing_jobs
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
    ) -> IndexingJob:
        job_id = uuid4()
        async with await self._connect() as connection:
            async with connection.transaction():
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        insert into visual_search.indexing_jobs
                            (id, job_type, status, model_version_id, requested_by,
                             total_count, pending_count, source_counts, completed_at)
                        select %s, %s, %s, id, %s, %s, %s, %s::jsonb,
                               case when %s = 0 then now() else null end
                        from visual_search.model_versions where status = 'ACTIVE'
                        """,
                        (
                            job_id,
                            job_type,
                            "COMPLETED" if not candidates else "PENDING",
                            requested_by,
                            len(candidates),
                            len(candidates),
                            "{}",
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
                                (id, event_type, event_version, aggregate_type, aggregate_id, payload)
                            values (%s, %s, 1, 'PRODUCT', %s, %s::jsonb)
                            """,
                            (event_id, event_type, candidate.product_id, json.dumps(payload)),
                        )
                        await cursor.execute(
                            """
                            insert into visual_search.indexing_job_items
                                (job_id, image_id, event_id, source_provider)
                            select %s, %s, %s, case
                                when image_url like 'https://res.cloudinary.com/%%' then 'cloudinary'
                                when image_url like 'https://cdn.shopify.com/%%' then 'shopify'
                                when image_url like 'https://%%' then 'unsupported_host'
                                else 'relative_or_non_https' end
                            from product_images where id = %s
                            """,
                            (job_id, candidate.image_id, event_id, candidate.image_id),
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
                        insert into visual_search.usage_events
                            (id, provider, model, operation, image_pixels, text_tokens,
                             estimated_cost_usd, latency_ms, success, error_code)
                        values (%s, %s, %s, 'QUERY_EMBEDDING', %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            uuid4(), model.provider, model.model, result.usage.image_pixels,
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
                    left join visual_search.image_embeddings e
                      on e.image_id = pi.id
                     and e.model_version_id = (
                           select id from visual_search.model_versions where status = 'ACTIVE' limit 1
                       )
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
                    from visual_search.usage_events
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
                    select id, job_type, status, total_count, completed_count,
                           failed_count, pending_count, created_at, completed_at,
                           source_counts, error_summary
                    from visual_search.indexing_jobs
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
                    select id, provider, model, dimensions
                    from visual_search.model_versions
                    where status = 'ACTIVE'
                    limit 1
                    """
                )
                model_row = await cursor.fetchone()
                model = (
                    ModelVersion(model_row[0], model_row[1], model_row[2], int(model_row[3]))
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
