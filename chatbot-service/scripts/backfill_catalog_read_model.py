import asyncio
import json
import sys
from pathlib import Path
from uuid import uuid4

import asyncpg

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config.settings import settings
from app.indexing.worker import ProductIndexingWorker
from app.indexing.indexer import index_product


async def verify_guards(pool: asyncpg.Pool) -> dict[str, bool]:
    async with pool.acquire() as conn:
        transaction = conn.transaction()
        await transaction.start()
        try:
            row = await conn.fetchrow(
                """
                select product_id, name, source_version
                  from ai_retrieval.product_search_documents
                 where status = 'ACTIVE'
                 order by product_id limit 1
                """
            )
            version_guard = True
            if row is not None:
                marker = "__STALE_EVENT_MUST_NOT_OVERWRITE__"
                await conn.execute(
                    """
                    update ai_retrieval.product_search_documents
                       set name = $2, source_version = $3
                     where product_id = $1
                    """,
                    row["product_id"], marker, row["source_version"] + 1,
                )
                result = await index_product(
                    conn, row["product_id"], max(0, row["source_version"] - 1)
                )
                current_name = await conn.fetchval(
                    """select name from ai_retrieval.product_search_documents
                        where product_id = $1""",
                    row["product_id"],
                )
                version_guard = result.outcome == "SKIPPED_STALE_OR_CURRENT" and current_name == marker

            event_id = uuid4()
            first = await conn.fetchval(
                """
                insert into ai_retrieval.processed_events(
                    consumer_name,event_id,aggregate_version
                ) values('catalog-indexer-verification', $1, 1)
                on conflict(consumer_name,event_id) do nothing returning true
                """,
                event_id,
            )
            duplicate = await conn.fetchval(
                """
                insert into ai_retrieval.processed_events(
                    consumer_name,event_id,aggregate_version
                ) values('catalog-indexer-verification', $1, 1)
                on conflict(consumer_name,event_id) do nothing returning true
                """,
                event_id,
            )
            return {"stale_event_guard": version_guard,
                    "duplicate_event_guard": bool(first) and duplicate is None}
        finally:
            await transaction.rollback()


async def main() -> None:
    if not settings.DB_WRITE_URL:
        raise RuntimeError("DB_WRITE_URL is required")
    dsn = settings.DB_WRITE_URL.replace("postgresql+asyncpg://", "postgresql://", 1)
    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=3)
    try:
        result = await ProductIndexingWorker(pool).backfill_active_catalog()
        async with pool.acquire() as conn:
            verification = await conn.fetchrow(
                """
                select (select count(*) from products where status = 'ACTIVE') as active_products,
                       (select count(*) from ai_retrieval.product_search_documents
                         where status = 'ACTIVE') as active_documents,
                       (select count(*) from ai_retrieval.search_variant_facets) as variant_facets,
                       (select count(*) from ai_retrieval.product_search_documents d
                         join products p on p.id = d.product_id
                        where d.source_version > p.catalog_version) as future_versions
                """
            )
        result["verification"] = dict(verification)
        result["guards"] = await verify_guards(pool)
        print(json.dumps(result, ensure_ascii=False))
    finally:
        await pool.close()


if __name__ == "__main__":
    asyncio.run(main())
