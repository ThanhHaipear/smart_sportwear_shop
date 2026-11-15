import argparse
import asyncio
import json
from pathlib import Path
from uuid import UUID

import httpx

from app.catalog.client import CatalogClient
from app.config import Settings, get_settings
from app.services.indexer import CatalogIndexer


async def inspect(ids: list[UUID], catalog: CatalogClient) -> dict:
    report = {
        "mode": "dry-run",
        "requestedProducts": len(ids),
        "foundProducts": 0,
        "missingProducts": 0,
        "variants": 0,
        "images": 0,
        "statuses": {},
        "errors": [],
    }
    for product_id in ids:
        try:
            document = await catalog.index_document(product_id)
            if document is None:
                report["missingProducts"] += 1
                continue
            report["foundProducts"] += 1
            report["variants"] += len(document.variants)
            report["images"] += len(document.images)
            status = document.status
            report["statuses"][status] = report["statuses"].get(status, 0) + 1
        except Exception as error:
            report["errors"].append({"productId": str(product_id), "errorType": type(error).__name__})
    report["passed"] = (
        report["foundProducts"] == report["requestedProducts"]
        and report["missingProducts"] == 0
        and not report["errors"]
    )
    return report


async def active_product_ids(settings: Settings, client: httpx.AsyncClient | None = None) -> list[UUID]:
    page = 1
    product_ids: list[UUID] = []
    owns_client = client is None
    if client is None:
        client = httpx.AsyncClient(timeout=settings.catalog_request_timeout_seconds)
    try:
        while True:
            response = await client.get(
                f"{settings.catalog_api_url.rstrip('/')}/api/v1/products",
                params={"page": page, "limit": 100},
            )
            response.raise_for_status()
            payload = response.json()
            product_ids.extend(UUID(item["id"]) for item in payload.get("data", []))
            meta = payload.get("meta") or {}
            if page >= int(meta.get("totalPages", page)):
                return product_ids
            page += 1
    finally:
        if owns_client:
            await client.aclose()


async def run(ids: list[UUID], execute: bool, force: bool = False) -> None:
    settings = get_settings()
    if not execute:
        report = await inspect(ids, CatalogClient(settings))
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    print(json.dumps({"mode": "execute", "products": len(ids), "force": force}))
    indexer = CatalogIndexer(settings)
    counts: dict[str, int] = {}
    for product_id in ids:
        outcome = await indexer.index(product_id, 0, force=force)
        counts[outcome] = counts.get(outcome, 0) + 1
    print(json.dumps({"processed": len(ids), "outcomes": counts}, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill ai_search through the Catalog API")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--ids", type=Path, help="One product UUID per line")
    source.add_argument(
        "--all-active",
        action="store_true",
        help="Discover active product IDs through the public Catalog API",
    )
    parser.add_argument("--execute", action="store_true")
    parser.add_argument(
        "--force",
        action="store_true",
        help="Recompute and write every selected document; requires --execute",
    )
    args = parser.parse_args()
    if args.force and not args.execute:
        parser.error("--force requires --execute")

    async def run_from_args() -> None:
        if args.all_active:
            ids = await active_product_ids(get_settings())
        else:
            ids = [UUID(line.strip()) for line in args.ids.read_text(encoding="utf-8").splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
        await run(ids, args.execute, args.force)

    asyncio.run(run_from_args())


if __name__ == "__main__":
    main()
