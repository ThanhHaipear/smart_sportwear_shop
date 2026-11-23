"""Reconcile the frozen pilot and World Cup image imports with Catalog."""

import argparse
import json
import os
import re
from collections import Counter
from pathlib import Path
from urllib.parse import unquote, urlsplit
from uuid import UUID

import psycopg
from psycopg import sql


ROOT = Path(__file__).resolve().parents[2]
IMPORTS = ("pilot-v1", "world-cup-2026-store-staging-v1")
FIELDS = {
    "public_id": "publicId",
    "asset_version": "assetVersion",
    "width": "width",
    "height": "height",
    "asset_format": "format",
    "asset_bytes": "bytes",
    "checksum": "checksum",
    "source_kind": "sourceKind",
    "license_code": "licenseCode",
    "source_uri": "sourceUri",
    "source_group_code": "sourceGroupCode",
}


def import_images() -> dict[UUID, dict]:
    expected = {}
    for dataset in IMPORTS:
        path = ROOT / "catalog" / dataset / "import-result.json"
        for product in json.loads(path.read_text(encoding="utf-8-sig"))["products"]:
            for image in product["images"]:
                image_id = UUID(image["id"])
                metadata = {column: image[field] for column, field in FIELDS.items()}
                url = urlsplit(image["secureUrl"])
                original_path = (
                    f"/image/upload/v{metadata['asset_version']}/"
                    f"{metadata['public_id']}.{metadata['asset_format']}"
                )
                if (
                    image_id in expected
                    or url.scheme != "https"
                    or url.netloc != "res.cloudinary.com"
                    or url.query or url.fragment
                    or unquote(url.path).split("/", 2)[-1] != original_path.lstrip("/")
                    or not re.fullmatch(r"[0-9a-f]{64}", metadata["checksum"])
                    or any(metadata[key] <= 0 for key in ("width", "height", "asset_bytes"))
                    or any(not value for value in metadata.values())
                ):
                    raise ValueError(f"Invalid or duplicate import record for image {image_id}")
                expected[image_id] = {
                    "dataset": dataset,
                    "product_id": UUID(product["id"]),
                    "url": image["secureUrl"],
                    "metadata": metadata,
                }
    if not expected:
        raise ValueError("No frozen image records found")
    return expected


def reconcile(connection: psycopg.Connection, expected: dict[UUID, dict], execute: bool) -> dict:
    if not execute:
        connection.execute("set transaction isolation level repeatable read read only")
    connection.execute("set local lock_timeout = '5s'")
    if execute:
        connection.execute("lock table catalog.product_images in share row exclusive mode")
    columns = {
        column for column, in connection.execute(
            "select attname from pg_attribute where attrelid='catalog.product_images'::regclass "
            "and attnum > 0 and not attisdropped"
        )
    }
    schema_ready = FIELDS.keys() <= columns
    live_images = connection.execute(
        "select id, product_id, secure_url, to_jsonb(i) from catalog.product_images i order by id"
    ).fetchall()
    hosts = Counter(urlsplit(url).hostname or "local-or-relative" for _, _, url, _ in live_images)
    rows = {image_id: (product_id, url, metadata) for image_id, product_id, url, metadata in live_images}
    pending = []
    failures = []
    for image_id, source in expected.items():
        row = rows.get(image_id)
        if row is None:
            failures.append({"imageId": str(image_id), "reason": "missing"})
            continue
        product_id, url, current = row
        if (product_id, url) != (source["product_id"], source["url"]):
            failures.append({"imageId": str(image_id), "reason": "product-or-url-mismatch"})
            continue
        proposed = source["metadata"]
        conflicts = [key for key, value in proposed.items() if current.get(key) not in (None, value)]
        if conflicts:
            failures.append({"imageId": str(image_id), "reason": "metadata-conflict", "fields": conflicts})
        elif any(current.get(key) is None for key in proposed):
            pending.append((image_id, proposed))

    passed = not failures and (schema_ready or not execute)
    if execute and passed:
        update = sql.SQL("update catalog.product_images set {} where id = %s").format(
            sql.SQL(", ").join(sql.SQL("{} = %s").format(sql.Identifier(key)) for key in FIELDS)
        )
        with connection.cursor() as cursor:
            cursor.executemany(update, [(*metadata.values(), image_id) for image_id, metadata in pending])
            if cursor.rowcount != len(pending):
                raise RuntimeError("Image metadata update count changed inside the transaction")
    return {
        "mode": "execute" if execute else "dry-run",
        "metadataSchemaReady": schema_ready,
        "importImages": dict(Counter(row["dataset"] for row in expected.values())),
        "catalogImageHosts": dict(hosts),
        "additionalCatalogImages": len(rows.keys() - expected.keys()),
        "alreadyComplete": len(expected) - len(failures) - len(pending),
        "needsMetadata": len(pending),
        "updated": len(pending) if execute and passed else 0,
        "errors": failures,
        "passed": passed,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Write metadata after V6 and backup approval")
    args = parser.parse_args()
    database_url = os.environ.get("DATABASE_URL")
    if not database_url:
        parser.error("Set DATABASE_URL to the Catalog PostgreSQL DSN")
    try:
        with psycopg.connect(database_url, connect_timeout=10) as connection:
            report = reconcile(connection, import_images(), args.execute)
            if not args.execute or not report["passed"]:
                connection.rollback()
    except psycopg.Error as error:
        parser.exit(1, f"Catalog metadata backfill failed ({type(error).__name__}, SQLSTATE {error.sqlstate})\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
