"""Verify DEV parity or run the approved independent TEST v4 once."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
from unittest.mock import patch

import httpx

from app.api.search import get_image_candidate_search
from app.providers.siglip2 import Siglip2EmbeddingProvider
from scripts.benchmark_test_v3 import (
    LOCK as BASE_LOCK, ROOT, LockedImageSearch, canonical_sha, load_inputs,
    request_case, summarise_run, validate_approval, write_json,
)
from scripts.evaluate_dev_v3 import grouped, load
from scripts.evaluate_dev_v3_followup import rank
from scripts.evaluate_dev_v4_garment import RUN_PROMPTS, garment_features
from scripts.verify_exact_image_search import internal_api


DEV = ROOT / "datasets/image-search/dev-v4-garment/manifest.json"
LOCK = DEV.with_name("locked-profile.json")
TEST = ROOT / "datasets/image-search/test-v4/manifest.json"
FINAL_DIR = ROOT / "evidence/image-search-v4/test-v4-final"


def text_sha(path: Path) -> str:
    return sha256(path.read_text(encoding="utf-8").encode("utf-8")).hexdigest()


class LockedGarmentSearch(LockedImageSearch):
    def __init__(self, candidate):
        super().__init__(BASE_LOCK)
        self.profile = candidate["profile"]
        self.provider = Siglip2EmbeddingProvider(self.settings, category_prompts=RUN_PROMPTS)

    async def __call__(self, content, limit, settings):
        vector = (await self.provider.embed_query(content)).validate_dimensions(settings.visual_embedding_dims).vector
        rows = grouped(vector, self.catalog)
        for row in rows:
            if row["product_type"] == "SPORTS_TOP":
                row["subtype"] = self.subtypes[row["product_id"]]
            row["image_url"] = f"snapshot://image/{row['image_id']}"
        scores = await self.provider.category_scores(vector)
        return rank(garment_features(rows, scores, self.profile), self.profile)[:limit]


def run(final: bool, output: Path) -> bool:
    marker = output / "run-started.json"
    if final and marker.exists():
        raise ValueError("The TEST v4 run has already been reserved")
    if final and not (TEST.is_file() and TEST.with_name("review-approval.json").is_file()):
        raise ValueError("TEST v4 requires a frozen dataset and recorded independent approval")
    candidate = load(LOCK)
    reference = candidate["devCheck"]
    evidence_path = ROOT / reference["evidence"]
    pinned = {
        BASE_LOCK: candidate["baseProfileSha256"],
        DEV: reference["manifestSha256"],
        DEV.with_name("labels.json"): reference["labelsSha256"],
        evidence_path: reference["evidenceSha256"],
        **{ROOT / path: digest for path, digest in candidate["rankingSourceSha256"].items()},
    }
    if (
        candidate["status"] != "FROZEN_FOR_TEST_V4"
        or canonical_sha(RUN_PROMPTS) != candidate["promptBankCanonicalSha256"]
        or any(text_sha(path) != digest for path, digest in pinned.items())
    ):
        raise ValueError("The locked profile, code or DEV evidence differs")
    evidence = load(evidence_path)
    selected = evidence["results"]["selectedOnCalibration"]
    if not evidence["selectedMeetsDevGates"] or selected["configuration"] != candidate["profile"]:
        raise ValueError("The profile must match the selected configuration that passed DEV")
    manifest, dev_payloads = load_inputs(DEV, "DEV")
    expected = {case["queryId"]: case for case in selected["freshDEVCheck"]["cases"]}
    if expected.keys() != {sample["id"] for sample, _, _ in dev_payloads}:
        raise ValueError("DEV parity cases differ from the frozen evidence")
    approval = None
    payloads = dev_payloads
    if final:
        manifest, payloads = load_inputs(TEST, "TEST", test_name="image-search-test-v4")
        approval = validate_approval(TEST, manifest, LOCK)
        if len(payloads) < 100 or {p[0]["group"] for p in payloads} != {p[0]["group"] for p in dev_payloads}:
            raise ValueError("TEST v4 requires at least 100 independently reviewed cases across all six groups")
        if not manifest["policy"]["mayReportAsFinalTest"] or manifest["dataset"]["catalogSnapshot"] != candidate["catalogSnapshot"]:
            raise ValueError("TEST v4 must be frozen against the locked catalog snapshot")

    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    engine = LockedGarmentSearch(candidate)
    catalog = engine.lock["catalog"]
    if candidate["catalogSnapshot"] != {
        "vectors": len(engine.catalog),
        "sha256": catalog["snapshotFileSha256"],
        "checksum": catalog["snapshotChecksum"],
    }:
        raise ValueError("The catalog metadata differs from the verified snapshot")
    config = {
        "scope": "loopback HTTP with frozen local snapshot",
        "dataset": manifest["dataset"], "profileSha256": text_sha(LOCK),
        "manifestSha256": text_sha(TEST if final else DEV),
        "catalogSnapshot": candidate["catalogSnapshot"], "approval": approval,
        "startedAt": datetime.now(timezone.utc).isoformat(),
    }
    output.mkdir(parents=True, exist_ok=True)
    with patch("app.main.build_provider", return_value=engine.provider), internal_api(
        engine.settings, overrides={get_image_candidate_search: lambda: engine},
    ) as url, httpx.Client(base_url=url, timeout=20, trust_env=False) as client:
        client.headers["X-Internal-Service-Token"] = engine.settings.internal_service_token
        request_case(client, dev_payloads[0])
        parity_cases = [request_case(client, payload) for payload in dev_payloads]
        parity = sum(
            case["error"] is None and case["top5ProductIds"] == expected[case["queryId"]]["top5ProductIds"]
            for case in parity_cases
        )
        gates = engine.lock["independentTestGates"]
        preflight = summarise_run(parity_cases, gates)
        preflight_passed = parity == len(dev_payloads) and preflight["acceptance"]["passed"]
        if final:
            if not preflight_passed:
                raise ValueError("Locked DEV parity or latency failed before the independent TEST")
            write_json(marker, config, mode="x")
            cases = [request_case(client, payload) for payload in payloads]
        else:
            cases = parity_cases
    metrics = summarise_run(cases, gates) if final else preflight
    metrics["acceptance"]["eligible"] = final
    metrics["devParityMatches"] = parity
    metrics["preflightPassed"] = preflight_passed
    write_json(output / "benchmark-config.json", config, mode="x")
    write_json(output / "ranked-results.json", cases, mode="x")
    write_json(output / "metrics.json", metrics, mode="x")
    return metrics["acceptance"]["passed"] if final else preflight_passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dev-smoke", type=Path)
    mode.add_argument("--final", action="store_true")
    args = parser.parse_args()
    output = FINAL_DIR if args.final else args.dev_smoke
    if not args.final and output.resolve().is_relative_to(FINAL_DIR.resolve()):
        parser.error("DEV smoke output belongs outside the final evidence directory")
    if any((output / name).exists() for name in ("benchmark-config.json", "ranked-results.json", "metrics.json")):
        parser.error("The output already contains evaluation evidence")
    try:
        passed = run(args.final, output)
    except (ValueError, FileExistsError) as error:
        parser.exit(1, f"{error}\n")
    print(json.dumps({"mode": "final" if args.final else "DEV smoke", "passed": passed}))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
