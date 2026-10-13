from __future__ import annotations

import asyncio
import json
import logging
from uuid import UUID

import asyncpg

from app.config.settings import settings
from app.indexing.consumer import ProductIndexingConsumer
from app.indexing.events import CatalogEvent
from app.indexing.indexer import index_product

logger = logging.getLogger(__name__)
CONSUMER_NAME = "catalog-read-model-v1"
BACKFILL_JOB_TYPE = "CATALOG_READ_MODEL_BACKFILL"


class ProductIndexingWorker:
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def handle(self, event: CatalogEvent) -> str:
        """Consume one outbox event atomically with its idempotency marker."""
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                inserted = await conn.fetchval(
                    """
                    insert into ai_retrieval.processed_events(
                        consumer_name, event_id, aggregate_version
                    ) values($1,$2,$3)
                    on conflict(consumer_name,event_id) do nothing
                    returning true
                    """,
                    CONSUMER_NAME,
                    event.eventId,
                    event.sourceVersion,
                )
                if not inserted:
                    return "DUPLICATE"
                result = await index_product(conn, event.productId, event.sourceVersion)
                return result.outcome

    async def create_backfill_job(self) -> UUID:
        """Snapshot ACTIVE product ids and create a durable, inspectable batch."""
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                job_id = await conn.fetchval(
                    """
                    insert into ai_retrieval.indexing_jobs(
                        job_type, status, total_count, started_at
                    ) values($1,'RUNNING',0,now())
                    returning id
                    """,
                    BACKFILL_JOB_TYPE,
                )
                await conn.execute(
                    """
                    insert into ai_retrieval.indexing_job_items(
                        job_id, entity_type, entity_id, status
                    )
                    select $1, 'PRODUCT', p.id, 'PENDING'
                      from products p
                     where p.status = 'ACTIVE'
                    on conflict(job_id,entity_type,entity_id) do nothing
                    """,
                    job_id,
                )
                await conn.execute(
                    """
                    update ai_retrieval.indexing_jobs
                       set total_count = (
                           select count(*) from ai_retrieval.indexing_job_items where job_id = $1
                       )
                     where id = $1
                    """,
                    job_id,
                )
                return job_id

    async def _claim_item(self, job_id: UUID):
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                return await conn.fetchrow(
                    """
                    with candidate as (
                        select id
                          from ai_retrieval.indexing_job_items
                         where job_id = $1
                           and status in ('PENDING','FAILED')
                           and attempts < $2
                         order by updated_at, id
                         for update skip locked
                         limit 1
                    )
                    update ai_retrieval.indexing_job_items item
                       set status = 'PROCESSING', attempts = attempts + 1, last_error = null
                      from candidate
                     where item.id = candidate.id
                    returning item.id, item.entity_id, item.attempts
                    """,
                    job_id,
                    settings.CATALOG_INDEXING_MAX_ITEM_ATTEMPTS,
                )

    async def _process_item(self, item) -> None:
        async with self.pool.acquire() as conn:
            try:
                async with conn.transaction():
                    result = await index_product(conn, item["entity_id"])
                    status = "SKIPPED" if result.outcome.startswith("SKIPPED") else "SUCCEEDED"
                    await conn.execute(
                        """
                        update ai_retrieval.indexing_job_items
                           set status = $2, last_error = null
                         where id = $1
                        """,
                        item["id"],
                        status,
                    )
            except Exception as exc:
                message = f"{type(exc).__name__}: {exc}"[:4000]
                async with conn.transaction():
                    await conn.execute(
                        """
                        update ai_retrieval.indexing_job_items
                           set status = 'FAILED', last_error = $2
                         where id = $1
                        """,
                        item["id"],
                        message,
                    )
                logger.warning("Catalog backfill item %s failed: %s", item["id"], type(exc).__name__)

    async def run_backfill_job(self, job_id: UUID) -> dict[str, int | str]:
        while item := await self._claim_item(job_id):
            await self._process_item(item)

        async with self.pool.acquire() as conn:
            async with conn.transaction():
                counts = await conn.fetchrow(
                    """
                    select count(*) filter (where status in ('SUCCEEDED','SKIPPED')) as success_count,
                           count(*) filter (where status = 'FAILED') as failed_count,
                           count(*) filter (where status = 'SKIPPED') as skipped_count,
                           count(*) as total_count
                      from ai_retrieval.indexing_job_items
                     where job_id = $1
                    """,
                    job_id,
                )
                total = int(counts["total_count"])
                success = int(counts["success_count"])
                failed = int(counts["failed_count"])
                skipped = int(counts["skipped_count"])
                if failed == 0:
                    status = "COMPLETED"
                elif success == 0:
                    status = "FAILED"
                else:
                    status = "PARTIALLY_COMPLETED"
                error_summary = None if failed == 0 else json.dumps({"failedItems": failed})
                await conn.execute(
                    """
                    update ai_retrieval.indexing_jobs
                       set status = $2, total_count = $3, success_count = $4,
                           failed_count = $5, finished_at = now(),
                           error_summary = $6::jsonb
                     where id = $1
                    """,
                    job_id, status, total, success, failed, error_summary,
                )
                return {"job_id": str(job_id), "status": status, "total": total,
                        "success": success, "skipped": skipped, "failed": failed}

    async def backfill_active_catalog(self) -> dict[str, int | str]:
        return await self.run_backfill_job(await self.create_backfill_job())

    async def reconcile_once(self) -> int:
        result = await self.backfill_active_catalog()
        return int(result["total"])


async def run_worker() -> None:
    if not settings.PRODUCT_SEARCH_INDEXING_ENABLED:
        raise RuntimeError("PRODUCT_SEARCH_INDEXING_ENABLED must be true")
    if not settings.DB_WRITE_URL:
        raise RuntimeError("DB_WRITE_URL is required by the indexing worker")
    dsn = settings.DB_WRITE_URL.replace("postgresql+asyncpg://", "postgresql://", 1)
    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=3)
    worker = ProductIndexingWorker(pool)
    consumer = ProductIndexingConsumer(settings, worker.handle)
    try:
        if settings.CATALOG_INDEX_BACKFILL_ON_STARTUP:
            result = await worker.backfill_active_catalog()
            logger.info("Catalog backfill completed: %s", result)
        await consumer.run()
        while True:
            await asyncio.sleep(settings.PRODUCT_SEARCH_RECONCILIATION_INTERVAL_SECONDS)
            try:
                await worker.reconcile_once()
            except Exception:
                logger.exception("Catalog read-model reconciliation failed")
    finally:
        await consumer.close()
        await pool.close()
