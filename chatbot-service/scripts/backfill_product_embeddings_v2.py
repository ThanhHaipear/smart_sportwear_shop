from __future__ import annotations

import argparse
import asyncio
import gc
import json
import sys
from pathlib import Path

import asyncpg

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config.settings import settings
from app.embeddings.models import (
    BGE_M3_DIMENSIONS,
    BGE_M3_NAME,
    BGE_M3_REVISION,
    SIGLIP2_DIMENSIONS,
    SIGLIP2_NAME,
    SIGLIP2_REVISION,
    BgeM3TextEmbedder,
    Siglip2ImageEmbedder,
)
from app.embeddings.worker import ProductEmbeddingWorker


class AlreadyReadyEmbedder:
    def encode(self, _source):
        raise AssertionError("Model-free retry attempted to encode a source")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Backfill versioned 1C.2 product embeddings")
    parser.add_argument("--modality", choices=("text", "image", "all"), default="all")
    parser.add_argument("--coverage-only", action="store_true")
    parser.add_argument("--verify", action="store_true")
    return parser.parse_args()


def release_model(model) -> None:
    del model
    gc.collect()
    try:
        import torch

        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except ImportError:
        pass


async def run() -> None:
    args = arguments()
    if not settings.DB_WRITE_URL:
        raise RuntimeError("DB_WRITE_URL is required")
    dsn = settings.DB_WRITE_URL.replace("postgresql+asyncpg://", "postgresql://", 1)
    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2)
    worker = ProductEmbeddingWorker(pool, settings)
    results: list[dict] = []
    try:
        if args.coverage_only or args.verify:
            output = {"coverage": await worker.coverage()}
            if args.verify:
                output["verification"] = await worker.verify()
            print(json.dumps(output))
            return
        if args.modality in {"text", "all"}:
            model = await worker.active_model("PRODUCT_TEXT")
            if (model.model_name, model.model_version, model.dimensions) != (
                BGE_M3_NAME, BGE_M3_REVISION, BGE_M3_DIMENSIONS
            ):
                raise RuntimeError("ACTIVE PRODUCT_TEXT registry row does not match the supported runtime")
            job_id = await worker.create_job("PRODUCT_TEXT", model.id)
            embedder = AlreadyReadyEmbedder()
            if await worker.job_requires_model(job_id, "PRODUCT_TEXT", model.id):
                embedder = BgeM3TextEmbedder(
                    revision=model.model_version,
                    device=settings.EMBEDDING_DEVICE,
                    max_length=settings.EMBEDDING_MAX_TEXT_LENGTH,
                    cache_dir=settings.EMBEDDING_CACHE_DIR or None,
                )
            results.append(await worker.run_text_job(job_id, model, embedder))
            release_model(embedder)
        if args.modality in {"image", "all"}:
            model = await worker.active_model("PRODUCT_IMAGE")
            if (model.model_name, model.model_version, model.dimensions) != (
                SIGLIP2_NAME, SIGLIP2_REVISION, SIGLIP2_DIMENSIONS
            ):
                raise RuntimeError("ACTIVE PRODUCT_IMAGE registry row does not match the supported runtime")
            job_id = await worker.create_job("PRODUCT_IMAGE", model.id)
            embedder = AlreadyReadyEmbedder()
            if await worker.job_requires_model(job_id, "PRODUCT_IMAGE", model.id):
                embedder = Siglip2ImageEmbedder(
                    revision=model.model_version,
                    device=settings.EMBEDDING_DEVICE,
                    cache_dir=settings.EMBEDDING_CACHE_DIR or None,
                )
            results.append(await worker.run_image_job(job_id, model, embedder))
            release_model(embedder)
        print(json.dumps({"jobs": results, "coverage": await worker.coverage()}))
    finally:
        await pool.close()


if __name__ == "__main__":
    asyncio.run(run())
