from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]
TARGETS = {
    "users": "identity.users", "user_identities": "identity.user_identities",
    "addresses": "identity.addresses", "refresh_tokens": "identity.refresh_tokens",
    "password_reset_tokens": "identity.password_reset_tokens",
    "categories": "catalog.categories", "brands": "catalog.brands", "products": "catalog.products",
    "product_variants": "catalog.product_variants", "product_images": "catalog.product_images",
    "wishlists": "catalog.wishlists", "wishlist_items": "catalog.wishlist_items",
    "product_reviews": "catalog.product_reviews", "promotions": "catalog.promotions",
    "promotion_products": "catalog.promotion_products", "inventory_transactions": "catalog.inventory_transactions",
    "size_systems": "catalog.size_systems", "sizes": "catalog.sizes", "product_types": "catalog.product_types",
    "sports": "catalog.sports", "use_cases": "catalog.use_cases", "materials": "catalog.materials",
    "technologies": "catalog.technologies", "colors": "catalog.colors",
    "catalog_datasets": "catalog.catalog_datasets", "catalog_dataset_items": "catalog.catalog_dataset_items",
    "product_sports": "catalog.product_sports", "product_use_cases": "catalog.product_use_cases",
    "product_materials": "catalog.product_materials", "product_technologies": "catalog.product_technologies",
    "carts": "sales.carts", "cart_items": "sales.cart_items", "orders": "sales.orders",
    "order_items": "sales.order_items", "order_status_history": "sales.order_status_history",
    "payments": "sales.payments", "payment_events": "sales.payment_events", "returns": "sales.returns",
    "return_items": "sales.return_items", "refunds": "sales.refunds", "bank_transactions": "sales.bank_transactions",
    "shipping_methods": "sales.shipping_methods", "shipments": "sales.shipments",
    "notifications": "sales.notifications", "user_interactions": "recommendation.user_interactions",
    "bank_reconciliation_runs": "sales.bank_reconciliation_runs",
    "notification_templates": "sales.notification_templates", "site_settings": "sales.site_settings",
    "audit_logs": "sales.audit_logs",
}
EXPLICIT_DECISIONS = {
    "integration_outbox": "drain_and_retire",
    "flyway_schema_history": "retain_migration_metadata",
}


def quote_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


async def relation_exists(connection: asyncpg.Connection, qualified: str) -> bool:
    return bool(await connection.fetchval("select to_regclass($1) is not null", qualified))


async def columns(connection: asyncpg.Connection, schema: str, table: str) -> list[str]:
    rows = await connection.fetch(
        """select column_name from information_schema.columns
            where table_schema=$1 and table_name=$2 order by ordinal_position""", schema, table
    )
    return [row["column_name"] for row in rows]


async def primary_key(connection: asyncpg.Connection, schema: str, table: str) -> list[str]:
    rows = await connection.fetch(
        """select a.attname from pg_index i join pg_class c on c.oid=i.indrelid
             join pg_namespace n on n.oid=c.relnamespace
             join unnest(i.indkey) with ordinality keys(attnum,ord) on true
             join pg_attribute a on a.attrelid=c.oid and a.attnum=keys.attnum
            where n.nspname=$1 and c.relname=$2 and i.indisprimary order by keys.ord""", schema, table
    )
    return [row["attname"] for row in rows]


async def checksum(connection: asyncpg.Connection, schema: str, table: str, selected: list[str], order: list[str]) -> str:
    if not selected:
        return hashlib.sha256(b"").hexdigest()
    relation = f"{quote_ident(schema)}.{quote_ident(table)}"
    pairs = ",".join(f"'{name}',{quote_ident(name)}" for name in selected)
    ordering = ",".join(quote_ident(name) for name in order) if order else "1"
    query = f"select coalesce(md5(string_agg(md5(jsonb_build_object({pairs})::text),'' order by {ordering})),md5('')) from {relation}"
    return await connection.fetchval(query)


async def inspect(dsn: str) -> dict:
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"), timeout=20)
    try:
        public_rows = await connection.fetch(
            """select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public' and c.relkind in ('r','p')
                order by c.relname"""
        )
        results = []
        for row in public_rows:
            source_table = row["relname"]
            source_count = await connection.fetchval(f"select count(*) from public.{quote_ident(source_table)}")
            target = TARGETS.get(source_table)
            decision = EXPLICIT_DECISIONS.get(source_table)
            item = {"source": f"public.{source_table}", "sourceCount": source_count, "target": target, "decision": decision}
            if target:
                target_schema, target_table = target.split(".", 1)
                if await relation_exists(connection, target):
                    target_count = await connection.fetchval(
                        f"select count(*) from {quote_ident(target_schema)}.{quote_ident(target_table)}"
                    )
                    source_columns = await columns(connection, "public", source_table)
                    target_columns = await columns(connection, target_schema, target_table)
                    shared = [name for name in source_columns if name in target_columns]
                    source_pk = await primary_key(connection, "public", source_table)
                    target_pk = await primary_key(connection, target_schema, target_table)
                    comparable_pk = source_pk if source_pk and source_pk == target_pk else []
                    item.update({
                        "targetExists": True, "targetCount": target_count, "countsMatch": source_count == target_count,
                        "sharedColumnCount": len(shared), "primaryKey": comparable_pk,
                        "sourcePrimaryKeyChecksum": await checksum(connection, "public", source_table, comparable_pk, comparable_pk),
                        "targetPrimaryKeyChecksum": await checksum(connection, target_schema, target_table, comparable_pk, comparable_pk),
                        "sourceSharedChecksum": await checksum(connection, "public", source_table, shared, comparable_pk),
                        "targetSharedChecksum": await checksum(connection, target_schema, target_table, shared, comparable_pk),
                    })
                    item["primaryKeysMatch"] = item["sourcePrimaryKeyChecksum"] == item["targetPrimaryKeyChecksum"]
                    item["sharedDataMatch"] = item["sourceSharedChecksum"] == item["targetSharedChecksum"]
                else:
                    item["targetExists"] = False
            results.append(item)
        unresolved = [item["source"] for item in results if not item["target"] and not item["decision"]]
        mismatched = [item["source"] for item in results if item.get("targetExists") and not (
            item.get("countsMatch") and item.get("primaryKeysMatch") and item.get("sharedDataMatch")
        )]
        missing_targets = [item["source"] for item in results if item.get("target") and not item.get("targetExists")]
        legacy_schema_rows = await connection.fetch(
            """select n.nspname schema_name,c.relname table_name,
                      (xpath('//row/count/text()',query_to_xml(format('select count(*) as count from %I.%I',n.nspname,c.relname),false,true,'')))[1]::text::bigint row_count
                 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='ai_retrieval' and c.relkind in ('r','p') order by c.relname"""
        )
        search_rows = await connection.fetch(
            """select n.nspname schema_name,c.relname table_name,
                      (xpath('//row/count/text()',query_to_xml(format('select count(*) as count from %I.%I',n.nspname,c.relname),false,true,'')))[1]::text::bigint row_count
                 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='ai_search' and c.relkind in ('r','p') order by c.relname"""
        )
        outbox_status = dict(await connection.fetch(
            "select status,count(*)::bigint from public.integration_outbox group by status order by status"
        )) if await relation_exists(connection, "public.integration_outbox") else {}
        active_sessions = await connection.fetchval(
            """select count(*) from pg_stat_activity where datname=current_database()
                and pid<>pg_backend_pid() and state<>'idle'"""
        )
        return {
            "inspectedAt": datetime.now(timezone.utc).isoformat(), "readOnly": True,
            "relations": results, "summary": {
                "publicTableCount": len(results), "mappedTableCount": sum(bool(item["target"]) for item in results),
                "unresolvedTables": unresolved, "missingTargetTables": missing_targets,
                "mismatchedTables": mismatched,
                "readyForBackfill": not unresolved and not missing_targets,
                "readyForCutover": not unresolved and not missing_targets and not mismatched,
            },
            "legacySearchTables": [dict(row) for row in legacy_schema_rows],
            "targetSearchTables": [dict(row) for row in search_rows],
            "legacyOutboxStatus": outbox_status,
            "otherActiveDatabaseSessions": active_sessions,
        }
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only Phase 10 source/target data inventory")
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--database-url")
    parser.add_argument("--evidence")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    dsn = args.database_url or values.get("DB_READ_URL") or values.get("DB_WRITE_URL") or ""
    if not dsn:
        raise SystemExit("database DSN is not configured")
    result = asyncio.run(inspect(dsn))
    output = json.dumps(result, indent=2) + "\n"
    if args.evidence:
        path = ROOT / args.evidence
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
