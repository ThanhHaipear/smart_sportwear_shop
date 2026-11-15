from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (
    ROOT
    / "catalog-service/src/main/resources/db/migration/V5__align_catalog_search_events.sql"
)
LEGACY_EVENT_TYPES = (
    "catalog.inventory.changed.v1",
    "catalog.product.changed.v1",
)


async def run(database_url: str, apply: bool) -> dict:
    connection = await asyncpg.connect(
        database_url.replace("postgresql+asyncpg://", "postgresql://"),
        timeout=20,
    )
    transaction = connection.transaction()
    await transaction.start()
    finished = False
    try:
        before = {
            row["event_type"]: row["count"]
            for row in await connection.fetch(
                """select event_type,count(*)::integer count
                     from catalog.integration_outbox
                    where status='PENDING' and event_type=any($1::text[])
                    group by event_type order by event_type""",
                list(LEGACY_EVENT_TYPES),
            )
        }
        await connection.execute(MIGRATION.read_text(encoding="utf-8"))
        definitions = {
            row["proname"]: row["definition"]
            for row in await connection.fetch(
                """select p.proname,pg_get_functiondef(p.oid) definition
                     from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                    where n.nspname='catalog'
                      and p.proname=any($1::text[])
                    order by p.proname""",
                ["emit_inventory_changed", "emit_product_changed"],
            )
        }
        required_fragments = (
            "product.reindex.requested",
            "PRODUCT_REINDEX_REQUESTED",
            "eventVersion",
            "productId",
            "sourceVersion",
            "traceId",
        )
        if set(definitions) != {"emit_inventory_changed", "emit_product_changed"}:
            raise RuntimeError("Catalog event functions were not installed")
        if any(
            fragment not in definition
            for definition in definitions.values()
            for fragment in required_fragments
        ):
            raise RuntimeError("Catalog event function does not match catalog-event-v1")

        superseded = await connection.fetchval(
            """with changed as (
                   update catalog.integration_outbox
                      set status='SUPERSEDED',next_attempt_at=null
                    where status='PENDING' and event_type=any($1::text[])
                    returning id
               ) select count(*)::integer from changed""",
            list(LEGACY_EVENT_TYPES),
        )
        if superseded != sum(before.values()):
            raise RuntimeError("Legacy outbox supersede count changed during migration")

        if apply:
            await transaction.commit()
        else:
            await transaction.rollback()
        finished = True
        return {
            "checkedAt": datetime.now(timezone.utc).isoformat(),
            "applied": apply,
            "migration": str(MIGRATION.relative_to(ROOT)).replace("\\", "/"),
            "legacyPendingBefore": before,
            "legacyEventsSuperseded": superseded,
            "functionsValidated": sorted(definitions),
            "requiredFragmentsValidated": list(required_fragments),
            "secretsPrinted": False,
            "rollback": "Restore the Catalog schema backup or set the preserved SUPERSEDED rows back to PENDING only after deploying a compatible legacy consumer.",
        }
    except BaseException:
        if not finished:
            await transaction.rollback()
        raise
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Apply the HAI-11 Catalog event contract migration")
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--database-url")
    parser.add_argument("--apply", action="store_true", help="Commit; default is rollback")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    database_url = args.database_url or values.get("DB_WRITE_URL") or ""
    if not database_url:
        raise SystemExit("Admin DB_WRITE_URL is not configured")
    print(json.dumps(asyncio.run(run(database_url, args.apply)), indent=2))


if __name__ == "__main__":
    main()
