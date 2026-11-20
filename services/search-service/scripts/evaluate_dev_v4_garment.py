"""Evaluate signals for isolated sports garments on a fresh DEV split."""

from __future__ import annotations

import argparse
import asyncio
from hashlib import sha256
from itertools import product
import json
from pathlib import Path

from app.providers.siglip2 import Siglip2EmbeddingProvider
from app.services.image_pipeline import ImagePipeline
from scripts.benchmark_test_v3 import LOCK
from scripts.evaluate_dev_v3 import GATES
from scripts.evaluate_dev_v3_followup import CATALOG_PROMPTS, measure
from scripts.evaluate_dev_v4 import (
    DATASETS, UNIFORM_PROMPTS, UNKNOWN_PROMPTS, collect_inputs, features_for, load_engine, summarize_split,
)
from scripts.evaluate_dev_v4_balanced import BALANCED_DATASET, blend_support


FOCUSED_DATASET = ("dev-v4-focus", "image-search-dev-v4-focus")
GARMENT_DATASET = ("dev-v4-garment", "image-search-dev-v4-garment")
GARMENT_PROMPTS = {
    "isolated-top": (
        "a single athletic shirt as the main subject, photographed separately",
        "a close-up crop of an athlete's torso showing a sports shirt and no shorts",
    ),
    "isolated-bottom": (
        "a single pair of sports shorts as the main subject, photographed separately",
        "a close-up crop of an athlete's hips and thighs showing only the sports shorts",
    ),
    "accessory-socks": ("a close-up of knee-high football socks", "a pair of athletic socks"),
    "accessory-guards": ("a close-up of soccer shin guards", "protective shin pads for football"),
    "accessory-wristband": ("a close-up of a fabric sports wrist sweatband", "a terry cloth athletic wristband"),
    "accessory-bottle": ("a reusable sports squeeze bottle", "an athlete holding a sports water bottle"),
}
RUN_PROMPTS = {**CATALOG_PROMPTS, **UNIFORM_PROMPTS, **UNKNOWN_PROMPTS, **GARMENT_PROMPTS}
GARMENT_GROUPS = ("tops", "bottoms", "uniforms")
ACCESSORY_KEYS = tuple(key for key in GARMENT_PROMPTS if key.startswith("accessory-"))
UNKNOWN_KEYS = tuple(key for key in CATALOG_PROMPTS if key.startswith("unknown:"))


def garment_features(raw, scores, config):
    support = {key: scores[key] for key in CATALOG_PROMPTS}
    complete_kit = max(scores[key] for key in UNIFORM_PROMPTS)
    isolated = max(scores["isolated-top"], scores["isolated-bottom"])
    isolation_margin = max(0.0, 1.0 - complete_kit / isolated)
    support["uniforms"] = blend_support(support["uniforms"], complete_kit, config["uniformBlend"])
    support["uniforms"] *= 1.0 - config["isolationWeight"] * isolation_margin
    support["accessories"] = blend_support(
        support["accessories"], max(scores[key] for key in ACCESSORY_KEYS), config["accessoryBlend"],
    )
    strongest_unknown = max(UNKNOWN_KEYS, key=scores.__getitem__)
    support[strongest_unknown] = blend_support(
        support[strongest_unknown], max(scores[key] for key in UNKNOWN_PROMPTS), config["unknownBlend"],
    )
    return {"full": {"raw": raw, "scores": support}}


def evaluate(features, config):
    records = [
        (sample, garment_features(raw, scores, config), relevant)
        for sample, raw, scores, relevant in features
    ]
    cases = measure(records, config)
    return {"metrics": summarize_split(cases), "cases": cases}


def choose(calibration, baseline):
    options = list(product((0.5, 1.0), (0.0, 0.5, 1.0), (0.0, 0.5), (0.5, 1.0), (1.0, 1.1, 1.2)))

    def key(config):
        metrics = evaluate(calibration, config)["metrics"]
        ratios = [metrics[name] / threshold for name, threshold in GATES.items()]
        groups = metrics["perGroup"]
        return (
            min(ratios),
            sum(min(1.0, ratio) for ratio in ratios),
            min(groups[group]["categoryAccuracy"] for group in GARMENT_GROUPS),
            metrics["categoryAccuracy"],
            metrics["recallAt5"],
        )

    configs = [
        {**baseline, "uniformBlend": uniform, "isolationWeight": isolation,
         "accessoryBlend": accessory, "unknownBlend": unknown, "unknownRatio": ratio}
        for uniform, isolation, accessory, unknown, ratio in options
    ]
    return max(configs, key=key), len(configs)


async def run():
    datasets = (*DATASETS, BALANCED_DATASET, FOCUSED_DATASET, GARMENT_DATASET)
    rows, manifests = collect_inputs(datasets)
    fresh_directory = LOCK.parents[1] / GARMENT_DATASET[0]
    fresh_rows = [row for row in rows["DEV-check"] if row[1].is_relative_to(fresh_directory)]
    historical = rows["calibration"] + [row for row in rows["DEV-check"] if row not in fresh_rows]
    if not fresh_rows or any(row[1].is_relative_to(fresh_directory) for row in historical):
        raise ValueError("Fresh queries must be held out in DEV-check")

    engine = load_engine()
    engine.provider = Siglip2EmbeddingProvider(engine.settings, category_prompts=RUN_PROMPTS)
    pipeline = ImagePipeline(engine.settings)
    baseline = {**engine.profile, "uniformBlend": 0.0, "unknownBlend": 0.0,
                "isolationWeight": 0.0, "accessoryBlend": 0.0}
    calibration = await features_for(historical, engine, pipeline)
    chosen, count = choose(calibration, baseline)
    arms = {
        "baseline": baseline,
        "devV41FullPrompts": {**baseline, "uniformBlend": 1.0, "unknownBlend": 1.0},
        "selectedOnCalibration": chosen,
    }
    fresh = await features_for(fresh_rows, engine, pipeline)
    results = {
        name: {
            "configuration": config,
            "calibration": evaluate(calibration, config)["metrics"],
            "freshDEVCheck": evaluate(fresh, config),
        }
        for name, config in arms.items()
    }
    metrics = results["selectedOnCalibration"]["freshDEVCheck"]["metrics"]
    return {
        "scope": "DEV only; fresh garment check scored after configuration selection",
        "manifests": manifests,
        "profileSha256": sha256(LOCK.read_bytes()).hexdigest(),
        "snapshotChecksum": engine.lock["catalog"]["snapshotChecksum"],
        "model": engine.lock["model"],
        "promptAdditions": GARMENT_PROMPTS,
        "configurationCount": count,
        "thresholds": GATES,
        "selectedMeetsDevGates": all(metrics[key] >= threshold for key, threshold in GATES.items()),
        "results": results,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output
    if output.exists():
        parser.error("Choose a new output path to preserve existing evidence")
    report = asyncio.run(run())
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    main()
