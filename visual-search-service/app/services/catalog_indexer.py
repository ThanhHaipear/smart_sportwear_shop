import time
from uuid import UUID

from app.messaging.events import CatalogEvent, CatalogEventType
from app.messaging.retry import PermanentEventError, RetryableEventError, classify_processing_error
from app.persistence.repository import ModelVersion, VisualSearchRepository
from app.providers.base import MultimodalEmbeddingProvider

from .image_pipeline import ImagePipeline


class CatalogIndexer:
    def __init__(
        self,
        repository: VisualSearchRepository,
        pipeline: ImagePipeline,
        provider: MultimodalEmbeddingProvider,
    ):
        self.repository = repository
        self.pipeline = pipeline
        self.provider = provider

    async def handle(self, event: CatalogEvent) -> None:
        if await self.repository.is_processed(event.event_id):
            return
        if event.event_type in {
            CatalogEventType.PRODUCT_IMAGE_DELETED,
            CatalogEventType.PRODUCT_DEACTIVATED,
            CatalogEventType.PRODUCT_DELETED,
        }:
            await self._complete(event)
            return

        model = await self.repository.active_model()
        if model is None:
            raise RetryableEventError("No active visual embedding model is configured")

        image_ids = [event.image_id] if event.image_id else await self.repository.product_image_ids(event.product_id)
        image_ids = [image_id for image_id in image_ids if image_id is not None]
        if not image_ids:
            await self._complete(event)
            return

        for index, image_id in enumerate(image_ids):
            await self._index_image(event, image_id, model, complete_event=index == len(image_ids) - 1)

    async def _index_image(
        self, event: CatalogEvent, image_id: UUID, model: ModelVersion, complete_event: bool
    ) -> None:
        image = await self.repository.get_image(image_id)
        if image is None or image.product_id != event.product_id or image.product_status != "ACTIVE":
            if complete_event:
                await self._complete(event)
            return
        try:
            normalized = await self.pipeline.download_and_normalize(image.image_url, image.public_id)
            existing = await self.repository.existing_embedding(image.id, model.id)
            if existing and existing.status == "READY" and existing.image_hash == normalized.sha256:
                if not existing.color_signature:
                    await self.repository.mark_color_signature_and_processed(
                        event.event_id if complete_event else None,
                        event.event_type.value if complete_event else None,
                        event.event_version if complete_event else None,
                        image.id, model.id, normalized.color_signature,
                    )
                    return
                if complete_event:
                    await self._complete(event)
                return
            if not await self.repository.mark_processing(image, model.id):
                raise RetryableEventError("Image is already being processed")
            started = time.perf_counter()
            result = (await self.provider.embed_document(normalized.content)).validate_dimensions(model.dimensions)
            latency_ms = round((time.perf_counter() - started) * 1000)
            if complete_event:
                await self.repository.mark_ready_and_processed(
                    event.event_id,
                    event.event_type.value,
                    event.event_version,
                    image,
                    model,
                    normalized.sha256,
                    normalized.color_signature,
                    result,
                    latency_ms,
                )
            else:
                # Product-wide events complete only after the final image. A synthetic
                # per-image completion is deliberately avoided so redelivery can hash-skip.
                await self.repository.mark_ready_and_processed(
                    None, None, None, image, model, normalized.sha256,
                    normalized.color_signature, result, latency_ms
                )
        except Exception as error:
            classified = classify_processing_error(error)
            if isinstance(classified, PermanentEventError):
                await self.repository.mark_failed(
                    image.id, model.id, str(classified), type(classified).__name__
                )
            elif isinstance(classified, RetryableEventError):
                await self.repository.mark_retry_pending(image.id, model.id, str(classified))
            raise classified from error

    async def _complete(self, event: CatalogEvent) -> None:
        await self.repository.mark_processed(event.event_id, event.event_type.value, event.event_version)

    async def mark_exhausted(self, event: CatalogEvent, error: Exception) -> None:
        model = await self.repository.active_model()
        if model is not None:
            await self.repository.mark_failed(
                event.image_id, model.id, str(error), type(error).__name__
            )
        await self.repository.mark_job_event_failed(event.event_id, str(error), type(error).__name__)
