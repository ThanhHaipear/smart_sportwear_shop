"""Repair recommendation read models from Catalog.

1. Load product snapshots for every ACTIVE catalog product plus every product already stored.
2. Resolve stored order baskets from variant IDs to product IDs.
3. Recompute `catalog_products.purchase_count` from the repaired baskets.

Dry-run by default: everything is computed in memory and only a report is printed.
Pass --execute to write the result to schema `recommendation`.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from uuid import UUID

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.catalog import HttpCatalogClient  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.enrichment import EventEnricher  # noqa: E402
from app.repository import MemoryRepository, PostgresRepository  # noqa: E402
from app.schemas import ProductSnapshot  # noqa: E402


def active_catalog_product_ids(base_url: str, timeout: float) -> list[UUID]:
    ids: list[UUID] = []
    page = 1
    with httpx.Client(timeout=timeout) as client:
        while True:
            response = client.get(f"{base_url.rstrip('/')}/api/v1/products", params={"page": page, "limit": 100})
            response.raise_for_status()
            rows = response.json().get("data") or []
            ids.extend(UUID(str(row["id"])) for row in rows)
            if len(rows) < 100:
                return ids
            page += 1


def stored_product_ids(repository: PostgresRepository) -> list[UUID]:
    with repository.connection() as connection:
        return [row[0] for row in connection.execute("select product_id from catalog_products").fetchall()]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--execute", action="store_true", help="write the repaired read models")
    args = parser.parse_args()

    settings = get_settings()
    if not settings.recommendation_database_url:
        parser.error("RECOMMENDATION_DATABASE_URL is not set")
    database = PostgresRepository(settings.recommendation_database_url)
    catalog = HttpCatalogClient(settings)
    memory = MemoryRepository()
    enricher = EventEnricher(catalog, memory)

    product_ids = list(dict.fromkeys(active_catalog_product_ids(settings.catalog_api_url,
                                                                 settings.catalog_request_timeout_seconds)
                                     + stored_product_ids(database)))
    tombstones: list[UUID] = []
    for product_id in product_ids:
        fetched = enricher.fetch_snapshot(product_id)
        if fetched is None:
            tombstones.append(product_id)
            memory.upsert_product(ProductSnapshot(id=product_id, name="", slug=str(product_id),
                                                  availableQuantity=0, status="DELETED"), 0)
        else:
            memory.upsert_product(*fetched)

    original_baskets = database.basket_rows()
    changed_baskets: list[tuple[UUID, list[UUID]]] = []
    for order_id, items in original_baskets:
        resolved = enricher.resolve_products(items)
        memory.order_baskets[order_id] = set(resolved)
        if set(resolved) != set(items):
            changed_baskets.append((order_id, resolved))
    memory.recompute_purchase_counts()

    recommendable = [p for p in memory.product_by_id.values() if p.status == "ACTIVE" and p.availableQuantity > 0]
    report = {
        "mode": "EXECUTE" if args.execute else "DRY_RUN",
        "products": len(memory.product_by_id),
        "recommendableProducts": len(recommendable),
        "tombstones": len(tombstones),
        "baskets": len(original_baskets),
        "basketsToRepair": len(changed_baskets),
        "emptyBasketsAfterRepair": sum(1 for _, items in changed_baskets if not items),
        "productsWithPurchases": len(memory.popularity),
        "topPopular": sorted(((memory.product_by_id[p].name, n) for p, n in memory.popularity.items()),
                             key=lambda row: -row[1])[:5],
    }

    if args.execute:
        for product in memory.product_by_id.values():
            database.upsert_product(product, memory.versions.get(product.id, 0))
        for order_id, items in changed_baskets:
            if items:
                database.replace_basket(order_id, items)
        report["purchaseCountsUpdated"] = database.recompute_purchase_counts()
        report["note"] = "Baskets with no resolvable product were left unchanged (product_ids must be non-empty)."
    print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
