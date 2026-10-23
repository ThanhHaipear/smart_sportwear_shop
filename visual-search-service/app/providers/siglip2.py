from __future__ import annotations

import asyncio
import math
from io import BytesIO
from typing import Any, Sequence

from PIL import Image

from app.config import Settings

from .base import EmbeddingResult, EmbeddingUsage


SIGLIP2_NAME = "google/siglip2-base-patch16-224"
SIGLIP2_REVISION = "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2"
SIGLIP2_DIMENSIONS = 768


class Siglip2ProviderError(RuntimeError):
    pass


def l2_normalize(values: Sequence[float], expected_dimensions: int) -> tuple[float, ...]:
    vector = tuple(float(value) for value in values)
    if len(vector) != expected_dimensions:
        raise ValueError(f"Expected {expected_dimensions} dimensions, got {len(vector)}")
    if not all(math.isfinite(value) for value in vector):
        raise ValueError("Embedding contains a non-finite value")
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 1e-12:
        raise ValueError("Embedding has zero L2 norm")
    return tuple(value / norm for value in vector)


class Siglip2EmbeddingProvider:
    """Pinned local SigLIP2 query embedder for the V2 image index."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._torch: Any | None = None
        self._processor: Any | None = None
        self._model: Any | None = None

    async def embed_document(self, image: bytes, text: str | None = None) -> EmbeddingResult:
        return await self.embed_query(image)

    async def embed_query(self, image: bytes) -> EmbeddingResult:
        if not image:
            raise ValueError("image is required")
        try:
            vector, pixels = await asyncio.to_thread(self._encode, image)
        except (ValueError, RuntimeError):
            raise
        except Exception as exc:
            raise Siglip2ProviderError("SigLIP2 failed to embed the image") from exc
        return EmbeddingResult(
            vector=vector,
            model=SIGLIP2_NAME,
            dimensions=SIGLIP2_DIMENSIONS,
            usage=EmbeddingUsage(image_pixels=pixels),
        )

    def _load(self) -> None:
        if self._model is not None:
            return
        try:
            import torch
            from transformers import AutoModel, AutoProcessor
        except ImportError as exc:
            raise RuntimeError(
                "Install requirements-embeddings.txt before enabling local SigLIP2"
            ) from exc

        device = self.settings.visual_embedding_device
        if device == "auto":
            device = "cuda" if torch.cuda.is_available() else "cpu"
        if device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("CUDA was requested but is not available")
        dtype = torch.float16 if device == "cuda" else torch.float32
        model_args = {
            "revision": self.settings.visual_embedding_revision,
            "cache_dir": self.settings.visual_embedding_cache_dir or None,
        }
        self._torch = torch
        self._processor = AutoProcessor.from_pretrained(SIGLIP2_NAME, use_fast=False, **model_args)
        self._model = AutoModel.from_pretrained(
            SIGLIP2_NAME,
            dtype=dtype,
            low_cpu_mem_usage=True,
            **model_args,
        ).to(device)
        self._model.eval()

    def _encode(self, image_bytes: bytes) -> tuple[tuple[float, ...], int]:
        self._load()
        with Image.open(BytesIO(image_bytes)) as source:
            image = source.convert("RGB")
            pixels = image.width * image.height
            inputs = self._processor(images=image, return_tensors="pt")
        inputs = {
            key: (
                value.to(device=self._model.device, dtype=self._model.dtype)
                if value.is_floating_point()
                else value.to(device=self._model.device)
            )
            for key, value in inputs.items()
        }
        with self._torch.inference_mode():
            features = self._model.get_image_features(**inputs)
        return l2_normalize(features[0].float().cpu().tolist(), SIGLIP2_DIMENSIONS), pixels
