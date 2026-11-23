"""Compare uniform and out-of-catalog prompts using frozen DEV splits only."""

from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from hashlib import sha256
from itertools import product
import json
from pathlib import Path

from app.providers.siglip2 import Siglip2EmbeddingProvider
from app.services.image_pipeline import ImagePipeline
from scripts.benchmark_test_v3 import LOCK, LockedImageSearch
from scripts.evaluate_dev_v3 import GATES, grouped, summarize
from scripts.evaluate_dev_v3_followup import CATALOG_PROMPTS, dataset, measure


ROOT = Path(__file__).resolve().parents[3]
DATASETS = (
    ("dev-v3", "image-search-dev-v3"),
    ("dev-v3-supplement", "image-search-dev-v3-supplement"),
    ("dev-v3-catalog-coverage", "image-search-dev-v3-catalog-coverage"),
)
UNIFORM_PROMPTS = {
    "uniforms:complete-kit": (
        "a full sports team kit with both jersey and matching shorts visible",
        "a complete two-piece athletic uniform with team colors on a shirt and shorts",
        "a full length photo of an athlete wearing a coordinated team sports uniform",
    ),
    "uniforms:team": (
        "a sports team wearing coordinated match kits with jersey and shorts",
        "a team lineup of athletes wearing matching full uniforms",
    ),
}
UNKNOWN_PROMPTS = {
    "unknown:scene": (
        "a wide view of a landscape with no clothing as a main subject",
        "an outdoor or indoor scene with no identifiable product as the main subject",
        "a documentary photograph of surroundings rather than a close-up of sportswear",
    ),
    "unknown:built-environment": (
        "a photo of buildings, streets or architecture without a main product",
        "a view of an interior room or outdoor public space",
    ),
    "unknown:household": (
        "an everyday household item such as tableware or decorations",
        "a kitchen or home object that is not sports equipment",
    ),
}


def collect_inputs(datasets):
    partitions = {"calibration": [], "DEV-check": []}
    fingerprints = set()
    source_partitions = {}
    manifests = {}
    for folder, name in datasets:
        path = ROOT / "datasets/image-search" / folder / "manifest.json"
        manifest, labels = dataset(path, name)
        manifests[folder] = {
            "manifestSha256": sha256(path.read_bytes()).hexdigest(),
            "labelsSha256": sha256(path.with_name("labels.json").read_bytes()).hexdigest(),
        }
        for sample in manifest["samples"]:
            partition = sample["partition"]
            source = sample["sourceGroupCode"]
            if sample["sha256"] in fingerprints or source_partitions.setdefault(source, partition) != partition:
                raise ValueError(f"Overlapping DEV sample or source split: {sample['id']}")
            fingerprints.add(sample["sha256"])
            partitions[partition].append((sample, path.parent / sample["image"], labels[sample["id"]]))
    return partitions, manifests


async def features_for(rows, engine, pipeline):
    features = []
    for sample, path, relevant in rows:
        original = path.read_bytes()
        if sha256(original).hexdigest() != sample["sha256"]:
            raise ValueError(f"DEV asset SHA-256 mismatch: {sample['id']}")
        image = pipeline.normalize(original, "image/jpeg").content
        vector = (await engine.provider.embed_query(image)).validate_dimensions(engine.settings.visual_embedding_dims).vector
        raw = grouped(vector, engine.catalog)
        for row in raw:
            if row["product_type"] == "SPORTS_TOP":
                row["subtype"] = engine.subtypes[row["product_id"]]
        scores = await engine.provider.category_scores(vector)
        features.append((sample, raw, scores, relevant))
    return features


def score(rows, config):
    bank = set(CATALOG_PROMPTS)
    if config["uniformPrompts"]:
        bank.update(UNIFORM_PROMPTS)
    if config["unknownPrompts"]:
        bank.update(UNKNOWN_PROMPTS)
    records = [
        (sample, {"full": {"raw": raw, "scores": {key: value for key, value in scores.items() if key in bank}}}, relevant)
        for sample, raw, scores, relevant in rows
    ]
    return measure(records, config)


def summarize_split(cases):
    groups = Counter(case["group"] for case in cases)
    return {
        **summarize(cases),
        "sampleCount": len(cases),
        "perGroup": {group: summarize([case for case in cases if case["group"] == group]) for group in sorted(groups)},
    }


def selection_key(metrics):
    ratios = [metrics[key] / threshold for key, threshold in GATES.items()]
    return (
        min(ratios),
        sum(min(1.0, ratio) for ratio in ratios),
        metrics["perGroup"]["uniforms"]["categoryAccuracy"],
        metrics["recallAt5"],
        metrics["categoryAccuracy"],
        metrics["unknownRejection"],
    )


def load_engine():
    engine = LockedImageSearch(LOCK)
    engine.provider = Siglip2EmbeddingProvider(
        engine.settings, category_prompts={**CATALOG_PROMPTS, **UNIFORM_PROMPTS, **UNKNOWN_PROMPTS},
    )
    return engine


async def run():
    partitions, manifests = collect_inputs(DATASETS)
    engine = load_engine()
    pipeline = ImagePipeline(engine.settings)
    baseline = {**engine.profile, "uniformPrompts": False, "unknownPrompts": False}
    calibration = await features_for(partitions["calibration"], engine, pipeline)
    configurations = [
        {**baseline, "uniformPrompts": uniform, "unknownPrompts": unknown, "unknownRatio": ratio}
        for uniform, unknown, ratio in product((False, True), (False, True), (1.0, 1.1, 1.2, 1.3))
    ]
    chosen = max(configurations, key=lambda config: selection_key(summarize_split(score(calibration, config))))
    check = await features_for(partitions["DEV-check"], engine, pipeline)
    arms = {
        "baseline": baseline,
        "uniformPromptsOnly": {**baseline, "uniformPrompts": True},
        "unknownPromptsOnly": {**baseline, "unknownPrompts": True},
        "bothPromptSets": {**baseline, "uniformPrompts": True, "unknownPrompts": True},
        "selectedOnCalibration": chosen,
    }
    results = {}
    for name, config in arms.items():
        results[name] = {"configuration": config}
        for split, rows in (("calibration", calibration), ("historicalDEVCheck", check)):
            cases = score(rows, config)
            results[name][split] = {"metrics": summarize_split(cases), "cases": cases}
    return {
        "scope": "DEV diagnostic using existing frozen splits; fresh DEV check required before locking",
        "manifests": manifests,
        "catalog": engine.lock["catalog"],
        "model": engine.lock["model"],
        "promptAdditions": {**UNIFORM_PROMPTS, **UNKNOWN_PROMPTS},
        "thresholds": GATES,
        "configurationCount": len(configurations),
        "results": results,
    }


async def check_fresh(calibration_report):
    partitions, manifests = collect_inputs((("dev-v4-focus", "image-search-dev-v4-focus"),))
    if partitions["calibration"]:
        raise ValueError("The focused split is check-only")
    reference = json.loads(calibration_report.read_text(encoding="utf-8"))
    engine = load_engine()
    if reference["catalog"] != engine.lock["catalog"] or reference["model"] != engine.lock["model"]:
        raise ValueError("The diagnostic and focused check must use the same catalog and model")
    prompts = {key: list(value) for key, value in {**UNIFORM_PROMPTS, **UNKNOWN_PROMPTS}.items()}
    if reference["promptAdditions"] != prompts:
        raise ValueError("The diagnostic and focused check must use the same prompts")
    check = await features_for(partitions["DEV-check"], engine, ImagePipeline(engine.settings))
    results = {}
    for name, arm in reference["results"].items():
        config = arm["configuration"]
        cases = score(check, config)
        results[name] = {"configuration": config, "metrics": summarize_split(cases), "cases": cases}
    return {
        "scope": "Fresh DEV check limited to uniforms and out-of-catalog images",
        "manifests": manifests,
        "calibrationEvidenceSha256": sha256(calibration_report.read_bytes()).hexdigest(),
        "catalog": engine.lock["catalog"],
        "model": engine.lock["model"],
        "results": results,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check-fresh", type=Path, metavar="CALIBRATION_REPORT")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report = asyncio.run(check_fresh(args.check_fresh) if args.check_fresh else run())
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
