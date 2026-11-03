from __future__ import annotations

import argparse
import asyncio
from collections.abc import Iterable
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import time
from typing import Any

import httpx
from PIL import Image
import psycopg

if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SERVICE_ROOT.parent
sys.path.insert(0, str(SERVICE_ROOT))

from app.config import Settings
from app.services.benchmark import BenchmarkObservation, RankedCandidate, evaluate_benchmark


ALLOWED_CONTENT_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
PIL_CONTENT_TYPES = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}
GROUP_ALIASES = {
    "top": "tops", "tops": "tops", "shirt": "tops", "ao": "tops",
    "bottom": "bottoms", "bottoms": "bottoms", "pants": "bottoms", "quan": "bottoms",
    "uniform": "uniforms", "uniforms": "uniforms", "dong-phuc": "uniforms",
    "shoe": "shoes", "shoes": "shoes", "footwear": "shoes", "giay": "shoes",
    "accessory": "accessories", "accessories": "accessories", "phu-kien": "accessories",
    "unknown": "unknown",
}


def normalize_group(value: str) -> str:
    normalized = value.strip().lower().replace("_", "-")
    for prefix, group in (
        ("dong-phuc", "uniforms"), ("phu-kien", "accessories"),
        ("giay", "shoes"), ("quan", "bottoms"), ("ao", "tops"),
    ):
        if normalized.startswith(prefix):
            return group
    return GROUP_ALIASES.get(normalized, normalized)


def normalize_color(value: str) -> str:
    return value.strip().upper().replace("-", "_").replace(" ", "_")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read valid JSON from {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return payload


def resolve_dataset(path: Path) -> tuple[Path, Path, dict[str, Any], dict[str, Any]]:
    root = path.resolve() if path.is_dir() else path.resolve().parent
    manifest_path = root / "manifest.json" if path.is_dir() else path.resolve()
    labels_path = root / "labels.json"
    manifest = load_json(manifest_path)
    labels = load_json(labels_path)
    dataset = manifest.get("dataset")
    if not isinstance(dataset, dict) or dataset.get("status") != "FROZEN":
        raise ValueError("benchmark dataset must declare dataset.status=FROZEN")
    if labels.get("dataset") != dataset.get("name"):
        raise ValueError("labels.dataset must match manifest.dataset.name")
    return root, manifest_path, manifest, labels


def resolve_image(root: Path, sample: dict[str, Any]) -> Path:
    candidate = (root / str(sample.get("image", ""))).resolve()
    if root != candidate and root not in candidate.parents:
        raise ValueError("image path must stay inside the dataset directory")
    if not candidate.is_file() or candidate.suffix.lower() not in ALLOWED_CONTENT_TYPES:
        raise ValueError(f"unsupported or missing benchmark image: {sample.get('image')}")
    expected_hash = str(sample.get("sha256", ""))
    actual_hash = sha256_file(candidate)
    if actual_hash != expected_hash:
        raise ValueError(f"checksum mismatch for {sample.get('id')}: expected {expected_hash}, got {actual_hash}")
    return candidate


def detected_content_type(path: Path) -> str:
    with Image.open(path) as image:
        content_type = PIL_CONTENT_TYPES.get(str(image.format).upper())
    if content_type is None:
        raise ValueError(f"unsupported benchmark image content: {path.name}")
    return content_type


def validate_and_join_cases(
    root: Path, manifest: dict[str, Any], labels: dict[str, Any]
) -> list[dict[str, Any]]:
    samples = manifest.get("samples")
    cases = labels.get("cases")
    if not isinstance(samples, list) or not samples:
        raise ValueError("manifest.samples must be a non-empty array")
    if not isinstance(cases, list):
        raise ValueError("labels.cases must be an array")
    labels_by_id = {str(case.get("queryId")): case for case in cases if isinstance(case, dict)}
    sample_ids = [str(sample.get("id")) for sample in samples if isinstance(sample, dict)]
    if len(sample_ids) != len(set(sample_ids)):
        raise ValueError("manifest contains duplicate sample IDs")
    if set(sample_ids) != set(labels_by_id):
        raise ValueError("manifest and labels must contain exactly the same query IDs")

    joined: list[dict[str, Any]] = []
    for sample in samples:
        if not isinstance(sample, dict):
            raise ValueError("every manifest sample must be an object")
        query_id = str(sample["id"])
        label = labels_by_id[query_id]
        if label.get("judgmentStatus") != "COMPLETE":
            raise ValueError(f"query {query_id} does not have COMPLETE judgments")
        relevant = label.get("relevantProducts")
        if not isinstance(relevant, list):
            raise ValueError(f"query {query_id} relevantProducts must be an array")
        joined.append({"sample": sample, "label": label, "imagePath": resolve_image(root, sample)})
    return joined


async def catalog_metadata(
    database_url: str, product_ids: set[str], image_ids: set[str]
) -> tuple[dict[str, str], dict[str, str]]:
    product_groups: dict[str, str] = {}
    image_colors: dict[str, str] = {}
    if not product_ids and not image_ids:
        return product_groups, image_colors
    async with await psycopg.AsyncConnection.connect(database_url, connect_timeout=10) as connection:
        async with connection.cursor() as cursor:
            if product_ids:
                await cursor.execute(
                    "select p.id::text, c.slug from products p join categories c on c.id=p.category_id "
                    "where p.id=any(%s::uuid[])",
                    (list(product_ids),),
                )
                product_groups = {row[0]: normalize_group(str(row[1])) for row in await cursor.fetchall()}
            if image_ids:
                await cursor.execute(
                    "select pi.id::text, color.family_code from product_images pi "
                    "left join colors color on color.id=pi.color_id where pi.id=any(%s::uuid[])",
                    (list(image_ids),),
                )
                image_colors = {row[0]: normalize_color(str(row[1])) for row in await cursor.fetchall() if row[1]}
    return product_groups, image_colors


def relevant_products(label: dict[str, Any], relevant_at_or_above: int) -> frozenset[str]:
    return frozenset(
        str(item["productId"])
        for item in label["relevantProducts"]
        if isinstance(item, dict) and int(item.get("grade", 0)) >= relevant_at_or_above
    )


def expected_colors(label: dict[str, Any]) -> frozenset[str]:
    values = label.get("expectedColorFamilies", [])
    if not isinstance(values, list):
        raise ValueError(f"query {label.get('queryId')} expectedColorFamilies must be an array")
    return frozenset(normalize_color(str(value)) for value in values)


def make_observations(
    raw_results: list[dict[str, Any]],
    *,
    similarity_threshold: float,
    product_groups: dict[str, str],
    image_colors: dict[str, str],
    relevant_at_or_above: int,
) -> list[BenchmarkObservation]:
    observations: list[BenchmarkObservation] = []
    for result in raw_results:
        candidates = tuple(
            RankedCandidate(
                product_id=item["productId"],
                image_id=item["imageId"],
                matched_image_url=item["matchedImageUrl"],
                similarity=item["similarity"],
                category_group=product_groups.get(item["productId"]),
                color_family=image_colors.get(item["imageId"]),
            )
            for item in result["candidates"]
            if item["similarity"] >= similarity_threshold
        )
        label = result["label"]
        observations.append(BenchmarkObservation(
            query_id=result["queryId"],
            expected_product_ids=relevant_products(label, relevant_at_or_above),
            expected_group=normalize_group(result["group"]),
            expected_color_families=expected_colors(label),
            judgment_complete=label.get("judgmentStatus") == "COMPLETE",
            candidates=candidates,
            latency_ms=result["latencyMs"],
            error=result.get("error"),
        ))
    return observations


def threshold_candidates(raw_results: list[dict[str, Any]]) -> list[float]:
    scores = sorted({float(item["similarity"]) for result in raw_results for item in result["candidates"]})
    return [0.0, *scores, 1.000001]


def choose_dev_threshold(
    raw_results: list[dict[str, Any]], product_groups: dict[str, str], image_colors: dict[str, str],
    relevant_at_or_above: int,
) -> tuple[float, dict[str, object]]:
    ranked: list[tuple[tuple[float, float, float, float], float, dict[str, object]]] = []
    for threshold in threshold_candidates(raw_results):
        observations = make_observations(
            raw_results, similarity_threshold=threshold, product_groups=product_groups,
            image_colors=image_colors, relevant_at_or_above=relevant_at_or_above,
        )
        metrics = evaluate_benchmark(observations, split="DEV")
        recall = metrics["positive"]["recall"]["at5"] or 0.0  # type: ignore[index]
        unknown_rejection = metrics["unknown"]["rejectionRate"] or 0.0  # type: ignore[index]
        category = metrics["positive"]["categoryAccuracy"]["value"] or 0.0  # type: ignore[index]
        qualifies = 1.0 if recall >= 0.80 else 0.0
        objective = (qualifies, round((recall + unknown_rejection) / 2, 6), category, -threshold)
        ranked.append((objective, threshold, metrics))
    _, threshold, metrics = max(ranked, key=lambda item: item[0])
    return threshold, metrics


def git_commit() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=REPOSITORY_ROOT, check=True,
        capture_output=True, text=True,
    )
    return result.stdout.strip()


def write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


async def run(args: argparse.Namespace) -> int:
    settings = Settings()
    root, manifest_path, manifest, labels = resolve_dataset(args.dataset)
    cases = validate_and_join_cases(root, manifest, labels)
    dataset = manifest["dataset"]
    split = str(dataset["split"]).upper()
    if split == "TEST" and args.tune_threshold:
        raise ValueError("threshold tuning on TEST is forbidden")
    if args.candidate_limit < 10:
        raise ValueError("candidate-limit must be at least 10 to calculate metrics at K=10")
    relevance = labels.get("relevanceScale", {})
    relevant_at_or_above = int(relevance.get("relevantAtOrAbove", 2))

    raw_results: list[dict[str, Any]] = []
    returned_product_ids: set[str] = set()
    returned_image_ids: set[str] = set()
    async with httpx.AsyncClient(timeout=args.request_timeout_seconds) as client:
        for index, case in enumerate(cases, start=1):
            if index > 1 and args.interval_seconds > 0:
                await asyncio.sleep(args.interval_seconds)
            image_path: Path = case["imagePath"]
            started = time.perf_counter()
            error: str | None = None
            candidates: list[dict[str, Any]] = []
            try:
                response = await client.post(
                    f"{args.service_url.rstrip('/')}/internal/v1/search",
                    params={"limit": args.candidate_limit},
                    headers={"X-Internal-Service-Token": settings.internal_service_token},
                    files={"image": (image_path.name, image_path.read_bytes(), detected_content_type(image_path))},
                )
                if response.is_error:
                    detail = response.text[:500].replace("\n", " ")
                    raise httpx.HTTPStatusError(
                        f"HTTP {response.status_code}: {detail}", request=response.request, response=response
                    )
                payload = response.json()
                for item in payload["candidates"]:
                    candidate = {
                        "productId": str(item["product_id"]),
                        "imageId": str(item["image_id"]),
                        "matchedImageUrl": str(item["matched_image_url"]),
                        "similarity": float(item["similarity"]),
                    }
                    candidates.append(candidate)
                    returned_product_ids.add(candidate["productId"])
                    returned_image_ids.add(candidate["imageId"])
            except (httpx.HTTPError, KeyError, TypeError, ValueError) as exc:
                error = f"{type(exc).__name__}: {exc}"
            latency_ms = round((time.perf_counter() - started) * 1000, 2)
            raw_results.append({
                "queryId": str(case["sample"]["id"]),
                "group": str(case["sample"]["group"]),
                "label": case["label"],
                "latencyMs": latency_ms,
                "error": error,
                "candidates": candidates,
            })
            print(f"completed {index}/{len(cases)} latencyMs={latency_ms:.2f}", file=sys.stderr, flush=True)

    product_groups, image_colors = await catalog_metadata(
        settings.database_url, returned_product_ids, returned_image_ids
    )
    tuning: dict[str, object]
    if args.tune_threshold:
        threshold, _ = choose_dev_threshold(raw_results, product_groups, image_colors, relevant_at_or_above)
        tuning = {
            "mode": "DEV_GRID_SEARCH",
            "objective": "maximize mean(Recall@5, unknown rejection rate), require Recall@5>=0.80, deterministic ties",
            "selectedSimilarityThreshold": round(threshold, 8),
        }
    else:
        threshold = args.similarity_threshold
        tuning = {"mode": "LOCKED", "selectedSimilarityThreshold": round(threshold, 8)}

    observations = make_observations(
        raw_results, similarity_threshold=threshold, product_groups=product_groups,
        image_colors=image_colors, relevant_at_or_above=relevant_at_or_above,
    )
    metrics = evaluate_benchmark(observations, split=split)
    ranked_results = {
        "schemaVersion": 1,
        "dataset": {"name": dataset["name"], "version": dataset["version"], "split": split},
        "similarityThreshold": round(threshold, 8),
        "queries": [],
    }
    observation_by_id = {item.query_id: item for item in observations}
    for raw in raw_results:
        observation = observation_by_id[raw["queryId"]]
        ranked_results["queries"].append({
            "queryId": raw["queryId"],
            "group": raw["group"],
            "judgmentStatus": raw["label"]["judgmentStatus"],
            "expectedProductIds": sorted(observation.expected_product_ids),
            "expectedColorFamilies": sorted(observation.expected_color_families),
            "latencyMs": raw["latencyMs"],
            "error": raw["error"],
            "results": [
                {
                    "rank": rank,
                    **candidate,
                    "categoryGroup": product_groups.get(candidate["productId"]),
                    "colorFamily": image_colors.get(candidate["imageId"]),
                    "accepted": candidate["similarity"] >= threshold,
                    "relevant": candidate["productId"] in observation.expected_product_ids,
                }
                for rank, candidate in enumerate(raw["candidates"], start=1)
            ],
        })

    output_dir: Path = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    labels_path = root / "labels.json"
    config = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "gitCommit": git_commit(),
        "dataset": {
            "name": dataset["name"], "version": dataset["version"], "split": split,
            "status": dataset["status"], "manifestSha256": sha256_file(manifest_path),
            "labelsSha256": sha256_file(labels_path),
            "catalogSnapshot": dataset.get("catalogSnapshot"),
        },
        "service": {
            "url": args.service_url, "latencyScope": "visual-service HTTP + preprocessing + embedding + exact vector search",
            "candidateLimit": args.candidate_limit, "requestTimeoutSeconds": args.request_timeout_seconds,
            "intervalSeconds": args.interval_seconds,
        },
        "model": {
            "provider": settings.visual_embedding_provider, "name": settings.visual_embedding_model,
            "revision": settings.visual_embedding_revision, "preprocessing": settings.visual_embedding_preprocessing,
            "dimensions": settings.visual_embedding_dims,
        },
        "ranking": {
            "colorRerankWeight": settings.visual_color_rerank_weight,
            "categoryConsensusWeight": settings.visual_category_consensus_weight,
        },
        "relevance": {"relevantAtOrAbove": relevant_at_or_above, "kValues": [1, 5, 10]},
        "threshold": tuning,
        "hardware": {
            "system": platform.system(), "release": platform.release(), "machine": platform.machine(),
            "processor": platform.processor() or "unknown", "python": platform.python_version(),
        },
    }
    write_json(output_dir / "ranked-results.json", ranked_results)
    write_json(output_dir / "metrics.json", metrics)
    write_json(output_dir / "benchmark-config.json", config)
    print(json.dumps({"outputDir": str(output_dir), "threshold": threshold, "metrics": metrics}, ensure_ascii=False, indent=2))
    if split == "TEST":
        return 0 if metrics["acceptance"]["passed"] else 2  # type: ignore[index]
    return 0 if metrics["requestFailureRate"]["failureCount"] == 0 else 2  # type: ignore[index]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run a frozen image-search DEV or TEST dataset.")
    parser.add_argument("dataset", type=Path, help="Dataset directory or its manifest.json")
    parser.add_argument("--service-url", default="http://127.0.0.1:8090")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--candidate-limit", type=int, default=20)
    parser.add_argument("--similarity-threshold", type=float, default=0.0)
    parser.add_argument("--tune-threshold", action="store_true", help="DEV only: select a threshold from returned scores")
    parser.add_argument("--interval-seconds", type=float, default=0.0)
    parser.add_argument("--request-timeout-seconds", type=float, default=60.0)
    parsed = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(run(parsed)))
    except ValueError as exc:
        parser.error(str(exc))
