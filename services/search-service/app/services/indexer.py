from uuid import UUID

from app.catalog.client import CatalogClient
from app.config import Settings
from app.persistence.repository import SearchRepository
from app.providers.factory import build_provider
from app.services.documents import canonical_text, enrich_search_classification
from app.services.image_pipeline import ImagePipeline
from app.services.text_embeddings import TextEmbedder


class CatalogIndexer:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.catalog = CatalogClient(settings)
        self.repository = SearchRepository(settings)
        self.text_embedder = TextEmbedder(settings)

    async def index(
        self,
        product_id: UUID,
        source_version: int,
        event_id: UUID | None = None,
        force: bool = False,
    ) -> str:
        if event_id and await self.repository.is_processed("catalog-index-v1", event_id):
            return "DUPLICATE"
        document = await self.catalog.index_document(product_id)
        if document is None:
            await self.repository.mark_deleted(product_id, source_version, event_id)
            return "DELETED"
        document = enrich_search_classification(document)
        if document.source_version < source_version:
            raise RuntimeError("Catalog snapshot is older than the event version")
        normalized_images: list[tuple] = []
        if self.settings.visual_search_enabled:
            pipeline = ImagePipeline(self.settings)
            for image in document.images:
                normalized = await pipeline.download_and_normalize(
                    image.url, image.public_id, asset_version=image.asset_version, checksum=image.checksum,
                )
                normalized_images.append((image, normalized))
        image_sources = [
            (image.id, image.url, normalized.sha256)
            for image, normalized in normalized_images
        ]
        if not force and await self.repository.is_current(
            document, image_sources if self.settings.visual_search_enabled else None,
        ):
            if event_id is not None:
                await self.repository.mark_processed("catalog-index-v1", event_id, document.source_version)
            return "SKIPPED"

        text_vector = await self.text_embedder.embed(canonical_text(document))
        image_vectors: list[tuple] = []
        if self.settings.visual_search_enabled:
            provider = build_provider(self.settings)
            for image, normalized in normalized_images:
                result = (await provider.embed_document(normalized.content)).validate_dimensions(
                    self.settings.visual_embedding_dims
                )
                image_vectors.append((image.id, image.url, normalized.sha256, result.vector))
        return await self.repository.upsert_document(
            document, text_vector, image_vectors if self.settings.visual_search_enabled else None, event_id,
        )
