from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Literal, Protocol
from uuid import UUID

import asyncpg

from app.config.settings import Settings
from app.embeddings.image_source import download_cloudinary_original
from app.embeddings.models import pgvector_literal

logger = logging.getLogger(__name__)

TEXT_JOB_TYPE = "PRODUCT_TEXT_EMBEDDING_BACKFILL"
IMAGE_JOB_TYPE = "PRODUCT_IMAGE_EMBEDDING_BACKFILL"
Modality = Literal["PRODUCT_TEXT", "PRODUCT_IMAGE"]


class TextEmbedder(Protocol):
    def encode(self, text: str) -> list[float]: ...


class ImageEmbedder(Protocol):
    def encode(self, image_bytes: bytes) -> list[float]: ...


@dataclass(frozen=True)
class ModelRecord:
    id: UUID
    modality: Modality
    model_name: str
    model_version: str
    dimensions: int
    preprocessing_version: str


class ProductEmbeddingWorker:
    def __init__(self, pool: asyncpg.Pool, settings: Settings):
        self.pool = pool
        self.settings = settings

    async def active_model(self, modality: Modality) -> ModelRecord:
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                select id, modality, model_name, model_version, dimensions, preprocessing_version
                  from ai_retrieval.embedding_models
                 where modality=$1 and status='ACTIVE'
                """,
                modality,
            )
        if row is None:
            raise RuntimeError(f"No ACTIVE embedding model for {modality}; apply migration V7")
        return ModelRecord(**dict(row))

    async def create_job(self, modality: Modality, model_id: UUID) -> UUID:
        job_type = TEXT_JOB_TYPE if modality == "PRODUCT_TEXT" else IMAGE_JOB_TYPE
        entity_type = "PRODUCT" if modality == "PRODUCT_TEXT" else "PRODUCT_IMAGE"
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                job_id = await conn.fetchval(
                    """
                    insert into ai_retrieval.indexing_jobs(
                        job_type, model_id, status, total_count, started_at
                    ) values($1,$2,'RUNNING',0,now()) returning id
                    """,
                    job_type,
                    model_id,
                )
                if modality == "PRODUCT_TEXT":
                    await conn.execute(
                        """
                        update ai_retrieval.product_text_embeddings embedding
                           set status='STALE', last_error=null
                         where embedding.model_id=$1 and embedding.status='READY'
                           and not exists (
                               select 1 from ai_retrieval.product_search_documents document
                                where document.product_id=embedding.product_id
                                  and document.status='ACTIVE'
                                  and document.document_hash=embedding.document_hash
                           )
                        """,
                        model_id,
                    )
                    await conn.execute(
                        """
                        insert into ai_retrieval.indexing_job_items(
                            job_id, entity_type, entity_id, status
                        )
                        select $1, $2, document.product_id, 'PENDING'
                          from ai_retrieval.product_search_documents document
                         where document.status='ACTIVE'
                        on conflict(job_id,entity_type,entity_id) do nothing
                        """,
                        job_id,
                        entity_type,
                    )
                    await conn.execute(
                        """
                        update ai_retrieval.indexing_job_items item
                           set status='SKIPPED'
                          from ai_retrieval.product_search_documents document,
                               ai_retrieval.product_text_embeddings embedding
                         where item.job_id=$1 and item.entity_type='PRODUCT'
                           and document.product_id=item.entity_id
                           and embedding.product_id=document.product_id
                           and embedding.model_id=$2 and embedding.status='READY'
                           and embedding.document_hash=document.document_hash
                        """,
                        job_id, model_id,
                    )
                else:
                    await conn.execute(
                        """
                        update ai_retrieval.product_image_embeddings embedding
                           set status='STALE', last_error=null
                         where embedding.model_id=$1 and embedding.status='READY'
                           and not exists (
                               select 1
                                 from product_images image
                                 join ai_retrieval.product_search_documents document
                                   on document.product_id=image.product_id and document.status='ACTIVE'
                                where image.id=embedding.image_id
                                  and image.product_id=embedding.product_id
                                  and image.public_id=embedding.source_public_id
                                  and image.asset_version=embedding.source_asset_version
                           )
                        """,
                        model_id,
                    )
                    await conn.execute(
                        """
                        insert into ai_retrieval.indexing_job_items(
                            job_id, entity_type, entity_id, status
                        )
                        select $1, $2, image.id, 'PENDING'
                          from product_images image
                          join ai_retrieval.product_search_documents document
                            on document.product_id=image.product_id and document.status='ACTIVE'
                        on conflict(job_id,entity_type,entity_id) do nothing
                        """,
                        job_id,
                        entity_type,
                    )
                    await conn.execute(
                        """
                        update ai_retrieval.indexing_job_items item
                           set status='SKIPPED'
                          from product_images image,
                               ai_retrieval.product_image_embeddings embedding
                         where item.job_id=$1 and item.entity_type='PRODUCT_IMAGE'
                           and image.id=item.entity_id
                           and embedding.image_id=image.id and embedding.model_id=$2
                           and embedding.status='READY'
                           and embedding.source_public_id=image.public_id
                           and embedding.source_asset_version=image.asset_version
                           and (image.checksum is null or embedding.image_hash=image.checksum)
                        """,
                        job_id, model_id,
                    )
                await conn.execute(
                    """
                    update ai_retrieval.indexing_jobs
                       set total_count=(select count(*) from ai_retrieval.indexing_job_items where job_id=$1)
                     where id=$1
                    """,
                    job_id,
                )
                return job_id

    async def _claim(self, job_id: UUID):
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                return await conn.fetchrow(
                    """
                    with candidate as (
                        select id from ai_retrieval.indexing_job_items
                         where job_id=$1 and status in ('PENDING','FAILED') and attempts<$2
                         order by updated_at,id for update skip locked limit 1
                    )
                    update ai_retrieval.indexing_job_items item
                       set status='PROCESSING', attempts=attempts+1, last_error=null
                      from candidate where item.id=candidate.id
                    returning item.id,item.entity_id,item.attempts
                    """,
                    job_id,
                    self.settings.EMBEDDING_MAX_ITEM_ATTEMPTS,
                )

    async def job_requires_model(self, job_id: UUID, modality: Modality, model_id: UUID) -> bool:
        """Avoid loading a multi-GB model when every job item is already current."""
        async with self.pool.acquire() as conn:
            if modality == "PRODUCT_TEXT":
                return bool(await conn.fetchval(
                    """
                    select exists(
                        select 1 from ai_retrieval.indexing_job_items item
                        join ai_retrieval.product_search_documents document
                          on document.product_id=item.entity_id and document.status='ACTIVE'
                        left join ai_retrieval.product_text_embeddings embedding
                          on embedding.product_id=document.product_id and embedding.model_id=$2
                         and embedding.status='READY' and embedding.document_hash=document.document_hash
                        where item.job_id=$1 and embedding.product_id is null
                    )
                    """,
                    job_id, model_id,
                ))
            return bool(await conn.fetchval(
                """
                select exists(
                    select 1 from ai_retrieval.indexing_job_items item
                    join product_images image on image.id=item.entity_id
                    left join ai_retrieval.product_image_embeddings embedding
                      on embedding.image_id=image.id and embedding.model_id=$2
                     and embedding.status='READY'
                     and embedding.source_public_id=image.public_id
                     and embedding.source_asset_version=image.asset_version
                     and (image.checksum is null or embedding.image_hash=image.checksum)
                    where item.job_id=$1 and embedding.image_id is null
                )
                """,
                job_id, model_id,
            ))

    async def _set_item(self, item_id: UUID, status: str, error: str | None = None) -> None:
        async with self.pool.acquire() as conn:
            await conn.execute(
                """update ai_retrieval.indexing_job_items
                      set status=$2,last_error=$3 where id=$1""",
                item_id,
                status,
                error,
            )

    async def _prepare_text(self, item, model: ModelRecord):
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                document = await conn.fetchrow(
                    """select product_id,document_text,document_hash
                         from ai_retrieval.product_search_documents
                        where product_id=$1 and status='ACTIVE' for update""",
                    item["entity_id"],
                )
                if document is None:
                    await conn.execute(
                        "update ai_retrieval.indexing_job_items set status='SKIPPED' where id=$1",
                        item["id"],
                    )
                    return None
                ready = await conn.fetchval(
                    """select true from ai_retrieval.product_text_embeddings
                        where product_id=$1 and model_id=$2 and status='READY' and document_hash=$3""",
                    document["product_id"], model.id, document["document_hash"],
                )
                if ready:
                    await conn.execute(
                        "update ai_retrieval.indexing_job_items set status='SKIPPED' where id=$1",
                        item["id"],
                    )
                    return None
                await conn.execute(
                    """
                    insert into ai_retrieval.product_text_embeddings(
                        product_id,model_id,document_hash,status,attempts
                    ) values($1,$2,$3,'PROCESSING',1)
                    on conflict(product_id,model_id) do update set
                        document_hash=excluded.document_hash,embedding=null,status='PROCESSING',
                        attempts=least(ai_retrieval.product_text_embeddings.attempts+1,20),
                        last_error=null,embedded_at=null
                    """,
                    document["product_id"], model.id, document["document_hash"],
                )
                return dict(document)

    async def _finish_text(self, item, model: ModelRecord, document, vector: list[float]) -> None:
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                current_hash = await conn.fetchval(
                    """select document_hash from ai_retrieval.product_search_documents
                        where product_id=$1 and status='ACTIVE' for update""",
                    document["product_id"],
                )
                if current_hash != document["document_hash"]:
                    await conn.execute(
                        """update ai_retrieval.product_text_embeddings
                              set status='STALE',embedding=null,embedded_at=null,last_error=null
                            where product_id=$1 and model_id=$2""",
                        document["product_id"], model.id,
                    )
                    await conn.execute(
                        "update ai_retrieval.indexing_job_items set status='SKIPPED' where id=$1",
                        item["id"],
                    )
                    return
                await conn.execute(
                    """update ai_retrieval.product_text_embeddings
                          set embedding=$3::extensions.vector,status='READY',embedded_at=now(),last_error=null
                        where product_id=$1 and model_id=$2 and document_hash=$4""",
                    document["product_id"], model.id, pgvector_literal(vector), document["document_hash"],
                )
                await conn.execute(
                    "update ai_retrieval.indexing_job_items set status='SUCCEEDED' where id=$1",
                    item["id"],
                )

    async def _fail_text(self, item, model: ModelRecord, error: str) -> None:
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """update ai_retrieval.product_text_embeddings
                          set status='FAILED',embedding=null,embedded_at=null,last_error=$3
                        where product_id=$1 and model_id=$2""",
                    item["entity_id"], model.id, error,
                )
                await conn.execute(
                    """update ai_retrieval.indexing_job_items
                          set status='FAILED',last_error=$2 where id=$1""",
                    item["id"], error,
                )

    async def run_text_job(self, job_id: UUID, model: ModelRecord, embedder: TextEmbedder) -> dict:
        while item := await self._claim(job_id):
            document = await self._prepare_text(item, model)
            if document is None:
                continue
            try:
                vector = embedder.encode(document["document_text"])
                if len(vector) != model.dimensions:
                    raise ValueError(f"Model returned {len(vector)} dimensions")
                await self._finish_text(item, model, document, vector)
            except Exception as exc:
                error = f"{type(exc).__name__}: {exc}"[:4000]
                await self._fail_text(item, model, error)
                logger.warning("Text embedding failed for %s: %s", item["entity_id"], error)
        return await self._finish_job(job_id)

    async def _prepare_image(self, item, model: ModelRecord):
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                image = await conn.fetchrow(
                    """
                    select image.id,image.product_id,image.public_id,image.asset_version,
                           image.secure_url,image.checksum,image.width,image.height
                      from product_images image
                      join ai_retrieval.product_search_documents document
                        on document.product_id=image.product_id and document.status='ACTIVE'
                     where image.id=$1 for update of image
                    """,
                    item["entity_id"],
                )
                if image is None:
                    await conn.execute(
                        "update ai_retrieval.indexing_job_items set status='SKIPPED' where id=$1",
                        item["id"],
                    )
                    return None
                ready = await conn.fetchval(
                    """select true from ai_retrieval.product_image_embeddings
                        where image_id=$1 and model_id=$2 and status='READY'
                          and source_public_id=$3 and source_asset_version=$4
                          and ($5::text is null or image_hash=$5)""",
                    image["id"], model.id, image["public_id"], image["asset_version"], image["checksum"],
                )
                if ready:
                    await conn.execute(
                        "update ai_retrieval.indexing_job_items set status='SKIPPED' where id=$1",
                        item["id"],
                    )
                    return None
                await conn.execute(
                    """
                    insert into ai_retrieval.product_image_embeddings(
                        image_id,product_id,model_id,source_public_id,source_asset_version,status,attempts
                    ) values($1,$2,$3,$4,$5,'PROCESSING',1)
                    on conflict(image_id,model_id) do update set
                        product_id=excluded.product_id,source_public_id=excluded.source_public_id,
                        source_asset_version=excluded.source_asset_version,image_hash=null,embedding=null,
                        status='PROCESSING',attempts=least(ai_retrieval.product_image_embeddings.attempts+1,20),
                        last_error=null,embedded_at=null
                    """,
                    image["id"], image["product_id"], model.id, image["public_id"], image["asset_version"],
                )
                return dict(image)

    async def _finish_image(self, item, model: ModelRecord, image, downloaded, vector: list[float]) -> None:
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                current = await conn.fetchrow(
                    """select public_id,asset_version,secure_url,checksum
                         from product_images where id=$1 for update""",
                    image["id"],
                )
                identity = None if current is None else (
                    current["public_id"], current["asset_version"], current["secure_url"], current["checksum"]
                )
                captured = (image["public_id"], image["asset_version"], image["secure_url"], image["checksum"])
                if identity != captured:
                    await conn.execute(
                        """update ai_retrieval.product_image_embeddings
                              set status='STALE',embedding=null,embedded_at=null,last_error=null
                            where image_id=$1 and model_id=$2""",
                        image["id"], model.id,
                    )
                    await conn.execute(
                        "update ai_retrieval.indexing_job_items set status='SKIPPED' where id=$1",
                        item["id"],
                    )
                    return
                await conn.execute(
                    """update ai_retrieval.product_image_embeddings
                          set image_hash=$3,embedding=$4::extensions.vector,status='READY',
                              embedded_at=now(),last_error=null
                        where image_id=$1 and model_id=$2
                          and source_public_id=$5 and source_asset_version=$6""",
                    image["id"], model.id, downloaded.sha256, pgvector_literal(vector),
                    image["public_id"], image["asset_version"],
                )
                await conn.execute(
                    "update ai_retrieval.indexing_job_items set status='SUCCEEDED' where id=$1",
                    item["id"],
                )

    async def _fail_image(self, item, model: ModelRecord, error: str) -> None:
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """update ai_retrieval.product_image_embeddings
                          set status='FAILED',embedding=null,embedded_at=null,last_error=$3
                        where image_id=$1 and model_id=$2""",
                    item["entity_id"], model.id, error,
                )
                await conn.execute(
                    """update ai_retrieval.indexing_job_items
                          set status='FAILED',last_error=$2 where id=$1""",
                    item["id"], error,
                )

    async def run_image_job(self, job_id: UUID, model: ModelRecord, embedder: ImageEmbedder) -> dict:
        for_next = True
        while for_next:
            item = await self._claim(job_id)
            for_next = item is not None
            if item is None:
                break
            image = await self._prepare_image(item, model)
            if image is None:
                continue
            try:
                downloaded = await download_cloudinary_original(
                    image["secure_url"], image["asset_version"],
                    expected_sha256=image["checksum"],
                    max_bytes=self.settings.EMBEDDING_MAX_IMAGE_BYTES,
                    max_pixels=self.settings.EMBEDDING_MAX_IMAGE_PIXELS,
                    timeout_seconds=self.settings.EMBEDDING_HTTP_TIMEOUT_SECONDS,
                )
                vector = embedder.encode(downloaded.content)
                if len(vector) != model.dimensions:
                    raise ValueError(f"Model returned {len(vector)} dimensions")
                await self._finish_image(item, model, image, downloaded, vector)
            except Exception as exc:
                error = f"{type(exc).__name__}: {exc}"[:4000]
                await self._fail_image(item, model, error)
                logger.warning("Image embedding failed for %s: %s", item["entity_id"], error)
        return await self._finish_job(job_id)

    async def _finish_job(self, job_id: UUID) -> dict:
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                counts = await conn.fetchrow(
                    """select count(*) as total,
                              count(*) filter(where status in ('SUCCEEDED','SKIPPED')) as success,
                              count(*) filter(where status='SKIPPED') as skipped,
                              count(*) filter(where status='FAILED') as failed
                         from ai_retrieval.indexing_job_items where job_id=$1""",
                    job_id,
                )
                total = int(counts["total"])
                success = int(counts["success"])
                skipped = int(counts["skipped"])
                failed = int(counts["failed"])
                status = "COMPLETED" if failed == 0 else ("FAILED" if success == 0 else "PARTIALLY_COMPLETED")
                summary = None if failed == 0 else json.dumps({"failedItems": failed})
                await conn.execute(
                    """update ai_retrieval.indexing_jobs
                          set status=$2,total_count=$3,success_count=$4,failed_count=$5,
                              finished_at=now(),error_summary=$6::jsonb where id=$1""",
                    job_id, status, total, success, failed, summary,
                )
                return {"job_id": str(job_id), "status": status, "total": total,
                        "success": success, "skipped": skipped, "failed": failed}

    async def coverage(self) -> dict:
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                with models as (
                    select modality,id from ai_retrieval.embedding_models where status='ACTIVE'
                )
                select
                    (select count(*) from ai_retrieval.product_search_documents where status='ACTIVE') as text_total,
                    (select count(*) from ai_retrieval.product_text_embeddings e join models m on m.id=e.model_id and m.modality='PRODUCT_TEXT' where e.status='READY') as text_ready,
                    (select count(*) from product_images i join ai_retrieval.product_search_documents d on d.product_id=i.product_id and d.status='ACTIVE') as image_total,
                    (select count(*) from ai_retrieval.product_image_embeddings e join models m on m.id=e.model_id and m.modality='PRODUCT_IMAGE' where e.status='READY') as image_ready
                """
            )
        return dict(row)

    async def verify(self) -> dict:
        async with self.pool.acquire() as conn:
            models = await conn.fetch(
                """select modality,model_name,model_version,dimensions,preprocessing_version
                     from ai_retrieval.embedding_models where status='ACTIVE' order by modality"""
            )
            guards = await conn.fetchrow(
                """
                select
                  (select count(*) from ai_retrieval.product_text_embeddings
                    where status='READY' and abs((embedding <#> embedding)+1.0)>0.02) as bad_text_l2,
                  (select count(*) from ai_retrieval.product_image_embeddings
                    where status='READY' and abs((embedding <#> embedding)+1.0)>0.02) as bad_image_l2,
                  (select count(*) from ai_retrieval.product_text_embeddings embedding
                    join ai_retrieval.product_search_documents document using(product_id)
                   where embedding.status='READY' and embedding.document_hash<>document.document_hash) as stale_text_ready,
                  (select count(*) from ai_retrieval.product_image_embeddings embedding
                    left join product_images image on image.id=embedding.image_id
                   where embedding.status='READY' and (
                       image.id is null or image.product_id<>embedding.product_id
                       or image.public_id<>embedding.source_public_id
                       or image.asset_version<>embedding.source_asset_version
                       or (image.checksum is not null and image.checksum<>embedding.image_hash)
                   )) as stale_image_ready,
                  (select count(*) from ai_retrieval.product_text_embeddings where status='FAILED') as failed_text,
                  (select count(*) from ai_retrieval.product_image_embeddings where status='FAILED') as failed_image,
                  (select count(*) from ai_retrieval.indexing_jobs
                    where status in ('PENDING','RUNNING')) as open_jobs,
                  (select count(*) from pg_indexes where schemaname='ai_retrieval'
                    and (lower(indexdef) like '%hnsw%' or lower(indexdef) like '%ivfflat%')) as ann_indexes
                """
            )
            text_similarity = await conn.fetchval(
                """
                with sample as (
                    select product_id,model_id,embedding from ai_retrieval.product_text_embeddings
                     where status='READY' order by product_id limit 1
                )
                select result.similarity from sample
                cross join lateral ai_retrieval.search_product_text_exact(
                    sample.model_id,sample.embedding,100
                ) result where result.product_id=sample.product_id
                """
            )
            image_similarity = await conn.fetchval(
                """
                with sample as (
                    select image_id,model_id,embedding from ai_retrieval.product_image_embeddings
                     where status='READY' order by image_id limit 1
                )
                select result.similarity from sample
                cross join lateral ai_retrieval.search_product_images_exact(
                    sample.model_id,sample.embedding,100
                ) result where result.image_id=sample.image_id
                """
            )
        return {
            "models": [dict(row) for row in models],
            "guards": dict(guards),
            "exact_cosine": {
                "text_self_similarity": text_similarity,
                "image_self_similarity": image_similarity,
            },
        }
