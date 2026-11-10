import hashlib
import math

from .base import EmbeddingResult, EmbeddingUsage


class FakeEmbeddingProvider:
    """Deterministic, offline provider for tests and local contract development."""

    def __init__(self, dimensions: int = 1024, model: str = "fake-multimodal-v1") -> None:
        if dimensions <= 0:
            raise ValueError("dimensions must be positive")
        self.dimensions = dimensions
        self.model = model

    async def embed_document(self, image: bytes, text: str | None = None) -> EmbeddingResult:
        if not image:
            raise ValueError("image is required")
        return self._embed(b"document\0" + image + b"\0" + (text or "").encode("utf-8"))

    async def embed_query(self, image: bytes) -> EmbeddingResult:
        if not image:
            raise ValueError("image is required")
        return self._embed(b"query\0" + image)

    def _embed(self, payload: bytes) -> EmbeddingResult:
        values: list[float] = []
        counter = 0
        while len(values) < self.dimensions:
            digest = hashlib.sha256(payload + counter.to_bytes(4, "big")).digest()
            values.extend((byte - 127.5) / 127.5 for byte in digest)
            counter += 1
        values = values[: self.dimensions]
        norm = math.sqrt(sum(value * value for value in values)) or 1.0
        vector = tuple(value / norm for value in values)
        return EmbeddingResult(vector, self.model, self.dimensions, EmbeddingUsage())
