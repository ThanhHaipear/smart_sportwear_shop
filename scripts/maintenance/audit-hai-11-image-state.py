"""Read-only audit for the HAI-11 image-search cutover state."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import psycopg


ROOT = Path(__file__).resolve().parents[2]
IMAGE_ID = "5a7e4aa5-194e-432f-87b5-f6b970bca3e3"
ASSET = ROOT / "catalog/pilot-v1/assets/PILOT-TOP-004-01.png"


def main() -> None:
    database_url = os.environ["DATABASE_URL"]
    with psycopg.connect(database_url) as connection:
        row = connection.execute(
            """select image_id::text, product_id::text, image_url, image_hash,
                      model, status, updated_at::text
                 from ai_search.image_embeddings
                where image_id = %s""",
            (IMAGE_ID,),
        ).fetchone()
        catalog = connection.execute(
            """select p.attributes::text
                 from catalog.products p
                 join catalog.product_images i on i.product_id = p.id
                where i.id = %s""",
            (IMAGE_ID,),
        ).fetchone()
        search_counts = connection.execute(
            """select
                   (select count(*) from ai_search.search_documents),
                   (select count(*) from ai_search.search_documents where status = 'ACTIVE'),
                   (select count(*) from ai_search.search_documents where product_type is null),
                   (select count(*) from ai_search.variant_facets),
                   (select count(*) from ai_search.image_embeddings where status = 'READY'),
                   (select count(distinct product_id) from ai_search.image_embeddings where status = 'READY'),
                   (select md5(string_agg(image_id::text || embedding::text, ',' order by image_id))
                      from ai_search.image_embeddings where status = 'READY')"""
        ).fetchone()
    print(
        json.dumps(
            {
                "database": {
                    "imageId": row[0],
                    "productId": row[1],
                    "imageUrl": row[2],
                    "imageHash": row[3],
                    "model": row[4],
                    "status": row[5],
                    "updatedAt": row[6],
                },
                "localAsset": {
                    "path": ASSET.relative_to(ROOT).as_posix(),
                    "sha256": hashlib.sha256(ASSET.read_bytes()).hexdigest(),
                },
                "catalogAttributes": json.loads(catalog[0]),
                "searchCounts": {
                    "documents": search_counts[0],
                    "activeDocuments": search_counts[1],
                    "documentsMissingProductType": search_counts[2],
                    "variants": search_counts[3],
                    "readyImages": search_counts[4],
                    "distinctImageProducts": search_counts[5],
                    "imageVectorChecksum": search_counts[6],
                },
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
