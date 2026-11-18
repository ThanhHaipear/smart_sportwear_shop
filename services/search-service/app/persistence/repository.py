from __future__ import annotations

from contextlib import asynccontextmanager
import logging
from uuid import UUID

from psycopg import AsyncConnection
from psycopg.rows import dict_row

from app.catalog.client import CatalogIndexDocument
from app.config import Settings
from app.services.documents import canonical_text, content_hash, stable_json
from app.services.visual_ranking import rerank_visual_candidates


logger = logging.getLogger(__name__)


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(f"{value:.9g}" for value in values) + "]"


class SearchRepository:
    def __init__(self, settings: Settings):
        self.settings = settings

    @asynccontextmanager
    async def connection(self):
        connection = await AsyncConnection.connect(self.settings.database_url, row_factory=dict_row)
        try:
            yield connection
        finally:
            await connection.close()

    async def ping(self) -> bool:
        try:
            async with self.connection() as connection, connection.cursor() as cursor:
                await cursor.execute("select 1 from ai_search.search_documents limit 1")
            return True
        except Exception:
            logger.exception("search database readiness check failed")
            return False

    async def is_processed(self, consumer: str, event_id: UUID) -> bool:
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                "select 1 from ai_search.processed_events where consumer_name=%s and event_id=%s",
                (consumer, event_id),
            )
            return await cursor.fetchone() is not None

    async def mark_processed(
        self,
        consumer: str,
        event_id: UUID,
        aggregate_version: int,
    ) -> None:
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                """insert into ai_search.processed_events(consumer_name,event_id,aggregate_version)
                   values(%s,%s,%s) on conflict do nothing""",
                (consumer, event_id, aggregate_version),
            )
            await connection.commit()

    async def is_current(
        self,
        document: CatalogIndexDocument,
        image_sources: list[tuple[UUID, str, str]] | None,
    ) -> bool:
        digest = content_hash(canonical_text(document))
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                """select source_version,status,name,document_hash,
                          category_id,category,category_slug,brand_id,brand,brand_slug,
                          product_type,gender,sport_codes,attributes
                     from ai_search.search_documents where product_id=%s""",
                (document.product_id,),
            )
            current_document = await cursor.fetchone()
            if current_document is None or (
                current_document["source_version"],
                current_document["status"],
                current_document["name"],
                current_document["document_hash"],
                current_document["category_id"],
                current_document["category"],
                current_document["category_slug"],
                current_document["brand_id"],
                current_document["brand"],
                current_document["brand_slug"],
                current_document["product_type"],
                current_document["gender"],
                list(current_document["sport_codes"] or []),
                stable_json(current_document["attributes"] or {}),
            ) != (
                document.source_version,
                document.status,
                document.name,
                digest,
                document.category_id,
                document.category,
                document.category_slug,
                document.brand_id,
                document.brand,
                document.brand_slug,
                document.product_type,
                document.gender,
                document.sport_codes,
                stable_json(document.attributes),
            ):
                return False

            await cursor.execute(
                """select variant_id,sku,size_code,color_code,color_family,price,
                          available_quantity,status,source_version
                     from ai_search.variant_facets where product_id=%s order by variant_id""",
                (document.product_id,),
            )
            current_variants = [
                (
                    row["variant_id"], row["sku"], row["size_code"], row["color_code"],
                    row["color_family"], float(row["price"]), row["available_quantity"],
                    row["status"], row["source_version"],
                )
                for row in await cursor.fetchall()
            ]
            expected_variants = sorted(
                (
                    variant.id, variant.sku, variant.size, variant.color, variant.color_family,
                    float(variant.price), variant.available_quantity, variant.status,
                    document.source_version,
                )
                for variant in document.variants
            )
            if current_variants != expected_variants:
                return False
            if image_sources is None:
                return True

            await cursor.execute(
                """select image_id,image_url,image_hash,model,status
                     from ai_search.image_embeddings where product_id=%s order by image_id""",
                (document.product_id,),
            )
            current_images = [
                (row["image_id"], row["image_url"], row["image_hash"], row["model"], row["status"])
                for row in await cursor.fetchall()
            ]
            expected_images = sorted(
                (image_id, image_url, image_hash, self.settings.visual_embedding_model, "READY")
                for image_id, image_url, image_hash in image_sources
            )
            return current_images == expected_images

    async def upsert_document(
        self, document: CatalogIndexDocument, text_vector: list[float] | None,
        image_vectors: list[tuple[UUID, str, str, list[float]]] | None, event_id: UUID | None = None,
        consumer: str = "catalog-index-v1",
    ) -> str:
        text = canonical_text(document)
        digest = content_hash(text)
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                """
                insert into ai_search.search_documents(
                    product_id, source_version, status, name, document_text, document_hash,
                    category_id,category,category_slug,brand_id,brand,brand_slug,
                    product_type,gender,sport_codes,attributes,updated_at
                ) values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb,now())
                on conflict(product_id) do update set
                    source_version=excluded.source_version,status=excluded.status,name=excluded.name,
                    document_text=excluded.document_text,document_hash=excluded.document_hash,
                    category_id=excluded.category_id,category=excluded.category,category_slug=excluded.category_slug,
                    brand_id=excluded.brand_id,brand=excluded.brand,brand_slug=excluded.brand_slug,
                    product_type=excluded.product_type,
                    gender=excluded.gender,sport_codes=excluded.sport_codes,attributes=excluded.attributes,
                    updated_at=now()
                where ai_search.search_documents.source_version <= excluded.source_version
                returning product_id
                """,
                (document.product_id, document.source_version, document.status, document.name, text, digest,
                 document.category_id, document.category, document.category_slug,
                 document.brand_id, document.brand, document.brand_slug,
                 document.product_type, document.gender,
                 document.sport_codes, stable_json(document.attributes)),
            )
            if await cursor.fetchone() is None:
                await connection.rollback()
                return "STALE"
            await cursor.execute("delete from ai_search.variant_facets where product_id=%s", (document.product_id,))
            for variant in document.variants:
                await cursor.execute(
                    """insert into ai_search.variant_facets(
                       variant_id,product_id,sku,size_code,color_code,color_family,price,
                       available_quantity,status,source_version)
                       values(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                    (variant.id, document.product_id, variant.sku, variant.size, variant.color,
                     variant.color_family, variant.price, variant.available_quantity,
                     variant.status, document.source_version),
                )
            if text_vector is not None:
                await cursor.execute(
                    """insert into ai_search.text_embeddings(product_id,document_hash,model,embedding,status)
                       values(%s,%s,%s,%s::extensions.vector,'READY')
                       on conflict(product_id) do update set document_hash=excluded.document_hash,
                       model=excluded.model,embedding=excluded.embedding,status='READY',updated_at=now()""",
                    (document.product_id, digest, self.settings.text_embedding_model, vector_literal(text_vector)),
                )
            active_images: list[UUID] = []
            for image_id, url, image_hash, vector in image_vectors or ():
                active_images.append(image_id)
                await cursor.execute(
                    """insert into ai_search.image_embeddings(
                       image_id,product_id,image_url,image_hash,model,embedding,status)
                       values(%s,%s,%s,%s,%s,%s::extensions.vector,'READY')
                       on conflict(image_id) do update set product_id=excluded.product_id,
                       image_url=excluded.image_url,image_hash=excluded.image_hash,model=excluded.model,
                       embedding=excluded.embedding,status='READY',updated_at=now()""",
                    (image_id, document.product_id, url, image_hash,
                     self.settings.visual_embedding_model, vector_literal(vector)),
                )
            if image_vectors is not None:
                await cursor.execute(
                    "delete from ai_search.image_embeddings where product_id=%s and not (image_id=any(%s::uuid[]))",
                    (document.product_id, active_images),
                )
            if event_id is not None:
                await cursor.execute(
                    """insert into ai_search.processed_events(consumer_name,event_id,aggregate_version)
                       values(%s,%s,%s) on conflict do nothing""",
                    (consumer, event_id, document.source_version),
                )
            await connection.commit()
        return "UPDATED"

    async def mark_deleted(self, product_id: UUID, source_version: int, event_id: UUID | None = None) -> None:
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                "update ai_search.search_documents set status='DELETED',source_version=%s,updated_at=now() "
                "where product_id=%s and source_version<=%s", (source_version, product_id, source_version),
            )
            if event_id:
                await cursor.execute(
                    "insert into ai_search.processed_events(consumer_name,event_id,aggregate_version) "
                    "values('catalog-index-v1',%s,%s) on conflict do nothing", (event_id, source_version),
                )
            await connection.commit()

    async def text_search(
        self, query: str, vector: list[float] | None, limit: int,
        filters: dict[str, object] | None = None,
    ) -> list[dict]:
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                """select * from ai_search.search_text(
                       %s::text,%s::extensions.vector,%s::integer,%s::jsonb,
                       %s::real,%s::integer,%s::real,%s::real
                   )""",
                (query, vector_literal(vector) if vector else None, limit,
                 stable_json(filters or {}), self.settings.text_min_similarity, self.settings.rrf_k,
                 self.settings.keyword_weight, self.settings.semantic_weight),
            )
            return list(await cursor.fetchall())

    async def image_search(
        self, vector: list[float], limit: int,
        query_category_scores: dict[str, float] | None = None,
    ) -> list[dict]:
        async with self.connection() as connection, connection.cursor() as cursor:
            await cursor.execute(
                """with ranked as (
                       select e.product_id,e.image_id,e.image_url,
                              1-(e.embedding <=> %s::extensions.vector) similarity,
                              d.product_type,
                              row_number() over (
                                  partition by e.product_id
                                  order by e.embedding <=> %s::extensions.vector,e.image_id
                              ) product_rank
                       from ai_search.image_embeddings e
                       join ai_search.search_documents d using(product_id)
                       where e.status='READY' and d.status='ACTIVE'
                   )
                   select product_id,image_id,image_url,similarity,product_type
                   from ranked where product_rank=1
                   order by similarity desc,product_id,image_id limit %s""",
                (vector_literal(vector), vector_literal(vector), min(max(limit * 10, 50), 100)),
            )
            rows = list(await cursor.fetchall())
        return rerank_visual_candidates(
            rows,
            category_weight=self.settings.visual_category_consensus_weight,
            query_category_scores=query_category_scores,
        )[:limit]
