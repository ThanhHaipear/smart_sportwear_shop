"""Freeze the DEV and TEST evaluation splits of a synthetic dataset.

Checks the dataset against its generator manifest, then writes `splits/dev.json` and `splits/test.json`
next to the data (window, candidate set, FBT and HOME cases, exclusion counts) and a split manifest
under `evidence/recommendation/`. Training data is not copied: the evaluator takes rows strictly before
`trainUntil` from the same dataset files.

Usage:
    python scripts/build_evaluation_split.py --dataset ../datasets/recommendation/synthetic-v1
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.offline_split import (  # noqa: E402
    HOME_GRADES, before, candidate_set, checksum, fbt_cases, home_cases, parse, segment_counts, windows, within)

REPO_ROOT = Path(__file__).resolve().parents[2]


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def verify(path: Path, expected: str) -> None:
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"{path.name}: sha256 {actual} does not match manifest {expected}")


def build(dataset: Path, generator_manifest: dict) -> tuple[dict[str, dict], dict]:
    config = json.loads((dataset / "config.json").read_text(encoding="utf-8"))
    verify(dataset / "config.json", generator_manifest["config"]["sha256"])
    for name, output in generator_manifest["outputs"].items():
        verify(dataset / name, output["sha256"])
    snapshot = json.loads((REPO_ROOT / config["catalogSnapshot"]).read_text(encoding="utf-8"))
    if snapshot["productsSha256"] != config["catalogProductsSha256"]:
        raise SystemExit("catalog snapshot checksum does not match config")

    interactions = read_jsonl(dataset / "interactions.jsonl")
    baskets = read_jsonl(dataset / "order_baskets.jsonl")
    t0 = parse(config["t0"])
    launch_days = {slug: float(config["newArrivals"]["launchDay"]) for slug in config["newArrivals"]["slugs"]}

    splits = {}
    for name, window in windows(t0, config["weeks"]).items():
        candidates = candidate_set(snapshot["products"], launch_days, t0, window.eval_from)
        fbt, fbt_excluded = fbt_cases(baskets, window, candidates)
        home, home_excluded = home_cases(interactions, baskets, window, candidates)
        splits[name] = {
            "dataset": config["version"],
            "window": window.as_dict(),
            "trainRows": {"interactions": len(before(interactions, window.train_until)),
                          "baskets": len(before(baskets, window.train_until))},
            "evalRows": {"interactions": len(within(interactions, window)),
                         "baskets": len(within(baskets, window))},
            "candidates": {"count": len(candidates), "sha256": checksum(candidates), "productIds": candidates},
            "fbt": {"unit": "source product of an evaluation basket with >= 2 products (leave-one-in)",
                    "counts": segment_counts(fbt), "orders": len({case["orderId"] for case in fbt}),
                    "excluded": fbt_excluded, "sha256": checksum(fbt), "cases": fbt},
            "home": {"unit": "user with ADD_TO_CART or PURCHASE in the window",
                     "grades": HOME_GRADES, "counts": segment_counts(home), "excluded": home_excluded,
                     "sha256": checksum(home), "cases": home},
        }
    policy = {
        "trainRows": "occurredAt < trainUntil (strict)",
        "evalRows": "evalFrom <= occurredAt < evalUntil; a basket belongs to the window of its own timestamp",
        "candidates": "ACTIVE, availableQuantity > 0 in the frozen snapshot, and launched by evalFrom",
        "fbtRelevant": "other products of the same basket that are candidates",
        "fbtWarm": "source product appears in at least one training basket",
        "homeRelevant": "ADD_TO_CART=1 / PURCHASE=2 in the window, minus products the user bought in training",
        "homeWarm": "user has a VIEW/CLICK/WISHLIST/ADD_TO_CART event or an order before trainUntil",
        "minimumSegmentSize": 30,
    }
    return splits, policy


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path)
    args = parser.parse_args()
    dataset = args.dataset.resolve()
    generator_path = REPO_ROOT / f"evidence/recommendation/{dataset.name}-manifest.json"
    generator_manifest = json.loads(generator_path.read_text(encoding="utf-8"))

    splits, policy = build(dataset, generator_manifest)
    out_dir = dataset / "splits"
    out_dir.mkdir(exist_ok=True)
    files = {}
    for name, split in splits.items():
        data = (json.dumps(split, ensure_ascii=False, indent=1, sort_keys=True) + "\n").encode("utf-8")
        (out_dir / f"{name}.json").write_bytes(data)
        files[f"splits/{name}.json"] = hashlib.sha256(data).hexdigest()

    summary = {name: {"window": s["window"], "trainRows": s["trainRows"], "evalRows": s["evalRows"],
                      "candidates": {k: s["candidates"][k] for k in ("count", "sha256")},
                      "fbt": {k: s["fbt"][k] for k in ("counts", "orders", "excluded", "sha256")},
                      "home": {k: s["home"][k] for k in ("counts", "excluded", "sha256")}}
               for name, split in splits.items() for s in [split]}
    manifest = {
        "dataset": dataset.name,
        "builtAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "builder": "recommendation-service/scripts/build_evaluation_split.py",
        "generatorManifest": generator_path.relative_to(REPO_ROOT).as_posix(),
        "policy": policy,
        "files": files,
        "splits": summary,
    }
    out = REPO_ROOT / f"evidence/recommendation/{dataset.name}-splits-manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
