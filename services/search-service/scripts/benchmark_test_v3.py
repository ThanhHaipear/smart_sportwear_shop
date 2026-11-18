"""Run the sealed TEST v3 once, or smoke test the locked profile on DEV."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
from hashlib import sha256
from importlib.metadata import version
import json
import mimetypes
import os
from pathlib import Path
import secrets
from time import perf_counter
from unittest.mock import patch

import httpx

from app.api.search import get_image_candidate_search
from app.config import Settings
from app.providers.siglip2 import Siglip2EmbeddingProvider
from scripts.evaluate_catalog_coverage import file_sha, verified_vectors
from scripts.evaluate_dev_v3 import LIMIT, grouped, load, summarize
from scripts.evaluate_dev_v3_followup import CATALOG_PROMPTS, catalog_top_subtypes, rank
from scripts.verify_exact_image_search import internal_api, percentile_95


ROOT = Path(__file__).resolve().parents[3]
LOCK = ROOT / "datasets/image-search/dev-v3-catalog-coverage/locked-profile.json"
DEV = LOCK.with_name("manifest.json")
TEST = ROOT / "datasets/image-search/test-v3/manifest.json"
FINAL_DIR = ROOT / "evidence/image-search-v3/test-v3-final"


def canonical_sha(value) -> str:
    return sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


class LockedImageSearch:
    def __init__(self, lock_path: Path):
        self.lock = load(lock_path)
        self.profile = self.lock["profile"]
        model = self.lock["model"]
        required = {"exifTranspose": True, "colorMode": "RGB", "maxWidth": 1024,
                    "maxHeight": 1024, "jpegQuality": 88}
        if (
            self.lock["status"] != "FROZEN_FOR_HAI15" or self.lock["candidateLimit"] != LIMIT
            or self.profile["view"] != "full" or self.profile["pairMode"] != "off"
            or self.profile["categorySource"] != "catalog-subtypes" or self.profile.get("subtypeWeight") is None
            or self.lock["queryNormalization"] != required
        ):
            raise ValueError("Unsupported locked TEST v3 profile")
        if any(version(package) != model[package] for package in ("torch", "transformers")):
            raise ValueError("The runtime model dependencies differ from the locked versions")
        snapshot_path = lock_path.parent / self.lock["catalog"]["snapshotLocalPath"]
        if file_sha(snapshot_path) != self.lock["catalog"]["snapshotFileSha256"]:
            raise ValueError("Catalog snapshot file SHA-256 differs from the lock")
        snapshot = load(snapshot_path)
        if snapshot["checksum"] != self.lock["catalog"]["snapshotChecksum"]:
            raise ValueError("Catalog vector checksum differs from the lock")
        self.catalog = verified_vectors(snapshot)
        if len(self.catalog) != self.lock["catalog"]["snapshotVectorCount"]:
            raise ValueError("Unexpected locked catalog vector count")
        self.subtypes = catalog_top_subtypes(ROOT)
        expected = self.lock["semanticInputs"]
        if (
            canonical_sha(CATALOG_PROMPTS) != expected["promptBankCanonicalSha256"]
            or canonical_sha(self.subtypes) != expected["catalogTopSubtypesCanonicalSha256"]
        ):
            raise ValueError("Semantic inputs differ from the lock")
        for asset in self.lock["catalog"]["addedReferences"]:
            if file_sha(lock_path.parent / asset["image"]) != asset["sha256"]:
                raise ValueError(f"Catalog reference image changed: {asset['image']}")
        self.settings = Settings(
            _env_file=None, search_enabled=True, visual_search_enabled=True,
            database_url="postgresql://unused", catalog_service_token=secrets.token_urlsafe(),
            internal_service_token=secrets.token_urlsafe(), visual_embedding_provider="hugging_face_local",
            visual_embedding_device="cpu", visual_embedding_model=model["name"],
            visual_embedding_revision=model["revision"], visual_embedding_dims=model["dimensions"],
            visual_embedding_preprocessing=model["preprocessing"], target_image_max_width=required["maxWidth"],
            target_image_max_height=required["maxHeight"],
        )
        self.provider = Siglip2EmbeddingProvider(self.settings, category_prompts=CATALOG_PROMPTS)

    async def __call__(self, content: bytes, limit: int, _: Settings) -> list[dict]:
        output = (await self.provider.embed_query(content)).validate_dimensions(self.settings.visual_embedding_dims)
        vector = output.vector
        raw = grouped(vector, self.catalog)
        for row in raw:
            if row["product_type"] == "SPORTS_TOP":
                row["subtype"] = self.subtypes[row["product_id"]]
            # The frozen vector snapshot has IDs but no public asset URLs.
            row["image_url"] = f"snapshot://image/{row['image_id']}"
        features = {"full": {"raw": raw, "scores": await self.provider.category_scores(vector)}}
        return rank(features, self.profile)[:limit]


def load_inputs(manifest_path: Path, split: str):
    manifest = load(manifest_path)
    identity = manifest["dataset"]
    if identity["split"] != split or identity["status"] != "FROZEN":
        raise ValueError(f"Expected a frozen {split} manifest")
    if split == "TEST" and identity["name"] != "image-search-test-v3":
        raise ValueError("Final evaluation only accepts TEST v3")
    labels_path = manifest_path.with_name("labels.json")
    labels = {}
    for item in load(labels_path)["cases"]:
        if item["judgmentStatus"] != "COMPLETE" or item["queryId"] in labels:
            raise ValueError("Dataset labels must be complete and unique")
        labels[item["queryId"]] = item
    samples = manifest["samples"]
    if len(labels) != len(samples) or set(labels) != {sample["id"] for sample in samples}:
        raise ValueError("Dataset and labels disagree")
    payloads = []
    for sample in samples:
        path = manifest_path.parent / sample["image"]
        content = path.read_bytes()
        label = labels[sample["id"]]
        if sha256(content).hexdigest() != sample["sha256"] or label["expectedCategory"] != sample["group"]:
            raise ValueError(f"TEST input validation failed for {sample['id']}")
        relevant = {row["productId"] for row in label["relevantProducts"] if row["grade"] >= 2}
        if (sample["group"] == "unknown") != (not relevant):
            raise ValueError(f"Invalid relevance coverage for {sample['id']}")
        payloads.append((sample, relevant, (path.name, content, mimetypes.guess_type(path.name)[0])))
    return manifest, payloads


def approval_bindings(manifest_path: Path, manifest: dict) -> dict:
    def text_sha(path: Path) -> str:
        return sha256(path.read_text(encoding="utf-8").encode("utf-8")).hexdigest()

    return {
        "samplesSha256": canonical_sha(manifest["samples"]),
        "labelsSha256": text_sha(manifest_path.with_name("labels.json")),
        "assetListSha256": text_sha(manifest_path.with_name("assets.sha256")),
        "duplicateReportSha256": text_sha(manifest_path.with_name("duplicate-report.json")),
        "lockedProfileSha256": text_sha(LOCK),
    }


def validate_approval(manifest_path: Path, manifest: dict) -> dict:
    approval_path = manifest_path.with_name("review-approval.json")
    if not approval_path.is_file():
        raise ValueError("TEST v3 independent review approval is required")
    approval = load(approval_path)
    reviewer = approval.get("reviewer", "").strip()
    if (
        approval.get("status") != "APPROVED" or not reviewer
        or reviewer.casefold() in {"hải", "hai", "lê thanh hải", "codex"}
        or not approval.get("reviewedAt") or approval.get("bindings") != approval_bindings(manifest_path, manifest)
        or approval.get("independent") is not True
    ):
        raise ValueError("Independent approval does not match the frozen dataset")
    duplicate_report = load(manifest_path.with_name("duplicate-report.json"))
    if (
        duplicate_report["unresolved"] or duplicate_report["exactDuplicates"]
        or duplicate_report["sourceSeriesOverlap"] or duplicate_report["sourcePageOverlap"]
        or {item["queryId"] for item in duplicate_report["accepted"]} != {row["id"] for row in manifest["samples"]}
    ):
        raise ValueError("TEST v3 leakage audit must cover every sample")
    return approval


def request_case(client: httpx.Client, payload: tuple) -> dict:
    sample, relevant, image = payload
    began = perf_counter()
    status, rows, error = None, [], None
    try:
        response = client.post("/internal/v1/search/image", params={"limit": LIMIT}, files={"image": image})
        status = response.status_code
        if status == 200:
            candidates = response.json()["candidates"]
            products = [row["product_id"] for row in candidates]
            if len(products) > LIMIT or len(set(products)) != len(products):
                raise ValueError("Unexpected candidate count or duplicate products")
            rows = candidates
        else:
            error = f"HTTP_{status}"
    except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
        error = type(exc).__name__
    elapsed = (perf_counter() - began) * 1000
    top5 = rows[:5]
    ranks = [index for index, row in enumerate(top5, 1) if row["product_id"] in relevant]
    return {
        "queryId": sample["id"], "group": sample["group"], "status": status, "error": error,
        "latencyMs": elapsed, "recall": len(ranks) / len(relevant) if relevant else None,
        "categoryCorrect": bool(rows) and rows[0]["category_group"] == sample["group"],
        "reciprocalRank": 1 / ranks[0] if ranks else 0.0, "rejected": not error and not rows,
        "top5ProductIds": [row["product_id"] for row in top5], "candidates": rows,
    }


def summarise_run(cases: list[dict], gates: dict) -> dict:
    metrics = summarize(cases)
    p95 = percentile_95([case["latencyMs"] for case in cases])
    failures = sum(case["error"] is not None for case in cases)
    failed = failures / len(cases)
    passed = (
        metrics["recallAt5"] >= gates["recallAt5"] and metrics["categoryAccuracy"] >= gates["categoryAccuracy"]
        and metrics["unknownRejection"] >= gates["unknownRejection"] and failed == gates["requestFailureRate"]
        and p95 <= gates["p95EndToEndMs"]
    )
    return {
        "acceptance": {"eligible": True, "passed": passed, "thresholds": gates, "unresolvedLeakage": 0},
        "positive": {
            "count": metrics["knownQueries"], "recall": {"at5": metrics["recallAt5"]},
            "categoryAccuracy": {"value": metrics["categoryAccuracy"]},
            "mrrAt5": metrics["meanReciprocalRankAt5"],
        },
        "unknown": {"count": metrics["unknownQueries"], "rejectionRate": {"value": metrics["unknownRejection"]}},
        "requests": len(cases), "requestFailureRate": {"count": failures, "value": failed},
        "latencyMs": {"p95": p95},
        "perGroup": {group: summarize([case for case in cases if case["group"] == group])
                     for group in sorted(Counter(case["group"] for case in cases))},
    }


def write_json(path: Path, value, mode: str = "w") -> None:
    with path.open(mode, encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def run(final: bool, output: Path) -> bool:
    manifest_path = TEST if final else DEV
    if final and any((output / name).exists() for name in ("run-started.json", "metrics.json", "ranked-results.json")):
        raise ValueError("The TEST v3 final run has already been reserved or executed")
    manifest, payloads = load_inputs(manifest_path, "TEST" if final else "DEV")
    approval = validate_approval(manifest_path, manifest) if final else None
    _, dev_payloads = load_inputs(DEV, "DEV")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    engine = LockedImageSearch(LOCK)
    if final and manifest["dataset"]["catalogSnapshot"] != {
        "vectors": len(engine.catalog),
        "sha256": engine.lock["catalog"]["snapshotFileSha256"],
        "checksum": engine.lock["catalog"]["snapshotChecksum"],
    }:
        raise ValueError("TEST manifest does not identify the locked catalog snapshot")
    dev_evidence_path = ROOT / engine.lock["devCheck"]["evidence"]
    if (
        file_sha(dev_evidence_path) != engine.lock["devCheck"]["evidenceSha256"]
        or file_sha(DEV) != engine.lock["devCheck"]["manifestSha256"]
    ):
        raise ValueError("DEV warmup or reference evidence differs from the lock")
    dev_evidence = load(dev_evidence_path)
    expected = {case["queryId"]: case for case in dev_evidence["results"]["augmented"]["queries"]}
    if expected.keys() != {sample["id"] for sample, _, _ in dev_payloads}:
        raise ValueError("DEV parity cases differ from the reference evidence")
    output.mkdir(parents=True, exist_ok=True)
    config = {
        "dataset": manifest["dataset"], "profileSha256": file_sha(LOCK),
        "manifestSha256": file_sha(manifest_path), "snapshotChecksum": engine.lock["catalog"]["snapshotChecksum"],
        "snapshotVectors": len(engine.catalog), "scope": "loopback HTTP with frozen local snapshot",
        "approval": approval, "startedAt": datetime.now(timezone.utc).isoformat(),
    }
    with patch("app.main.build_provider", return_value=engine.provider), internal_api(
        engine.settings, overrides={get_image_candidate_search: lambda: engine},
    ) as url, httpx.Client(base_url=url, timeout=20, trust_env=False) as client:
        client.headers["X-Internal-Service-Token"] = engine.settings.internal_service_token
        warmup = request_case(client, dev_payloads[0])
        if warmup["error"] or warmup["top5ProductIds"] != expected[warmup["queryId"]]["top5ProductIds"]:
            raise RuntimeError("DEV warmup failed locked-profile parity")
        if final:
            # Acquire exactly once before the first TEST request, including across processes.
            write_json(output / "run-started.json", config, mode="x")
        cases = [request_case(client, payload) for payload in payloads]
    metrics = summarise_run(cases, engine.lock["independentTestGates"])
    metrics["acceptance"]["eligible"] = final
    if not final:
        parity = sum(
            not case["error"] and case["top5ProductIds"] == expected[case["queryId"]]["top5ProductIds"]
            for case in cases
        )
        metrics["devParityMatches"] = parity
        metrics["preflightPassed"] = parity == len(cases) and metrics["latencyMs"]["p95"] <= 3000
    write_json(output / "benchmark-config.json", config)
    write_json(output / "ranked-results.json", cases)
    write_json(output / "metrics.json", metrics)
    return metrics["acceptance"]["passed"] if final else metrics["preflightPassed"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dev-smoke", type=Path, metavar="OUTPUT_DIR")
    mode.add_argument("--final", action="store_true")
    args = parser.parse_args()
    if args.dev_smoke and args.dev_smoke.resolve().is_relative_to(FINAL_DIR.resolve()):
        parser.error("DEV smoke output belongs outside the final evidence directory")
    try:
        passed = run(args.final, FINAL_DIR if args.final else args.dev_smoke)
    except ValueError as exc:
        parser.exit(1, f"{exc}\n")
    print(json.dumps({"mode": "final" if args.final else "DEV smoke", "passed": passed}))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
