"""Compare one live image vector with the configured real and fake providers."""

from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

import psycopg


ROOT = Path(__file__).resolve().parents[2]
SEARCH_SERVICE = ROOT / "services/search-service"
sys.path.insert(0, str(SEARCH_SERVICE))

from app.config import Settings  # noqa: E402
from app.persistence.repository import vector_literal  # noqa: E402
from app.providers.fake import FakeEmbeddingProvider  # noqa: E402
from app.providers.siglip2 import (  # noqa: E402
    SIGLIP2_DIMENSIONS,
    SIGLIP2_NAME,
    SIGLIP2_REVISION,
    Siglip2EmbeddingProvider,
)
from app.services.image_pipeline import ImagePipeline  # noqa: E402


IMAGE_ID = "5a7e4aa5-194e-432f-87b5-f6b970bca3e3"
ASSET = ROOT / "catalog/pilot-v1/assets/PILOT-TOP-004-01.png"


async def vectors() -> tuple[list[float], list[float]]:
    settings = Settings(
        search_enabled=False,
        visual_search_enabled=True,
        visual_embedding_provider="hugging_face_local",
        visual_embedding_device="cpu",
    )
    normalized = ImagePipeline(settings).normalize(ASSET.read_bytes(), "image/png")
    real = await Siglip2EmbeddingProvider(settings).embed_query(normalized.content)
    fake = await FakeEmbeddingProvider(SIGLIP2_DIMENSIONS).embed_document(normalized.content)
    return list(real.vector), list(fake.vector)


def main() -> None:
    real, fake = asyncio.run(vectors())
    with psycopg.connect(os.environ["DATABASE_URL"]) as connection:
        row = connection.execute(
            """select model,
                      1 - (embedding <=> %s::extensions.vector) as real_similarity,
                      1 - (embedding <=> %s::extensions.vector) as fake_similarity
                 from ai_search.image_embeddings
                where image_id = %s""",
            (vector_literal(real), vector_literal(fake), IMAGE_ID),
        ).fetchone()
    print(
        json.dumps(
            {
                "imageId": IMAGE_ID,
                "databaseModel": row[0],
                "runtimeModel": SIGLIP2_NAME,
                "runtimeRevision": SIGLIP2_REVISION,
                "dimensions": SIGLIP2_DIMENSIONS,
                "similarityToRuntimeSiglip2": float(row[1]),
                "similarityToFakeDocumentProvider": float(row[2]),
            }
        )
    )


if __name__ == "__main__":
    main()
