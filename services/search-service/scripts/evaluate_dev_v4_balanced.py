"""Select a blended SigLIP2 configuration and evaluate a fresh six-group DEV split."""

from __future__ import annotations

import argparse
import asyncio
from hashlib import sha256
from itertools import product
import json
from pathlib import Path

from app.services.image_pipeline import ImagePipeline
from scripts.benchmark_test_v3 import LOCK
from scripts.evaluate_dev_v3 import GATES
from scripts.evaluate_dev_v3_followup import CATALOG_PROMPTS, measure
from scripts.evaluate_dev_v4 import (
    DATASETS,
    UNIFORM_PROMPTS,
    UNKNOWN_PROMPTS,
    collect_inputs,
    features_for,
    load_engine,
    selection_key,
    summarize_split,
)


BALANCED_DATASET = ("dev-v4-balanced", "image-search-dev-v4-balanced")
BLEND_WEIGHTS = (0.0, 0.25, 0.5, 0.75, 1.0)
UNKNOWN_RATIOS = (1.0, 1.1, 1.2, 1.3)


def blend_support(prior, added, weight):
    return prior + weight * (max(prior, added) - prior)


def evaluate(features, config):
    records = []
    for sample, raw, scores, relevant in features:
        scores_for_rank = {key: scores[key] for key in CATALOG_PROMPTS}
        scores_for_rank["uniforms"] = blend_support(
            scores_for_rank["uniforms"], max(scores[key] for key in UNIFORM_PROMPTS), config["uniformBlend"],
        )
        strongest_unknown = max(
            (key for key in scores_for_rank if key.startswith("unknown:")), key=scores_for_rank.__getitem__,
        )
        scores_for_rank[strongest_unknown] = blend_support(
            scores_for_rank[strongest_unknown], max(scores[key] for key in UNKNOWN_PROMPTS), config["unknownBlend"],
        )
        records.append((sample, {"full": {"raw": raw, "scores": scores_for_rank}}, relevant))
    cases = measure(records, config)
    return {"metrics": summarize_split(cases), "cases": cases}


def choose(calibration, baseline):
    configurations = [
        {**baseline, "uniformBlend": uniform, "unknownBlend": unknown, "unknownRatio": ratio}
        for uniform, unknown, ratio in product(BLEND_WEIGHTS, BLEND_WEIGHTS, UNKNOWN_RATIOS)
    ]

    def calibration_key(config):
        metrics = evaluate(calibration, config)["metrics"]
        controls = metrics["perGroup"]
        return (
            *selection_key(metrics)[:2],
            min(controls[group]["categoryAccuracy"] for group in ("tops", "accessories")),
            controls["uniforms"]["categoryAccuracy"],
            metrics["recallAt5"],
            -abs(config["unknownRatio"] - baseline["unknownRatio"]),
        )

    return max(configurations, key=calibration_key), len(configurations)


async def run():
    previous, historical_manifests = collect_inputs(DATASETS)
    current, fresh_manifests = collect_inputs((BALANCED_DATASET,))
    engine = load_engine()
    pipeline = ImagePipeline(engine.settings)
    calibration_rows = previous["calibration"] + current["calibration"]
    calibration = await features_for(calibration_rows, engine, pipeline)
    baseline = {**engine.profile, "uniformBlend": 0.0, "unknownBlend": 0.0}
    selected, count = choose(calibration, baseline)
    arms = {
        "baseline": baseline,
        "fullPromptSupport": {**baseline, "uniformBlend": 1.0, "unknownBlend": 1.0},
        "selectedOnCalibration": selected,
    }
    fresh = await features_for(current["DEV-check"], engine, pipeline)
    results = {
        name: {
            "configuration": config,
            "calibration": evaluate(calibration, config),
            "freshDEVCheck": evaluate(fresh, config),
        }
        for name, config in arms.items()
    }
    metrics = results["selectedOnCalibration"]["freshDEVCheck"]["metrics"]
    return {
        "scope": "DEV experiment; image query ranking on the frozen local catalog snapshot",
        "manifests": {**historical_manifests, **fresh_manifests},
        "profileSha256": sha256(LOCK.read_bytes()).hexdigest(),
        "snapshotChecksum": engine.lock["catalog"]["snapshotChecksum"],
        "promptAdditions": {**UNIFORM_PROMPTS, **UNKNOWN_PROMPTS},
        "configurationCount": count,
        "thresholds": GATES,
        "selectedMeetsDevGates": all(metrics[key] >= threshold for key, threshold in GATES.items()),
        "results": results,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    output_path = parser.parse_args().output
    if output_path.exists():
        parser.error("Choose a new output path to preserve existing evidence")
    report = asyncio.run(run())
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
