from __future__ import annotations

import math
from io import BytesIO
from typing import Sequence


BGE_M3_NAME = "BAAI/bge-m3"
BGE_M3_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
BGE_M3_DIMENSIONS = 1024
SIGLIP2_NAME = "google/siglip2-base-patch16-224"
SIGLIP2_REVISION = "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2"
SIGLIP2_DIMENSIONS = 768


def l2_normalize(values: Sequence[float], expected_dimensions: int) -> list[float]:
    vector = [float(value) for value in values]
    if len(vector) != expected_dimensions:
        raise ValueError(f"Expected {expected_dimensions} dimensions, got {len(vector)}")
    if not all(math.isfinite(value) for value in vector):
        raise ValueError("Embedding contains a non-finite value")
    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 1e-12:
        raise ValueError("Embedding has zero L2 norm")
    return [value / norm for value in vector]


def pgvector_literal(values: Sequence[float]) -> str:
    return "[" + ",".join(format(value, ".9g") for value in values) + "]"


def resolve_device(requested: str) -> str:
    value = requested.strip().lower()
    if value not in {"auto", "cpu", "cuda"}:
        raise ValueError("EMBEDDING_DEVICE must be auto, cpu or cuda")
    if value == "cpu":
        return value
    try:
        import torch
    except ImportError:
        if value == "cuda":
            raise RuntimeError("CUDA was requested but PyTorch is not installed")
        return "cpu"
    if value == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but is not available")
    return "cuda" if torch.cuda.is_available() else "cpu"


class BgeM3TextEmbedder:
    dimensions = BGE_M3_DIMENSIONS

    def __init__(
        self,
        *,
        revision: str,
        device: str = "auto",
        max_length: int = 512,
        cache_dir: str | None = None,
    ) -> None:
        if revision != BGE_M3_REVISION:
            raise ValueError(f"Unsupported BGE-M3 revision: {revision}")
        try:
            import torch
            from sentence_transformers import SentenceTransformer
        except ImportError as exc:
            raise RuntimeError(
                "Install requirements-embeddings.txt before running the text worker"
            ) from exc
        self.device = resolve_device(device)
        model_kwargs = {"torch_dtype": torch.float16} if self.device == "cuda" else {}
        self.model = SentenceTransformer(
            BGE_M3_NAME,
            revision=revision,
            device=self.device,
            cache_folder=cache_dir or None,
            model_kwargs=model_kwargs,
        )
        self.model.max_seq_length = max_length

    def encode(self, text: str) -> list[float]:
        result = self.model.encode(
            [text],
            batch_size=1,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )[0]
        return l2_normalize(result.tolist(), self.dimensions)


class Siglip2ImageEmbedder:
    dimensions = SIGLIP2_DIMENSIONS

    def __init__(
        self,
        *,
        revision: str,
        device: str = "auto",
        cache_dir: str | None = None,
    ) -> None:
        if revision != SIGLIP2_REVISION:
            raise ValueError(f"Unsupported SigLIP2 revision: {revision}")
        try:
            import torch
            from transformers import AutoModel, AutoProcessor
        except ImportError as exc:
            raise RuntimeError(
                "Install requirements-embeddings.txt before running the image worker"
            ) from exc
        self.torch = torch
        self.device = resolve_device(device)
        dtype = torch.float16 if self.device == "cuda" else torch.float32
        kwargs = {"revision": revision, "cache_dir": cache_dir or None}
        # Explicitly keep the processor used for the current index; a future
        # Transformers default must not silently change embedding semantics.
        self.processor = AutoProcessor.from_pretrained(SIGLIP2_NAME, use_fast=False, **kwargs)
        self.model = AutoModel.from_pretrained(
            SIGLIP2_NAME,
            dtype=dtype,
            low_cpu_mem_usage=True,
            **kwargs,
        ).to(self.device)
        self.model.eval()

    def encode(self, image_bytes: bytes) -> list[float]:
        try:
            from PIL import Image
        except ImportError as exc:
            raise RuntimeError("Pillow is required by the image worker") from exc
        with Image.open(BytesIO(image_bytes)) as source:
            image = source.convert("RGB")
            inputs = self.processor(images=image, return_tensors="pt")
        inputs = {
            key: (value.to(device=self.device, dtype=self.model.dtype)
                  if value.is_floating_point() else value.to(device=self.device))
            for key, value in inputs.items()
        }
        with self.torch.inference_mode():
            features = self.model.get_image_features(**inputs)
        return l2_normalize(features[0].float().cpu().tolist(), self.dimensions)
