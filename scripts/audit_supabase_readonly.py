from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from urllib.parse import quote

import psycopg
from psycopg.rows import dict_row


WORKSPACE = Path(__file__).resolve().parents[1]
LEGACY_BACKEND_ENV = WORKSPACE / ".env.legacy-backend.local"


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def legacy_database_url() -> str:
    # Historical public/ai_retrieval audit only. Current services use
    # schema-specific DSNs from .env.phase10.local.
    values = load_env(LEGACY_BACKEND_ENV)
    params = values.get("DB_PARAMS", "").lstrip("?")
    return (
        f"postgresql://{quote(values['DB_USERNAME'], safe='')}:{quote(values['DB_PASSWORD'], safe='')}"
        f"@{values['DB_HOST']}:{values['DB_PORT']}/{quote(values['DB_NAME'], safe='')}"
        f"{'?' + params if params else ''}"
    )


def rows(cursor: psycopg.Cursor, sql: str):
    cursor.execute(sql)
    return cursor.fetchall()


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only Supabase V2 catalog and image-search audit")
    parser.add_argument("--database-url", default=os.getenv("DATABASE_URL", ""))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    database_url = args.database_url or legacy_database_url()

    report: dict[str, object] = {"readOnly": True}
    with psycopg.connect(database_url, row_factory=dict_row, autocommit=False) as connection:
        connection.execute("set transaction read only")
        with connection.cursor() as cursor:
            report["database"] = rows(cursor, "select current_database() database, now() checked_at")
            report["flyway"] = rows(
                cursor,
                "select version, description, success, installed_on from flyway_schema_history order by installed_rank",
            )
            report["catalog"] = rows(
                cursor,
                """
                select count(*) products,
                       count(*) filter (where status='ACTIVE') active_products
                  from products
                """,
            )
            report["images"] = rows(
                cursor,
                """
                select count(*) images,
                       count(*) filter (where p.status='ACTIVE') active_images,
                       count(*) filter (where pi.public_id is null or btrim(pi.public_id)='') missing_public_id
                  from product_images pi join products p on p.id=pi.product_id
                """,
            )
            report["models"] = rows(
                cursor,
                """
                select id, provider, model_name, model_version, dimensions,
                       preprocessing_version, status, activated_at
                  from ai_retrieval.embedding_models
                 where modality='PRODUCT_IMAGE'
                 order by created_at
                """,
            )
            report["coverage"] = rows(
                cursor,
                """
                with active_model as (
                    select id from ai_retrieval.embedding_models
                     where modality='PRODUCT_IMAGE' and status='ACTIVE'
                )
                select count(*) active_images,
                       count(*) filter (where e.status='READY') ready,
                       count(*) filter (where e.image_id is null) missing,
                       count(*) filter (where e.status in ('PENDING','PROCESSING','FAILED','STALE')) not_ready,
                       count(*) filter (where e.status='READY' and e.color_signature is not null) color_ready
                  from product_images pi
                  join products p on p.id=pi.product_id and p.status='ACTIVE'
                  cross join active_model m
                  left join ai_retrieval.product_image_embeddings e
                    on e.image_id=pi.id and e.model_id=m.id
                   and e.source_public_id=pi.public_id and e.source_asset_version=pi.asset_version
                """,
            )
            report["outbox"] = rows(
                cursor,
                "select status, count(*) count from integration_outbox group by status order by status",
            )

    payload = json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")


if __name__ == "__main__":
    main()
