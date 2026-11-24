"""Export the ACTIVE catalog as a frozen file for offline recommendation experiments.

The synthetic generator and the offline evaluator read this file, never the live Catalog,
so a run can be repeated after the catalog changes. Only product attributes are exported;
no customer data is involved.

Catalog currently stores `productType`, `gender` and `sportCodes` as null and `colorFamily`
as null on every variant, although the versioned catalog import manifests (`catalog/*/manifest.json`)
define them. `--attributes` fills those null fields from the manifests, matched by slug. Values
returned by Catalog always win; each product records where its attributes came from.

Usage (Catalog service must be running, CATALOG_SERVICE_TOKEN set):
    python scripts/export_catalog_snapshot.py \
        --attributes ../catalog/pilot-v1/manifest.json ../catalog/world-cup-2026-store-staging-v1/manifest.json \
        --out ../evidence/recommendation/catalog-snapshot-20261117.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.catalog import HttpCatalogClient  # noqa: E402
from app.config import get_settings  # noqa: E402


def active_catalog_slugs(base_url: str, timeout: float) -> dict[UUID, str]:
    slugs: dict[UUID, str] = {}
    page = 1
    with httpx.Client(timeout=timeout) as client:
        while True:
            response = client.get(f"{base_url.rstrip('/')}/api/v1/products", params={"page": page, "limit": 100})
            response.raise_for_status()
            rows = response.json().get("data") or []
            slugs.update({UUID(str(row["id"])): str(row["slug"]) for row in rows})
            if len(rows) < 100:
                return slugs
            page += 1


def load_manifest_attributes(paths: list[Path]) -> dict[str, dict]:
    """Map slug -> attributes declared in a catalog import manifest."""
    by_slug: dict[str, dict] = {}
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        dataset = data.get("dataset") or {}
        source = f"{path.parent.name}/{path.name}"
        if isinstance(dataset, dict) and dataset.get("version"):
            source += f"@{dataset['version']}"
        for product in data["products"]:
            by_slug[product["slug"]] = {
                "productType": product.get("productType"),
                "gender": product.get("gender"),
                "sportCodes": [product["sportCode"]] if product.get("sportCode") else [],
                "colorFamilies": [product["colorCode"]] if product.get("colorCode") else [],
                "source": source,
            }
    return by_slug


def product_record(raw: dict, slug: str, manifest: dict | None) -> dict:
    variants = [v for v in raw.get("variants") or [] if str(v.get("status")) == "ACTIVE"]
    prices = [float(v["price"]) for v in variants if v.get("price") is not None]
    record = {
        "productId": str(raw["productId"]),
        "slug": slug,
        "name": raw.get("name") or "",
        "status": raw.get("status"),
        "productType": raw.get("productType"),
        "category": raw.get("categorySlug"),
        "brand": raw.get("brandSlug"),
        "gender": raw.get("gender"),
        "sportCodes": sorted(set(raw.get("sportCodes") or [])),
        "colorFamilies": sorted({str(v["colorFamily"]) for v in variants if v.get("colorFamily")}),
        "minPrice": min(prices) if prices else None,
        "maxPrice": max(prices) if prices else None,
        "availableQuantity": sum(max(0, int(v.get("availableQuantity") or 0)) for v in variants),
        "sourceVersion": int(raw.get("sourceVersion") or 0),
    }
    filled = []
    if manifest:
        for field in ("productType", "gender", "sportCodes", "colorFamilies"):
            if not record[field] and manifest.get(field):
                record[field] = manifest[field]
                filled.append(field)
    record["attributeSource"] = {"catalog": True, "manifest": manifest["source"] if filled else None,
                                 "filledFromManifest": filled}
    return record


def canonical_bytes(products: list[dict]) -> bytes:
    return json.dumps(products, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--attributes", nargs="*", type=Path, default=[],
                        help="catalog import manifests used to fill attributes Catalog returns as null")
    args = parser.parse_args()

    settings = get_settings()
    catalog = HttpCatalogClient(settings)
    manifests = load_manifest_attributes(args.attributes)
    products = []
    for product_id, slug in active_catalog_slugs(settings.catalog_api_url,
                                                 settings.catalog_request_timeout_seconds).items():
        document = catalog.index_document(product_id)
        if document is not None and document.status == "ACTIVE":
            products.append(product_record(document.raw, slug, manifests.get(slug)))
    products.sort(key=lambda row: row["productId"])

    missing = [p["slug"] for p in products if not (p["productType"] and p["gender"] and p["sportCodes"])]
    payload = {
        "exportedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": "catalog-service /internal/v1/products/{id}/index-document",
        "attributeManifests": [str(path.as_posix()) for path in args.attributes],
        "productCount": len(products),
        "productsMissingAttributes": missing,
        # Hash of `products` only, so re-exporting an unchanged catalog gives the same checksum.
        "productsSha256": hashlib.sha256(canonical_bytes(products)).hexdigest(),
        "products": products,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: payload[k] for k in ("productCount", "productsMissingAttributes", "productsSha256")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
