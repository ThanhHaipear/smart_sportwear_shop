from uuid import UUID

from app.catalog.client import CatalogClient
from app.config import Settings
from app.persistence.repository import SearchRepository
from app.providers.factory import build_provider
from app.services.documents import canonical_text
from app.services.image_pipeline import ImagePipeline
from app.services.text_embeddings import TextEmbedder


class CatalogIndexer:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.catalog = CatalogClient(settings)
        self.repository = SearchRepository(settings)
        self.text_embedder = TextEmbedder(settings)

    async def index(self, product_id: UUID, source_version: int, event_id: UUID | None = None) -> str:
        if event_id and await self.repository.is_processed("catalog-index-v1", event_id):
            return "DUPLICATE"
        document = await self.catalog.index_document(product_id)
        if document is None:
            await self.repository.mark_deleted(product_id, source_version, event_id)
            return "DELETED"
        if document.source_version < source_version:
            raise RuntimeError("Catalog snapshot is older than the event version")
        text_vector = await self.text_embedder.embed(canonical_text(document))
        image_vectors: list[tuple] = []
        if self.settings.visual_search_enabled:
            provider = build_provider(self.settings)
            pipeline = ImagePipeline(self.settings)
            for image in document.images:
                normalized = await pipeline.download_and_normalize(image.url, image.public_id)
                result = (await provider.embed_document(normalized.content)).validate_dimensions(
                    self.settings.visual_embedding_dims
                )
                image_vectors.append((image.id, image.url, normalized.sha256, result.vector))
        return await self.repository.upsert_document(document, text_vector, image_vectors, event_id)
