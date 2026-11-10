import argparse
import asyncio
from pathlib import Path
from uuid import UUID

from app.config import get_settings
from app.services.indexer import CatalogIndexer


async def run(ids: list[UUID], execute: bool) -> None:
    print({"mode": "execute" if execute else "dry-run", "products": len(ids)})
    if not execute:
        return
    indexer = CatalogIndexer(get_settings())
    counts: dict[str, int] = {}
    for product_id in ids:
        outcome = await indexer.index(product_id, 0)
        counts[outcome] = counts.get(outcome, 0) + 1
    print({"processed": len(ids), "outcomes": counts})


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill ai_search through the Catalog API")
    parser.add_argument("--ids", required=True, type=Path, help="One product UUID per line")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    ids = [UUID(line.strip()) for line in args.ids.read_text(encoding="utf-8").splitlines()
           if line.strip() and not line.lstrip().startswith("#")]
    asyncio.run(run(ids, args.execute))


if __name__ == "__main__":
    main()
