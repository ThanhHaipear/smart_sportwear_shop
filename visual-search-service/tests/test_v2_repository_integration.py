import asyncio
import os
from uuid import UUID

import pytest

from app.config import Settings
from app.persistence.repository import VisualSearchRepository
from app.providers.base import EmbeddingResult, EmbeddingUsage


DATABASE_URL = os.getenv("VISUAL_SEARCH_TEST_DATABASE_URL", "")
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="Set VISUAL_SEARCH_TEST_DATABASE_URL to a disposable migrated V2 database",
)


def test_repository_operational_queries_match_migrated_v2_schema() -> None:
    coverage_product_id = UUID("62000000-0000-0000-0000-000000000001")

    async def verify() -> None:
        repository = VisualSearchRepository(Settings(_env_file=None, database_url=DATABASE_URL))

        model = await repository.active_model()
        assert model is not None
        assert model.dimensions == 768

        draft_model = await repository.create_model_version(
            "HUGGING_FACE_LOCAL",
            "audit-fixture-model",
            "audit-fixture-revision",
            "audit-fixture-preprocessing",
            768,
        )
        assert draft_model.status == "DRAFT"
        reactivated_model = await repository.activate_model_version(model.id)
        assert reactivated_model.status == "ACTIVE"

        coverage = await repository.coverage_stats()
        assert coverage.total_active_images == 1
        assert coverage.ready == 1
        assert coverage.coverage_pct == 100.0

        models = await repository.model_versions()
        assert any(item.id == model.id and item.ready_image_count == 1 for item in models)

        operations = await repository.operations_stats()
        assert operations.model is not None
        assert operations.model.id == model.id
        assert await repository.current_month_cost() == 0.0
        assert await repository.usage_stats() == []

        jobs = await repository.recent_jobs()
        assert len(jobs) == 1
        assert jobs[0].pending_count == 1

        candidates = await repository.targeted_candidates(product_id=coverage_product_id)
        job = await repository.create_indexing_job(
            "PRODUCT",
            candidates,
            audit_action="VISUAL_REINDEX_PRODUCT",
            audit_target_type="PRODUCT",
            audit_target_id=str(coverage_product_id),
        )
        assert job.total_count == 1
        assert (await repository.recent_jobs())[0].id == job.id

        image = await repository.get_image(candidates[0].image_id)
        assert image is not None
        assert image.asset_version == "1"
        assert await repository.mark_processing(image, model.id) is True

        async with await repository._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """select action, entity_type, entity_id, before_json, after_json,
                              details_redacted, actor_user_id, ip_address, user_agent
                       from public.audit_logs
                       where action in ('VISUAL_MODEL_CREATE', 'VISUAL_MODEL_ACTIVATE')
                       order by created_at"""
                )
                model_audits = await cursor.fetchall()
                assert len(model_audits) == 2
                assert model_audits[0][0:3] == (
                    "VISUAL_MODEL_CREATE",
                    "VISUAL_EMBEDDING_MODEL",
                    str(draft_model.id),
                )
                assert model_audits[0][3] is None
                assert model_audits[0][4] == {
                    "provider": "HUGGING_FACE_LOCAL",
                    "model": "audit-fixture-model",
                    "model_version": "audit-fixture-revision",
                    "preprocessing_version": "audit-fixture-preprocessing",
                    "dimensions": 768,
                    "status": "DRAFT",
                }
                assert model_audits[1][0:3] == (
                    "VISUAL_MODEL_ACTIVATE",
                    "VISUAL_EMBEDDING_MODEL",
                    str(model.id),
                )
                for audit_row in model_audits:
                    assert audit_row[5] == {
                        "source": "visual-search-service", "result": "SUCCESS"
                    }
                    assert audit_row[6:] == (None, None, None)
                await cursor.execute(
                    "select id from integration_outbox where payload->>'traceId'=%s",
                    (str(job.id),),
                )
                event_id = (await cursor.fetchone())[0]
                await cursor.execute(
                    """select action, entity_type, entity_id, before_json, after_json,
                              details_redacted, actor_user_id, ip_address, user_agent
                       from public.audit_logs where entity_id=%s""",
                    (str(job.id),),
                )
                audit = await cursor.fetchone()
                assert audit[0:3] == (
                    "VISUAL_REINDEX_PRODUCT", "VISUAL_INDEXING_JOB", str(job.id)
                )
                assert audit[3] is None
                assert audit[4] == {
                    "job_type": "PRODUCT",
                    "enqueued_count": 1,
                    "target_type": "PRODUCT",
                    "target_id": str(coverage_product_id),
                }
                assert audit[5] == {
                    "source": "visual-search-service", "result": "SUCCESS"
                }
                assert audit[6:] == (None, None, None)

        value = 1.0 / (768 ** 0.5)
        await repository.mark_ready_and_processed(
            event_id,
            "PRODUCT_REINDEX_REQUESTED",
            1,
            image,
            model,
            "b" * 64,
            (1.0,) + (0.0,) * 13,
            EmbeddingResult(
                vector=(value,) * 768,
                model=model.model,
                dimensions=768,
                usage=EmbeddingUsage(image_pixels=224 * 224),
            ),
            10,
        )
        embedding = await repository.existing_embedding(image.id, model.id)
        assert embedding is not None
        assert embedding.status == "READY"
        assert (await repository.recent_jobs())[0].status == "COMPLETED"
        assert (await repository.usage_stats())[0].operation == "DOCUMENT_EMBEDDING"

    asyncio.run(verify())
