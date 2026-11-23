"""Tune confusion-pair category consensus and evaluate a fresh DEV supplement."""

from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
from hashlib import md5, sha256
from importlib.metadata import version
from itertools import product
import json
import math
import os
from pathlib import Path
from unittest.mock import patch

from app.config import Settings
from app.providers import siglip2
from app.providers.siglip2 import SIGLIP2_DIMENSIONS, SIGLIP2_NAME, SIGLIP2_REVISION
from app.services.visual_ranking import category_group, rerank_visual_candidates
from scripts.evaluate_dev_v3 import BASELINE as ORIGINAL_BASELINE
from scripts.evaluate_dev_v3 import GATES, LIMIT, generate_features, load, read_catalog, summarize


BASELINE = {**ORIGINAL_BASELINE, "pairMode": "off", "pairBoost": 0.0}
PRIOR_CANDIDATE = {**BASELINE, "unknownRatio": 1.0}
PAIRS = (("tops", "uniforms", "upper"), ("accessories", "shoes", "center"))
CATALOG_PROMPTS = {
    **{key: prompts for key, prompts in siglip2.CATEGORY_PROMPTS.items() if key not in {"tops", "unknown"}},
    "tops:short": ("a short sleeve athletic training T-shirt", "a sports T-shirt for running practice"),
    "tops:long": ("a long sleeve sports shirt", "a thermal base layer running shirt with long sleeves"),
    "tops:sleeveless": ("a sleeveless athletic running tank top", "a sports singlet"),
    "tops:jacket": ("a zip-up sports training jacket", "a warm-up jacket or long sleeve training pullover"),
    "tops:jersey": ("a single soccer jersey shirt without matching shorts", "a football fan jersey top"),
    "unknown:backpack": ("a backpack or rucksack carried on the back", "a padded travel or hiking bag"),
    "unknown:electronics": ("a computer mouse or other computer peripheral", "a standalone electronic accessory"),
    "unknown:other": siglip2.CATEGORY_PROMPTS["unknown"] + ("an umbrella for rain", "an everyday object"),
}


def adjusted_scores(features: dict, config: dict, group_scores: dict | None = None) -> dict:
    scores = (group_scores if group_scores is not None else features[config["view"]]["scores"]).copy()
    if config["pairMode"] == "off":
        return scores
    leaders = sorted((group for group in scores if group != "unknown"), key=lambda group: -scores[group])
    for target, confounder, region in PAIRS:
        if leaders[0] != confounder:
            continue
        if config["pairMode"] == "retrieval":
            if scores[target] < scores[confounder] / 2:
                continue
            neighbors = [
                max(features[view]["raw"], key=lambda row: row["similarity"])
                for view in ("full", "center", "upper")
            ]
            if sum(category_group(row["product_type"]) == target for row in neighbors) < 2:
                continue
        elif leaders[1] != target:
            continue
        if config["pairMode"] == "region" and features[region]["scores"][target] < features[region]["scores"][confounder]:
            continue
        scores[target] *= 1.0 + config["pairBoost"]
    return scores


def rank(features: dict, config: dict) -> list[dict]:
    view = features[config["view"]]
    scores = view["scores"]
    if config.get("subtypeWeight") is not None:
        known = {}
        unsupported = []
        for kind, score in scores.items():
            group = kind.split(":")[0]
            if group == "unknown":
                unsupported.append(score)
            else:
                known[group] = max(known.get(group, 0.0), score)
        if config["unknownRatio"] is not None and max(unsupported) >= config["unknownRatio"] * max(known.values()):
            return []
        rows = rerank_visual_candidates(
            view["raw"], category_weight=config["categoryWeight"],
            query_category_scores=adjusted_scores(features, config, known),
        )
        for row in rows:
            if row["category_group"] == "tops":
                subtype = scores[row["subtype"]] / known["tops"]
                row["similarity"] += config["subtypeWeight"] * (subtype - 1.0)
        rows.sort(key=lambda row: (-row["similarity"], row["product_id"], row["image_id"]))
        return [row for row in rows[:LIMIT] if row["similarity"] >= config["minSimilarity"]]
    strongest_known = max(score for group, score in scores.items() if group != "unknown")
    ratio = config["unknownRatio"]
    if ratio is not None and scores["unknown"] >= ratio * strongest_known:
        return []
    ordered = rerank_visual_candidates(
        view["raw"], category_weight=config["categoryWeight"], query_category_scores=adjusted_scores(features, config),
    )
    return [row for row in ordered[:LIMIT] if row["similarity"] >= config["minSimilarity"]]


def measure(records: list[tuple[dict, dict, set]], config: dict) -> list[dict]:
    results = []
    for sample, features, relevant in records:
        rows = rank(features, config)
        top = rows[:5]
        ranks = [rank for rank, row in enumerate(top, 1) if row["product_id"] in relevant]
        actual_group = rows[0]["category_group"] if rows else None
        results.append({
            "queryId": sample["id"], "group": sample["group"], "predictedGroup": actual_group,
            "recall": len(ranks) / len(relevant) if relevant else None,
            "reciprocalRank": 1 / ranks[0] if ranks else 0.0,
            "categoryCorrect": actual_group == sample["group"], "rejected": not rows,
            "top5ProductIds": [row["product_id"] for row in top],
        })
    return results


def choose(records: list[tuple[dict, dict, set]], *, semantics: bool = False,
           combine: bool = False) -> tuple[dict, int]:
    pair_options = [("off", 0.0), *product(("region", "global", "retrieval"), (0.15, 0.30, 0.50, 0.75))]
    if semantics:
        semantic_pairs = (("retrieval", 0.30), ("retrieval", 0.50), ("retrieval", 0.75)) if combine else (("off", 0.0),)
        configs = (
            {**BASELINE, "view": view, "categorySource": "catalog-subtypes", "categoryWeight": weight,
             "minSimilarity": threshold, "unknownRatio": ratio, "subtypeWeight": subtype,
             "pairMode": pair, "pairBoost": boost}
            for view, weight, threshold, ratio, subtype, (pair, boost) in product(
                ("full", "full+center", "full+upper"), (0.35, 0.60, 0.75),
                (0.50, 0.60, 0.70, 0.755936), (0.70, 0.85, 1.0, 1.2, None), (0.0, 0.10, 0.25, 0.40),
                semantic_pairs,
            )
        )
    else:
        configs = (
            {**BASELINE, "view": view, "categoryWeight": weight, "minSimilarity": similarity,
             "unknownRatio": ratio, "pairMode": mode, "pairBoost": boost}
            for view, weight, similarity, ratio, (mode, boost) in product(
                ("full", "full+center", "full+upper"), (0.35, 0.60, 0.75),
                (0.50, 0.60, 0.70, 0.755936), (0.70, 0.85, 1.0, 1.2, None), pair_options,
            )
        )
    best, best_key, evaluated = PRIOR_CANDIDATE, (), 0
    for config in configs:
        cases = measure(records, config)
        metrics = summarize(cases)
        ratios = [metrics[key] / target for key, target in GATES.items()]
        target_metrics = summarize([case for case in cases if case["group"] in {"tops", "accessories"}])
        key = (
            min(ratios), sum(min(1.0, ratio) for ratio in ratios),
            target_metrics["categoryAccuracy"], metrics["recallAt5"], metrics["categoryAccuracy"],
            metrics["meanReciprocalRankAt5"], config["view"] == "full", config["pairMode"] == "off",
            -abs(config["minSimilarity"] - BASELINE["minSimilarity"]),
        )
        if key > best_key:
            best, best_key = config, key
        evaluated += 1
    return best, evaluated


def dataset(path: Path, name: str) -> tuple[dict, dict]:
    manifest = load(path)
    identity = manifest["dataset"]
    if (identity["name"], identity["split"], identity["status"]) != (name, "DEV", "FROZEN"):
        raise ValueError(f"Expected frozen {name}")
    labels = {case["queryId"]: {row["productId"] for row in case["relevantProducts"] if row["grade"] >= 2}
              for case in load(path.with_name("labels.json"))["cases"]}
    if labels.keys() != {sample["id"] for sample in manifest["samples"]}:
        raise ValueError(f"Dataset and labels disagree for {name}")
    return manifest, labels


async def collect_records(path: Path, name: str, snapshot: dict, settings: Settings):
    manifest, labels = dataset(path, name)
    features, durations = await generate_features(manifest, path, snapshot, settings)
    records = [(sample, features[index], labels[sample["id"]]) for index, sample in enumerate(manifest["samples"])]
    return manifest, records, durations


def catalog_top_subtypes(root: Path) -> dict[str, str]:
    subtype_by_prefix = {
        "ao-khoac-": "tops:jacket", "ao-gio-": "tops:jacket",
        "ao-chay-bo-dai-": "tops:long", "ao-giu-nhiet-": "tops:long",
        "ao-tank-": "tops:sleeveless", "ao-dau-": "tops:jersey",
    }
    mapping = {}
    for directory in ("pilot-v1", "world-cup-2026-store-staging-v1"):
        folder = root / "catalog" / directory
        imports = json.loads((folder / "import-result.json").read_text(encoding="utf-8-sig"))
        manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8-sig"))
        ids = {row["slug"]: row["id"] for row in imports["products"]}
        for row in manifest["products"]:
            slug = row["slug"]
            if row["productType"] == "SPORTS_TOP":
                mapping[ids[slug]] = next(
                    (subtype for prefix, subtype in subtype_by_prefix.items() if slug.startswith(prefix)), "tops:short",
                )
    return mapping


async def run(original: Path, supplement: Path, snapshot: dict, *,
              semantics: bool = False, combine: bool = False) -> dict:
    settings = Settings(
        _env_file=None, visual_embedding_provider="hugging_face_local", visual_embedding_device="cpu",
        visual_embedding_model=SIGLIP2_NAME, visual_embedding_revision=SIGLIP2_REVISION,
        visual_embedding_dims=SIGLIP2_DIMENSIONS, visual_embedding_preprocessing="siglip2-autoprocessor-original-v1",
        target_image_max_width=1024, target_image_max_height=1024,
    )
    subtypes = catalog_top_subtypes(original.resolve().parents[3]) if semantics else {}
    if semantics and any(
        row["product_type"] == "SPORTS_TOP" and row["product_id"] not in subtypes for row in snapshot["rows"]
    ):
        raise ValueError("Some catalog tops do not have subtype metadata")
    prompt_bank = CATALOG_PROMPTS if semantics else siglip2.CATEGORY_PROMPTS
    with patch.object(siglip2, "CATEGORY_PROMPTS", prompt_bank):
        old_manifest, old_records, old_durations = await collect_records(
            original, "image-search-dev-v3", snapshot, settings,
        )
        new_manifest, new_records, new_durations = await collect_records(
            supplement, "image-search-dev-v3-supplement", snapshot, settings,
        )
    if semantics:
        for _, features, _ in old_records + new_records:
            for feature in features.values():
                for row in feature["raw"]:
                    if row["product_type"] == "SPORTS_TOP":
                        row["subtype"] = subtypes[row["product_id"]]
    groups = {record[0]["sourceGroupCode"] for record in old_records}
    if groups.intersection(record[0]["sourceGroupCode"] for record in new_records):
        raise ValueError("The supplement overlaps DEV v3 source groups")
    calibration = old_records + [row for row in new_records if row[0]["partition"] == "calibration"]
    check = [row for row in new_records if row[0]["partition"] == "DEV-check"]
    chosen, count = choose(calibration, semantics=semantics, combine=combine)
    results = {}
    configs = (("selected", chosen),) if semantics else (
        ("baseline", BASELINE), ("priorCandidate", PRIOR_CANDIDATE), ("selected", chosen),
    )
    for name, config in configs:
        partitions = {}
        check_name = "diagnosticDEVCheck" if semantics else "freshDEVCheck"
        for partition, records in (("calibration", calibration), (check_name, check)):
            cases = measure(records, config)
            partitions[partition] = {
                "groups": dict(Counter(case["group"] for case in cases)),
                "overall": summarize(cases),
                "perGroup": {group: summarize([case for case in cases if case["group"] == group])
                             for group in sorted({case["group"] for case in cases})},
                "queries": cases,
            }
        results[name] = {"config": config, "partitions": partitions}
    passed = all(
        all(partition["overall"][key] >= target for key, target in GATES.items())
        for partition in results["selected"]["partitions"].values()
    )
    latency = sorted(old_durations + new_durations)
    return {
        "schemaVersion": 1, "taskId": "HAI-14", "evaluatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": [
            {"identity": manifest["dataset"], "manifestSha256": sha256(path.read_bytes()).hexdigest(),
             "labelsSha256": sha256(path.with_name("labels.json").read_bytes()).hexdigest()}
            for path, manifest in ((original, old_manifest), (supplement, new_manifest))
        ],
        "catalog": {"images": len(snapshot["rows"]), "vectorChecksum": snapshot["checksum"]},
        "model": {"name": SIGLIP2_NAME, "revision": SIGLIP2_REVISION, "dimensions": SIGLIP2_DIMENSIONS,
                  "preprocessing": settings.visual_embedding_preprocessing, "candidateLimit": LIMIT},
        "runtime": {"device": "cpu", "torch": version("torch"), "transformers": version("transformers")},
        "variant": "catalog-subtype-consensus" if semantics else "confusion-pair-consensus",
        "combinedPairEvidence": combine,
        "calibrationGridSize": count, "gates": GATES, "threeViewOfflineP95Ms": latency[math.ceil(.95 * len(latency)) - 1],
        "results": results, "passed": passed,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("supplement", type=Path)
    parser.add_argument("--catalog-vectors", type=Path)
    parser.add_argument("--catalog-semantics", action="store_true", help="Run the subtype and unsupported-object diagnostic")
    parser.add_argument("--combine-pair-evidence", action="store_true", help="Combine the two DEV diagnostics")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.combine_pair_evidence and not args.catalog_semantics:
        parser.error("--combine-pair-evidence requires --catalog-semantics")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    if not args.catalog_vectors and not os.environ.get("DATABASE_URL"):
        parser.error("Set DATABASE_URL or use --catalog-vectors")
    snapshot = load(args.catalog_vectors) if args.catalog_vectors else read_catalog(os.environ["DATABASE_URL"])
    vectors = ",".join(row["image_id"] + row["vector"] for row in sorted(snapshot["rows"], key=lambda r: r["image_id"]))
    if not vectors or md5(vectors.encode("utf-8")).hexdigest() != snapshot["checksum"]:
        raise ValueError("Catalog image vector snapshot checksum mismatch")
    report = asyncio.run(run(
        args.original, args.supplement, snapshot,
        semantics=args.catalog_semantics, combine=args.combine_pair_evidence,
    ))
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    selected = report["results"]["selected"]
    print(json.dumps({
        "passed": report["passed"], "config": selected["config"],
        **{key: value["overall"] for key, value in selected["partitions"].items()},
    }))


if __name__ == "__main__":
    main()
