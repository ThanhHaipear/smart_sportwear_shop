from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = (
    ROOT / "catalog-service/src/main/resources/db/migration/V3__complete_legacy_catalog_ownership.sql",
    ROOT / "sales-service/src/main/resources/db/migration/V3__complete_legacy_sales_ownership.sql",
    ROOT / "services/search-service/migrations/V2__backfill_legacy_search_read_model.sql",
)
COUNT_PAIRS = (
    ("public.inventory_transactions", "catalog.inventory_transactions"),
    ("public.size_systems", "catalog.size_systems"), ("public.sizes", "catalog.sizes"),
    ("public.product_types", "catalog.product_types"), ("public.sports", "catalog.sports"),
    ("public.use_cases", "catalog.use_cases"), ("public.materials", "catalog.materials"),
    ("public.technologies", "catalog.technologies"), ("public.colors", "catalog.colors"),
    ("public.catalog_datasets", "catalog.catalog_datasets"),
    ("public.catalog_dataset_items", "catalog.catalog_dataset_items"),
    ("public.product_sports", "catalog.product_sports"),
    ("public.product_use_cases", "catalog.product_use_cases"),
    ("public.product_materials", "catalog.product_materials"),
    ("public.product_technologies", "catalog.product_technologies"),
    ("public.bank_reconciliation_runs", "sales.bank_reconciliation_runs"),
    ("public.notification_templates", "sales.notification_templates"),
    ("public.site_settings", "sales.site_settings"), ("public.audit_logs", "sales.audit_logs"),
    ("ai_retrieval.product_search_documents", "ai_search.search_documents"),
    ("ai_retrieval.search_variant_facets", "ai_search.variant_facets"),
    ("ai_retrieval.product_image_embeddings", "ai_search.image_embeddings"),
)


async def apply(dsn: str, commit: bool) -> dict:
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"), timeout=20)
    transaction = connection.transaction()
    await transaction.start()
    finished = False
    try:
        for migration in MIGRATIONS:
            await connection.execute(migration.read_text("utf-8"))
        counts = []
        failures = []
        for source, target in COUNT_PAIRS:
            source_count = await connection.fetchval(f"select count(*) from {source}")
            target_count = await connection.fetchval(f"select count(*) from {target}")
            # Only READY, non-null, one-per-image legacy vectors are eligible for the target image index.
            if source == "ai_retrieval.product_image_embeddings":
                source_count = await connection.fetchval(
                    """select count(distinct image_id) from ai_retrieval.product_image_embeddings
                        where status='READY' and embedding is not null and image_hash is not null"""
                )
            match = source_count == target_count
            counts.append({"source": source, "target": target, "sourceCount": source_count,
                           "targetCount": target_count, "match": match})
            if not match:
                failures.append(f"{source}={source_count}, {target}={target_count}")
        if failures:
            raise RuntimeError("Phase 10 backfill count mismatch: " + "; ".join(failures))
        if commit:
            await transaction.commit()
        else:
            await transaction.rollback()
        finished = True
        return {
            "appliedAt": datetime.now(timezone.utc).isoformat(), "committed": commit,
            "migrations": [str(path.relative_to(ROOT)).replace("\\", "/") for path in MIGRATIONS],
            "counts": counts, "allCountsMatch": True, "secretsPrinted": False,
        }
    except BaseException:
        if not finished:
            await transaction.rollback()
        raise
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Transactional Phase 10 ownership backfill")
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--database-url")
    parser.add_argument("--apply", action="store_true", help="commit; without this flag the transaction rolls back")
    parser.add_argument("--evidence")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    dsn = args.database_url or values.get("DB_WRITE_URL") or ""
    if not dsn:
        raise SystemExit("admin write DSN is not configured")
    result = asyncio.run(apply(dsn, args.apply))
    output = json.dumps(result, indent=2) + "\n"
    if args.evidence:
        path = ROOT / args.evidence
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
