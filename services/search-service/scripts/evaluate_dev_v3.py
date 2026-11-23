"""Compare pinned SigLIP2 view and ranking settings on the DEV v3 split."""

from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
from hashlib import md5, sha256
from importlib.metadata import version
from io import BytesIO
from itertools import product
import json
import math
import os
from pathlib import Path
from time import perf_counter

from PIL import Image
import psycopg
from psycopg.rows import dict_row

from app.config import Settings
from app.providers.siglip2 import SIGLIP2_DIMENSIONS, SIGLIP2_NAME, SIGLIP2_REVISION, Siglip2EmbeddingProvider
from app.services.image_pipeline import ImagePipeline
from app.services.visual_ranking import category_group, rerank_visual_candidates


BASELINE = {
    "view": "full", "categorySource": "siglip2", "categoryWeight": 0.60,
    "minSimilarity": 0.755936, "unknownRatio": None,
}
GATES = {"recallAt5": 0.80, "categoryAccuracy": 0.90, "unknownRejection": 0.80}
LIMIT = 20


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def read_catalog(dsn: str) -> dict:
    with psycopg.connect(dsn, row_factory=dict_row, connect_timeout=8) as db:
        db.execute("set transaction isolation level repeatable read read only")
        rows = db.execute("""
            select e.product_id::text,e.image_id::text,e.embedding::text as vector,d.product_type,e.model
            from ai_search.image_embeddings e join ai_search.search_documents d using(product_id)
            where e.status='READY' and d.status='ACTIVE'
            order by e.image_id
        """).fetchall()
        checksum = db.execute("""
            select md5(string_agg(e.image_id::text||e.embedding::text,',' order by e.image_id)) as checksum
            from ai_search.image_embeddings e join ai_search.search_documents d using(product_id)
            where e.status='READY' and d.status='ACTIVE'
        """).fetchone()["checksum"]
        return {"checksum": checksum, "rows": rows}


def crop_view(content: bytes, mode: str) -> bytes:
    with Image.open(BytesIO(content)) as image:
        width, height = image.size
        x, y = width // 8, height // 8
        output = BytesIO()
        box = (x, y, width - x, height - y) if mode == "center" else (x, 0, width - x, 3 * height // 4)
        image.crop(box).save(output, "JPEG", quality=88, optimize=True)
        return output.getvalue()


def grouped(vector, catalog):
    norm = math.hypot(*vector)
    best = {}
    for row, image_vector, image_norm in catalog:
        score = math.fsum(a * b for a, b in zip(vector, image_vector, strict=True)) / (norm * image_norm)
        entry = {
            "product_id": row["product_id"], "image_id": row["image_id"],
            "product_type": row["product_type"], "similarity": score,
        }
        existing = best.get(row["product_id"])
        if existing is None or (-score, row["image_id"]) < (-existing["similarity"], existing["image_id"]):
            best[row["product_id"]] = entry
    return list(best.values())


def combine_views(full, center):
    by_product = {}
    for row in full["raw"] + center["raw"]:
        selected = by_product.get(row["product_id"])
        if selected is None or (-row["similarity"], row["image_id"]) < (-selected["similarity"], selected["image_id"]):
            by_product[row["product_id"]] = row
    return {
        "raw": list(by_product.values()),
        "scores": {group: (score + center["scores"][group]) / 2 for group, score in full["scores"].items()},
    }


async def generate_features(dataset, manifest_path, snapshot, settings):
    provider = Siglip2EmbeddingProvider(settings)
    pipeline = ImagePipeline(settings)
    catalog = []
    for row in snapshot["rows"]:
        if row["model"] != settings.visual_embedding_model:
            raise ValueError("Catalog model does not match the query model")
        vector = json.loads(row["vector"])
        catalog.append((row, vector, math.hypot(*vector)))
    await provider.prepare()
    features, durations = [], []
    for sample in dataset["samples"]:
        original = (manifest_path.parent / sample["image"]).read_bytes()
        if sha256(original).hexdigest() != sample["sha256"]:
            raise ValueError(f"DEV asset checksum mismatch: {sample['id']}")
        started = perf_counter()
        normalized = pipeline.normalize(original, "image/jpeg").content
        views = {}
        for view, content in (
            ("full", normalized), ("center", crop_view(normalized, "center")),
            ("upper", crop_view(normalized, "upper")),
        ):
            result = await provider.embed_query(content)
            vector = result.validate_dimensions(settings.visual_embedding_dims).vector
            views[view] = {"raw": grouped(vector, catalog), "scores": await provider.category_scores(vector)}
        views["full+center"] = combine_views(views["full"], views["center"])
        views["full+upper"] = combine_views(views["full"], views["upper"])
        durations.append(round((perf_counter() - started) * 1000, 3))
        features.append(views)
    return features, durations


def ranked_rows(features, config):
    view = features[config["view"]]
    category_scores = view["scores"]
    strongest_known = max(value for group, value in category_scores.items() if group != "unknown")
    if config["unknownRatio"] is not None and category_scores["unknown"] >= config["unknownRatio"] * strongest_known:
        return []
    support = category_scores if config["categorySource"] == "siglip2" else None
    if config["categorySource"] in {"blended", "adaptive"}:
        similarities = {}
        for row in view["raw"]:
            group = category_group(row["product_type"])
            if group:
                similarities.setdefault(group, []).append(row["similarity"])
        catalog_support = {group: sum(sorted(scores, reverse=True)[:3]) / min(3, len(scores))
                           for group, scores in similarities.items()}
        strongest_catalog = max(catalog_support.values(), default=0.0)
        ranked_known = sorted((prob for group, prob in category_scores.items() if group != "unknown"), reverse=True)
        model_weight = 0.75
        if config["categorySource"] == "adaptive":
            margin = (ranked_known[0] - ranked_known[1]) / strongest_known
            model_weight = 0.35 + 0.50 * margin
        support = {
            group: model_weight * prob / strongest_known
            + ((1 - model_weight) * catalog_support.get(group, 0.0) / strongest_catalog if strongest_catalog > 0 else 0.0)
            for group, prob in category_scores.items() if group != "unknown"
        }
    ranked = rerank_visual_candidates(view["raw"], category_weight=config["categoryWeight"], query_category_scores=support)
    return [row for row in ranked[:LIMIT] if row["similarity"] >= config["minSimilarity"]]


def summarize(cases):
    known = [case for case in cases if case["group"] != "unknown"]
    unknown = [case for case in cases if case["group"] == "unknown"]
    return {
        "knownQueries": len(known),
        "unknownQueries": len(unknown),
        "recallAt5": sum(case["recall"] for case in known) / len(known) if known else None,
        "categoryAccuracy": sum(case["categoryCorrect"] for case in known) / len(known) if known else None,
        "unknownRejection": sum(case["rejected"] for case in unknown) / len(unknown) if unknown else None,
        "meanReciprocalRankAt5": sum(case["reciprocalRank"] for case in known) / len(known) if known else None,
    }


def evaluate(samples, features, labels, config, partition=None):
    cases = []
    for sample, feature in zip(samples, features, strict=True):
        if partition is not None and sample["partition"] != partition:
            continue
        rows = ranked_rows(feature, config)
        known = labels[sample["id"]]
        relevant_in_top5 = sum(row["product_id"] in known for row in rows[:5])
        reciprocal_rank = next((1 / rank for rank, row in enumerate(rows[:5], 1) if row["product_id"] in known), 0)
        cases.append({
            "queryId": sample["id"], "group": sample["group"], "partition": sample["partition"],
            "recall": relevant_in_top5 / len(known) if known else None,
            "reciprocalRank": reciprocal_rank,
            "categoryCorrect": bool(rows) and rows[0]["category_group"] == sample["group"],
            "rejected": not rows,
            "top5ProductIds": [row["product_id"] for row in rows[:5]],
        })
    return summarize(cases), cases


def choose_config(samples, features, labels):
    options = (
        {"view": view, "categorySource": source, "categoryWeight": weight,
         "minSimilarity": threshold, "unknownRatio": ratio}
        for view, source, weight, threshold, ratio in product(
            ("full", "center", "upper", "full+center", "full+upper"),
            ("siglip2", "catalog-consensus", "blended", "adaptive"),
            (0.0, 0.25, 0.35, 0.45, 0.60, 0.70, 0.80),
            (0.45, 0.50, 0.58, 0.65, 0.70, 0.755936, 0.80, 0.85),
            (None, 0.8, 1.0, 1.25, 1.5),
        )
    )
    best_key, best, count = (), BASELINE, 0
    for config in options:
        metrics, cases = evaluate(samples, features, labels, config, "calibration")
        ratios = [metrics[metric] / target for metric, target in GATES.items()]
        group_recalls = [
            summarize([case for case in cases if case["group"] == group])["recallAt5"]
            for group in sorted({case["group"] for case in cases} - {"unknown"})
        ]
        key = (
            min(ratios), sum(min(1.0, ratio) for ratio in ratios),
            metrics["recallAt5"], metrics["categoryAccuracy"],
            metrics["meanReciprocalRankAt5"], min(group_recalls), config["view"] == "full",
            -abs(config["minSimilarity"] - BASELINE["minSimilarity"]),
            config == BASELINE, config["unknownRatio"] is None,
        )
        count += 1
        if key > best_key:
            best_key, best = key, config
    return best, count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--catalog-vectors", type=Path, help="Replay a local export of the Catalog vector snapshot")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    dataset = load(args.manifest)
    identity = dataset["dataset"]
    if (identity["split"], identity["name"], identity["status"]) != ("DEV", "image-search-dev-v3", "FROZEN"):
        raise ValueError("This tuning script only accepts frozen DEV v3")
    labels_path = args.manifest.with_name("labels.json")
    labels = {case["queryId"]: {row["productId"] for row in case["relevantProducts"] if row["grade"] >= 2}
              for case in load(labels_path)["cases"]}
    if not args.catalog_vectors and not os.environ.get("DATABASE_URL"):
        parser.error("Set the Search DATABASE_URL or supply --catalog-vectors")
    snapshot = load(args.catalog_vectors) if args.catalog_vectors else read_catalog(os.environ["DATABASE_URL"])
    if not snapshot["rows"]:
        raise ValueError("Catalog snapshot has no READY image vectors for active products")
    ordered_rows = sorted(snapshot["rows"], key=lambda row: row["image_id"])
    vector_data = ",".join(row["image_id"] + row["vector"] for row in ordered_rows)
    if md5(vector_data.encode("utf-8")).hexdigest() != snapshot["checksum"]:
        raise ValueError("Catalog image vector snapshot checksum mismatch")
    settings = Settings(
        _env_file=None, visual_embedding_provider="hugging_face_local", visual_embedding_device="cpu",
        visual_embedding_model=SIGLIP2_NAME, visual_embedding_revision=SIGLIP2_REVISION,
        visual_embedding_dims=SIGLIP2_DIMENSIONS, visual_embedding_preprocessing="siglip2-autoprocessor-original-v1",
        target_image_max_width=1024, target_image_max_height=1024,
    )
    features, durations = asyncio.run(generate_features(dataset, args.manifest, snapshot, settings))
    samples = dataset["samples"]
    chosen, count = choose_config(samples, features, labels)
    results = {}
    for name, config in (("baseline", BASELINE), ("selectedOnCalibration", chosen)):
        partitions = {}
        for partition in ("calibration", "DEV-check"):
            metrics, cases = evaluate(samples, features, labels, config, partition)
            partitions[partition] = {
                "metrics": metrics,
                "perGroup": {
                    group: summarize([case for case in cases if case["group"] == group])
                    for group in sorted({case["group"] for case in cases})
                },
                "queries": cases,
            }
        results[name] = {"config": config, "partitions": partitions}
    passed = all(
        all(result["metrics"][metric] >= target for metric, target in GATES.items())
        for result in results["selectedOnCalibration"]["partitions"].values()
    )
    report = {
        "schemaVersion": 1, "taskId": "HAI-14", "evaluatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": dataset["dataset"], "sampleCount": len(samples),
        "groupCounts": dict(Counter(sample["group"] for sample in samples)),
        "manifestSha256": sha256(args.manifest.read_bytes()).hexdigest(),
        "labelsSha256": sha256(labels_path.read_bytes()).hexdigest(),
        "catalog": {"images": len(snapshot["rows"]), "vectorChecksum": snapshot["checksum"]},
        "model": {"name": settings.visual_embedding_model, "revision": settings.visual_embedding_revision,
                  "preprocessing": settings.visual_embedding_preprocessing, "dimensions": settings.visual_embedding_dims},
        "ranking": {"candidateLimit": LIMIT, "cosine": "full scan", "productTieBreak": "product ID then image ID"},
        "runtime": {"device": "cpu", "torch": version("torch"), "transformers": version("transformers"),
                    "cpuThreads": os.getenv("OMP_NUM_THREADS")},
        "calibrationGridSize": count, "gates": GATES, "results": results, "failedImageEvaluations": 0,
        "threeViewOfflineP95Ms": sorted(durations)[math.ceil(0.95 * len(durations)) - 1],
        "passed": passed,
    }
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "selected": chosen, **{
        name: {partition: result["metrics"] for partition, result in item["partitions"].items()}
        for name, item in results.items()
    }}))


if __name__ == "__main__":
    main()
