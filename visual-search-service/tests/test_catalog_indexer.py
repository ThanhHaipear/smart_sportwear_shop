import asyncio
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from app.messaging.events import CatalogEvent
from app.messaging.retry import PermanentEventError, RetryableEventError
from app.persistence.repository import CatalogImage, ExistingEmbedding, ModelVersion
from app.providers.fake import FakeEmbeddingProvider
from app.services.catalog_indexer import CatalogIndexer
from app.services.errors import ImagePipelineError
from app.services.image_pipeline import NormalizedImage


def make_event(event_type="PRODUCT_IMAGE_CREATED"):
    product_id = uuid4()
    return CatalogEvent.model_validate(
        {
            "eventId": str(uuid4()),
            "eventType": event_type,
            "eventVersion": 1,
            "productId": str(product_id),
            "imageId": str(uuid4()) if "IMAGE" in event_type else None,
            "occurredAt": datetime.now(timezone.utc).isoformat(),
            "traceId": "test",
        }
    )


class FakeRepository:
    def __init__(self, event, *, processed=False, status="ACTIVE", existing=None):
        self.processed = processed
        self.model = ModelVersion(uuid4(), "fake", "fake-v1", 4)
        self.image = CatalogImage(event.image_id or uuid4(), event.product_id, "https://cdn.shopify.com/s/files/a.jpg", None, status)
        self.existing = existing
        self.processing_calls = 0
        self.ready_calls = 0
        self.completed = []
        self.failed = []
        self.retry_pending = []

    async def is_processed(self, _event_id): return self.processed
    async def active_model(self): return self.model
    async def get_image(self, _image_id): return self.image
    async def product_image_ids(self, _product_id): return [self.image.id]
    async def existing_embedding(self, _image_id, _model_id): return self.existing
    async def mark_processing(self, _image, _model_id):
        self.processing_calls += 1
        return True
    async def mark_ready_and_processed(self, *args):
        self.ready_calls += 1
        if args[0] is not None:
            self.completed.append(args[0])
    async def mark_color_signature_and_processed(self, event_id, *_args): self.completed.append(event_id)
    async def mark_processed(self, event_id, _event_type, _event_version): self.completed.append(event_id)
    async def mark_failed(self, image_id, model_id, error, failure_code="PermanentEventError"):
        self.failed.append((image_id, model_id, error, failure_code))
    async def mark_retry_pending(self, image_id, model_id, error):
        self.retry_pending.append((image_id, model_id, error))


class FakePipeline:
    def __init__(self, error=None): self.error = error
    async def download_and_normalize(self, _url, _public_id):
        if self.error:
            raise self.error
        return NormalizedImage(b"jpeg", "a" * 64, 10, 10, "JPEG", (1.0,) + (0.0,) * 13)


def test_duplicate_event_is_idempotent():
    async def scenario():
        event = make_event()
        repo = FakeRepository(event, processed=True)
        await CatalogIndexer(repo, FakePipeline(), FakeEmbeddingProvider(4)).handle(event)
        assert repo.processing_calls == repo.ready_calls == 0
    asyncio.run(scenario())


def test_ready_hash_skips_provider_and_completes_event():
    class FailingProvider:
        async def embed_document(self, _image, _text=None):
            raise AssertionError("provider must not be called")

    async def scenario():
        event = make_event()
        repo = FakeRepository(event, existing=ExistingEmbedding("READY", "a" * 64, (1.0,) + (0.0,) * 13))
        await CatalogIndexer(repo, FakePipeline(), FailingProvider()).handle(event)
        assert repo.processing_calls == repo.ready_calls == 0
        assert repo.completed == [event.event_id]
    asyncio.run(scenario())


def test_changed_asset_hash_recomputes_embedding():
    async def scenario():
        event = make_event()
        repo = FakeRepository(
            event,
            existing=ExistingEmbedding("READY", "b" * 64, (1.0,) + (0.0,) * 13),
        )
        await CatalogIndexer(repo, FakePipeline(), FakeEmbeddingProvider(4)).handle(event)
        assert repo.processing_calls == 1
        assert repo.ready_calls == 1
        assert repo.completed == [event.event_id]

    asyncio.run(scenario())


def test_active_image_becomes_ready_and_event_is_processed():
    async def scenario():
        event = make_event()
        repo = FakeRepository(event)
        await CatalogIndexer(repo, FakePipeline(), FakeEmbeddingProvider(4)).handle(event)
        assert repo.processing_calls == 1
        assert repo.ready_calls == 1
        assert repo.completed == [event.event_id]
    asyncio.run(scenario())


def test_inactive_product_does_not_call_provider():
    async def scenario():
        event = make_event()
        repo = FakeRepository(event, status="INACTIVE")
        await CatalogIndexer(repo, FakePipeline(), FakeEmbeddingProvider(4)).handle(event)
        assert repo.processing_calls == repo.ready_calls == 0
        assert repo.completed == [event.event_id]
    asyncio.run(scenario())


def test_permanent_image_error_marks_embedding_failed():
    async def scenario():
        event = make_event()
        repo = FakeRepository(event)
        indexer = CatalogIndexer(repo, FakePipeline(ImagePipelineError("bad image")), FakeEmbeddingProvider(4))
        with pytest.raises(PermanentEventError):
            await indexer.handle(event)
        assert len(repo.failed) == 1
    asyncio.run(scenario())


def test_transient_provider_error_releases_processing_claim_for_retry():
    class FailingProvider:
        async def embed_document(self, _image, _text=None):
            raise RetryableEventError("provider busy")

    async def scenario():
        event = make_event()
        repo = FakeRepository(event)
        indexer = CatalogIndexer(repo, FakePipeline(), FailingProvider())
        with pytest.raises(RetryableEventError):
            await indexer.handle(event)
        assert repo.processing_calls == 1
        assert len(repo.retry_pending) == 1
        assert repo.failed == []

    asyncio.run(scenario())
