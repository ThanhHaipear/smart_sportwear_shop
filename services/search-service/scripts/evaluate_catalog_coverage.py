"""Compare original and augmented Catalog vectors on a frozen fresh DEV check."""

from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
from hashlib import md5, sha256
from importlib.metadata import version
import json
import math
import os
from pathlib import Path
from time import perf_counter
from unittest.mock import patch
from uuid import NAMESPACE_URL, uuid5

from app.config import Settings
from app.providers import siglip2
from app.services.image_pipeline import ImagePipeline
from scripts.evaluate_dev_v3 import GATES, LIMIT, grouped, load, read_catalog, summarize
from scripts.evaluate_dev_v3_followup import CATALOG_PROMPTS, catalog_top_subtypes, dataset, measure, rank


def file_sha(path: Path) -> str:
    return sha256(path.read_bytes()).hexdigest()


def snapshot_checksum(rows: list[dict]) -> str:
    ordered = sorted(rows, key=lambda row: row["image_id"])
    text = ",".join(row["image_id"] + row["vector"] for row in ordered)
    return md5(text.encode("utf-8")).hexdigest()


def verified_vectors(snapshot: dict) -> list[tuple[dict, list[float], float]]:
    if not snapshot["rows"] or snapshot_checksum(snapshot["rows"]) != snapshot["checksum"]:
        raise ValueError("Catalog vector snapshot checksum mismatch")
    vectors = []
    for row in snapshot["rows"]:
        vector = json.loads(row["vector"])
        if len(vector) != siglip2.SIGLIP2_DIMENSIONS or row["model"] != siglip2.SIGLIP2_NAME:
            raise ValueError("Catalog embedding dimensions or model mismatch")
        vectors.append((row, vector, math.hypot(*vector)))
    return vectors


async def run(manifest_path: Path, profile_path: Path, snapshot: dict) -> tuple[dict, dict]:
    manifest, labels = dataset(manifest_path, "image-search-dev-v3-catalog-coverage")
    if {row["partition"] for row in manifest["samples"]} != {"DEV-check"}:
        raise ValueError("The paired comparison requires a frozen check-only split")
    profile_evidence = load(profile_path)
    config = profile_evidence["experiments"]["catalog-subtype-consensus"]["results"]["selected"]["config"]
    if config["view"] != "full" or config["pairMode"] != "off" or config.get("subtypeWeight") is None:
        raise ValueError("Use the preselected single-view catalog-subtype profile")
    baseline = verified_vectors(snapshot)
    if snapshot["checksum"] != profile_evidence["catalog"]["vectorChecksum"]:
        raise ValueError("The original catalog must match the preselected profile's snapshot")
    root = manifest_path.resolve().parents[3]
    subtypes = catalog_top_subtypes(root)
    pilot = root / "catalog/pilot-v1"
    pilot_products = json.loads((pilot / "manifest.json").read_text(encoding="utf-8-sig"))["products"]
    pilot_imports = json.loads((pilot / "import-result.json").read_text(encoding="utf-8-sig"))["products"]
    ids_by_slug = {row["slug"]: row["id"] for row in pilot_imports}
    files_by_slug = {
        row["slug"]: {f"catalog/pilot-v1/{image['path']}": image["sha256"] for image in row["images"]}
        for row in pilot_products
    }
    references_path = manifest_path.with_name("reference-inventory.json")
    sources = load(references_path)["images"]
    products = {row["product_id"]: row["product_type"] for row, _, _ in baseline}
    query_hashes = {row["sha256"] for row in manifest["samples"]}
    reference_hashes = set()
    coverage = {}
    approved_views = {"worn-front", "worn-three-quarter-close"}
    for source in sources:
        product_id, digest, slug = source["productId"], source["sha256"], source["productSlug"]
        if (
            digest in query_hashes or digest in reference_hashes
            or products.get(product_id) != source["productType"]
            or subtypes.get(product_id) != "tops:jacket"
            or ids_by_slug.get(slug) != product_id
            or files_by_slug.get(slug, {}).get(source["sourceReference"]) != source["sourceReferenceSha256"]
            or source["view"] not in approved_views
            or file_sha(root / source["sourceReference"]) != source["sourceReferenceSha256"]
            or file_sha(manifest_path.parent / source["image"]) != digest
        ):
            raise ValueError(f"Invalid or overlapping Catalog reference: {source['image']}")
        reference_hashes.add(digest)
        coverage.setdefault(product_id, []).append(source["view"])
    if len(coverage) != 2 or any(Counter(views) != Counter(approved_views) for views in coverage.values()):
        raise ValueError("Expected two additional views for each of the two jacket designs")
    settings = Settings(
        _env_file=None, visual_embedding_provider="hugging_face_local", visual_embedding_device="cpu",
        visual_embedding_model=siglip2.SIGLIP2_NAME, visual_embedding_revision=siglip2.SIGLIP2_REVISION,
        visual_embedding_dims=siglip2.SIGLIP2_DIMENSIONS,
        visual_embedding_preprocessing="siglip2-autoprocessor-original-v1",
        target_image_max_width=1024, target_image_max_height=1024,
    )
    provider = siglip2.Siglip2EmbeddingProvider(settings)
    pipeline = ImagePipeline(settings)
    await provider.prepare()
    added, added_report = [], []
    for source in sources:
        raw = (manifest_path.parent / source["image"]).read_bytes()
        normalized = pipeline.normalize(raw, "image/png").content
        embedding = await provider.embed_document(normalized)
        vec = embedding.validate_dimensions(settings.visual_embedding_dims).vector
        image_id = str(uuid5(NAMESPACE_URL, f"hai14-catalog-coverage:{source['productId']}:{source['sha256']}"))
        added.append((
            {"product_id": source["productId"], "image_id": image_id, "product_type": source["productType"]},
            vec, math.hypot(*vec),
        ))
        added_report.append({
            "imageId": image_id, "productId": source["productId"], "image": source["image"],
            "sha256": source["sha256"], "embeddingSha256": sha256(json.dumps(vec).encode("utf-8")).hexdigest(),
        })
    augmented = baseline + added
    augmented_rows = snapshot["rows"] + [
        {**row, "model": siglip2.SIGLIP2_NAME, "vector": json.dumps(vector, separators=(",", ":"))}
        for row, vector, _ in added
    ]
    augmented_snapshot = {"checksum": snapshot_checksum(augmented_rows), "rows": augmented_rows}
    records = {"original": [], "augmented": []}
    durations = []
    with patch.object(siglip2, "CATEGORY_PROMPTS", CATALOG_PROMPTS):
        for sample in manifest["samples"]:
            raw = (manifest_path.parent / sample["image"]).read_bytes()
            if sha256(raw).hexdigest() != sample["sha256"]:
                raise ValueError(f"Query checksum mismatch: {sample['id']}")
            started = perf_counter()
            normalized = pipeline.normalize(raw, "image/jpeg").content
            embedding = await provider.embed_query(normalized)
            vec = embedding.validate_dimensions(settings.visual_embedding_dims).vector
            scores = await provider.category_scores(vec)
            for name, catalog in (("original", baseline), ("augmented", augmented)):
                rows = grouped(vec, catalog)
                for row in rows:
                    if row["product_type"] == "SPORTS_TOP":
                        row["subtype"] = subtypes[row["product_id"]]
                records[name].append((sample, {"full": {"raw": rows, "scores": scores}}, labels[sample["id"]]))
            durations.append((perf_counter() - started) * 1000)
    jackets = {sample["id"] for sample in manifest["samples"] if sample["subtype"] == "training-jacket"}
    if not jackets:
        raise ValueError("The fresh DEV dataset has no jacket queries")
    results = {}
    added_ids = {row["imageId"] for row in added_report}
    for name, rows in records.items():
        cases = measure(rows, config)
        for case, (_, features, _) in zip(cases, rows, strict=True):
            top = rank(features, config)[:5]
            case["top5ExperimentalImageIds"] = [row["image_id"] for row in top if row["image_id"] in added_ids]
        results[name] = {
            "overall": summarize(cases),
            "jackets": summarize([case for case in cases if case["queryId"] in jackets]),
            "controls": summarize([case for case in cases if case["queryId"] not in jackets]),
            "queries": cases,
        }
    passed = all(results["augmented"]["overall"][name] >= target for name, target in GATES.items())
    jacket_delta = results["augmented"]["jackets"]["recallAt5"] - results["original"]["jackets"]["recallAt5"]
    return {
        "schemaVersion": 1, "taskId": "HAI-14", "evaluatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": manifest["dataset"], "manifestSha256": file_sha(manifest_path),
        "labelsSha256": file_sha(manifest_path.with_name("labels.json")),
        "referenceInventorySha256": file_sha(references_path),
        "preselectedProfileEvidenceSha256": file_sha(profile_path),
        "model": {"name": siglip2.SIGLIP2_NAME, "revision": siglip2.SIGLIP2_REVISION,
                  "dimensions": siglip2.SIGLIP2_DIMENSIONS, "preprocessing": settings.visual_embedding_preprocessing,
                  "torch": version("torch"), "transformers": version("transformers")},
        "profile": config, "candidateLimit": LIMIT, "catalogVectorChecksum": snapshot["checksum"],
        "augmentedVectorChecksum": augmented_snapshot["checksum"],
        "originalVectorCount": len(baseline), "augmentedVectorCount": len(augmented),
        "addedReferences": added_report, "gates": GATES, "results": results,
        "jacketRecallDelta": jacket_delta,
        "pairedOfflineP95Ms": round(sorted(durations)[math.ceil(.95 * len(durations)) - 1], 3),
        "productionWrites": False, "passed": passed,
        "eligibleToFreezeForTestV3": passed and jacket_delta > 0,
    }, augmented_snapshot


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--profile-evidence", type=Path, required=True)
    parser.add_argument("--catalog-vectors", type=Path)
    parser.add_argument("--augmented-snapshot-output", type=Path, help="Save the 85-vector comparison snapshot locally")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    if not args.catalog_vectors and not os.environ.get("DATABASE_URL"):
        parser.error("Set DATABASE_URL or use --catalog-vectors")
    snapshot = load(args.catalog_vectors) if args.catalog_vectors else read_catalog(os.environ["DATABASE_URL"])
    report, augmented = asyncio.run(run(args.manifest, args.profile_evidence, snapshot))
    if args.augmented_snapshot_output:
        args.augmented_snapshot_output.write_text(json.dumps(augmented, separators=(",", ":")) + "\n", encoding="utf-8")
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "passed": report["passed"], "jacketRecallDelta": report["jacketRecallDelta"],
        **{key: {name: value[name] for name in ("overall", "jackets", "controls")}
           for key, value in report["results"].items()},
    }))


if __name__ == "__main__":
    main()
