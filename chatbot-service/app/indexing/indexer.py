from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

import asyncpg

from app.services.product_search_document import (
    PRODUCT_DOCUMENT_SQL,
    PRODUCT_VARIANTS_SQL,
    canonical_document,
    content_hash,
)


@dataclass(frozen=True)
class IndexResult:
    outcome: str
    source_version: int


async def index_product(
    conn: asyncpg.Connection, product_id: UUID, requested_source_version: int = 0
) -> IndexResult:
    """Materialize one aggregate. The caller owns the surrounding transaction."""
    row = await conn.fetchrow(PRODUCT_DOCUMENT_SQL, product_id)
    if row is None:
        updated = await conn.fetchval(
            """
            update ai_retrieval.product_search_documents
               set status = 'DELETED', source_version = $2, indexed_at = now()
             where product_id = $1 and source_version < $2
         returning source_version
            """,
            product_id,
            requested_source_version,
        )
        if updated is not None:
            await conn.execute(
                """
                update ai_retrieval.search_variant_facets
                   set status = 'DELETED', source_version = $2, indexed_at = now()
                 where product_id = $1 and source_version < $2
                """,
                product_id,
                requested_source_version,
            )
            return IndexResult("DELETED", requested_source_version)
        return IndexResult("MISSING_OR_STALE", requested_source_version)

    source_version = int(row["catalog_version"])
    # An outbox event is committed with the catalog mutation, therefore a
    # version ahead of the source row is invalid (or from a different DB). Do
    # not let it poison the read model and block legitimate later events.
    if requested_source_version > source_version:
        raise ValueError(
            f"Event source version {requested_source_version} is ahead of "
            f"catalog version {source_version} for product {product_id}"
        )
    document = canonical_document(row)
    document_status = "ACTIVE" if row["status"] == "ACTIVE" else "INACTIVE"
    accepted = await conn.fetchval(
        """
        insert into ai_retrieval.product_search_documents(
            product_id, name, document_text, document_hash, category_code,
            brand_code, product_type_code, gender_code, sport_codes,
            use_case_codes, material_codes, source_version, indexed_at, status
        ) values($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,now(),$13)
        on conflict(product_id) do update set
            name = excluded.name, document_text = excluded.document_text,
            document_hash = excluded.document_hash, category_code = excluded.category_code,
            brand_code = excluded.brand_code, product_type_code = excluded.product_type_code,
            gender_code = excluded.gender_code, sport_codes = excluded.sport_codes,
            use_case_codes = excluded.use_case_codes, material_codes = excluded.material_codes,
            source_version = excluded.source_version, indexed_at = excluded.indexed_at,
            status = excluded.status
        where ai_retrieval.product_search_documents.source_version < excluded.source_version
        returning product_id
        """,
        product_id, row["name"], document, content_hash(document),
        row["category_code"], row["brand_code"], row["product_type_code"],
        row["gender_code"], row["sport_codes"], row["use_case_codes"],
        row["material_codes"], source_version, document_status,
    )
    if accepted is None:
        return IndexResult("SKIPPED_STALE_OR_CURRENT", source_version)

    variants = await conn.fetch(PRODUCT_VARIANTS_SQL, product_id)
    variant_ids: list[UUID] = []
    for variant in variants:
        variant_ids.append(variant["id"])
        facet_status = "ACTIVE" if document_status == "ACTIVE" and variant["status"] == "ACTIVE" else "INACTIVE"
        await conn.execute(
            """
            insert into ai_retrieval.search_variant_facets(
                variant_id, product_id, sku, size_code, color_code, color_family_code,
                price, available_quantity, status, source_version, indexed_at
            ) values($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,now())
            on conflict(variant_id) do update set
                product_id = excluded.product_id, sku = excluded.sku,
                size_code = excluded.size_code, color_code = excluded.color_code,
                color_family_code = excluded.color_family_code, price = excluded.price,
                available_quantity = excluded.available_quantity, status = excluded.status,
                source_version = excluded.source_version, indexed_at = excluded.indexed_at
            where ai_retrieval.search_variant_facets.source_version < excluded.source_version
            """,
            variant["id"], product_id, variant["sku"], variant["size_code"],
            variant["color_code"], variant["color_family_code"], variant["price"],
            variant["available_quantity"], facet_status, source_version,
        )

    await conn.execute(
        """
        update ai_retrieval.search_variant_facets
           set status = 'DELETED', source_version = $2, indexed_at = now()
         where product_id = $1
           and not (variant_id = any($3::uuid[]))
           and source_version < $2
        """,
        product_id, source_version, variant_ids,
    )
    return IndexResult("UPDATED", source_version)
