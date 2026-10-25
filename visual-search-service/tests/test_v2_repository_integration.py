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
        job = await repository.create_indexing_job("PRODUCT", candidates)
        assert job.total_count == 1
        assert (await repository.recent_jobs())[0].id == job.id

        image = await repository.get_image(candidates[0].image_id)
        assert image is not None
        assert image.asset_version == "1"
        assert await repository.mark_processing(image, model.id) is True

        async with await repository._connect() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "select id from integration_outbox where payload->>'traceId'=%s",
                    (str(job.id),),
                )
                event_id = (await cursor.fetchone())[0]

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
