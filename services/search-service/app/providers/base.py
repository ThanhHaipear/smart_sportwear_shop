from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class EmbeddingUsage:
    image_pixels: int = 0
    text_tokens: int = 0


@dataclass(frozen=True, slots=True)
class EmbeddingResult:
    vector: tuple[float, ...]
    model: str
    dimensions: int
    usage: EmbeddingUsage

    def validate_dimensions(self, expected: int) -> "EmbeddingResult":
        if self.dimensions != expected or len(self.vector) != expected:
            raise ValueError(f"Embedding dimension mismatch: expected {expected}, received {len(self.vector)}")
        return self


class MultimodalEmbeddingProvider(Protocol):
    async def embed_document(self, image: bytes, text: str | None = None) -> EmbeddingResult: ...

    async def embed_query(self, image: bytes) -> EmbeddingResult: ...
