import asyncio
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID

from app.catalog.client import CatalogIndexDocument
from app.config import Settings
from app.services.image_pipeline import NormalizedImage
from app.services.indexer import CatalogIndexer


def document() -> CatalogIndexDocument:
    return CatalogIndexDocument.model_validate({
        "productId": "10000000-0000-0000-0000-000000000001",
        "sourceVersion": 2,
        "status": "ACTIVE",
        "name": "Demo",
        "categorySlug": "ao-the-thao",
        "variants": [],
        "images": [{
            "id": "50000000-0000-0000-0000-000000000001",
            "url": "https://res.cloudinary.com/demo/image/upload/v1/demo.jpg",
        }],
    })


def indexer() -> CatalogIndexer:
    instance = CatalogIndexer(Settings(
        visual_search_enabled=True,
        visual_embedding_provider="fake",
    ))
    instance.catalog.index_document = AsyncMock(return_value=document())
    instance.repository.is_processed = AsyncMock(return_value=False)
    instance.repository.is_current = AsyncMock(return_value=True)
    instance.repository.mark_processed = AsyncMock()
    instance.repository.upsert_document = AsyncMock(return_value="UPDATED")
    instance.text_embedder.embed = AsyncMock(return_value=None)
    return instance


@patch("app.services.indexer.ImagePipeline")
def test_unchanged_snapshot_is_skipped_before_embedding(pipeline_type):
    instance = indexer()
    pipeline_type.return_value.download_and_normalize = AsyncMock(return_value=NormalizedImage(
        content=b"normalized",
        sha256="a" * 64,
        width=224,
        height=224,
        original_format="JPEG",
    ))

    outcome = asyncio.run(instance.index(document().product_id, 0))

    assert outcome == "SKIPPED"
    instance.repository.is_current.assert_awaited_once()
    assert instance.repository.is_current.await_args.args[0].product_type == "SPORTS_TOP"
    instance.text_embedder.embed.assert_not_awaited()
    instance.repository.upsert_document.assert_not_awaited()


@patch("app.services.indexer.ImagePipeline")
def test_skipped_event_is_recorded_as_processed(pipeline_type):
    instance = indexer()
    pipeline_type.return_value.download_and_normalize = AsyncMock(return_value=NormalizedImage(
        content=b"normalized",
        sha256="a" * 64,
        width=224,
        height=224,
        original_format="JPEG",
    ))
    event_id = UUID("90000000-0000-0000-0000-000000000001")

    outcome = asyncio.run(instance.index(document().product_id, 2, event_id=event_id))

    assert outcome == "SKIPPED"
    instance.repository.mark_processed.assert_awaited_once_with(
        "catalog-index-v1",
        event_id,
        2,
    )


@patch("app.services.indexer.build_provider")
@patch("app.services.indexer.ImagePipeline")
def test_force_recomputes_and_updates_snapshot(pipeline_type, build_provider):
    instance = indexer()
    pipeline_type.return_value.download_and_normalize = AsyncMock(return_value=NormalizedImage(
        content=b"normalized",
        sha256="a" * 64,
        width=224,
        height=224,
        original_format="JPEG",
    ))
    result = Mock()
    result.validate_dimensions.return_value.vector = [0.0] * 768
    build_provider.return_value.embed_document = AsyncMock(return_value=result)

    outcome = asyncio.run(instance.index(document().product_id, 0, force=True))

    assert outcome == "UPDATED"
    instance.repository.is_current.assert_not_awaited()
    build_provider.return_value.embed_document.assert_awaited_once_with(b"normalized")
    instance.repository.upsert_document.assert_awaited_once()


@patch("app.services.indexer.ImagePipeline")
def test_reindex_with_visual_search_disabled_leaves_image_vectors_untouched(pipeline_type):
    instance = indexer()
    instance.settings.visual_search_enabled = False
    instance.repository.is_current.return_value = False

    outcome = asyncio.run(instance.index(document().product_id, 0))

    assert outcome == "UPDATED"
    assert instance.repository.is_current.await_args.args[1] is None
    assert instance.repository.upsert_document.await_args.args[2] is None
    pipeline_type.assert_not_called()
