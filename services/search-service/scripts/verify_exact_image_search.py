"""Verify the pilot image query against a full cosine scan and time the internal API."""

from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version
from itertools import chain
import json
import math
import mimetypes
import os
from pathlib import Path
import platform
import re
import secrets
from threading import Thread
from time import perf_counter, sleep
from unittest.mock import patch

import httpx
import psycopg
from psycopg.rows import dict_row
import uvicorn

from app.config import Settings, get_settings
from app.main import create_app
from app.persistence.repository import EXACT_IMAGE_SEARCH_SQL, SearchRepository, vector_literal


def percentile_95(samples: list[float]) -> float:
    return round(sorted(samples)[math.ceil(0.95 * len(samples)) - 1], 3)


def plan_nodes(node: dict):
    yield node
    for child in node.get("Plans", []):
        yield from plan_nodes(child)


def verify_sql(dsn: str) -> dict:
    with psycopg.connect(dsn, row_factory=dict_row, connect_timeout=10, prepare_threshold=None) as db:
        db.execute("set transaction isolation level repeatable read read only")
        rows = db.execute("""
            select e.product_id,e.image_id,e.embedding::text as vector
            from ai_search.image_embeddings e join ai_search.search_documents d using(product_id)
            where e.status='READY' and d.status='ACTIVE'
            order by e.product_id,e.image_id
        """).fetchall()
        if not rows:
            raise ValueError("The pilot image index has no eligible vectors")
        checksum = db.execute("""
            select md5(string_agg(image_id::text||embedding::text,',' order by image_id)) as checksum
            from ai_search.image_embeddings where status='READY'
        """).fetchone()["checksum"]
        indexes = db.execute("""
            select indexname,indexdef from pg_indexes
            where schemaname='ai_search' and tablename='image_embeddings' order by indexname
        """).fetchall()
        probes = {row["product_id"]: row for row in reversed(rows)}
        vectors = [json.loads(row["vector"]) for row in rows]
        times, plans = [], []
        parity = 0
        for probe in probes.values():
            vector = json.loads(probe["vector"])
            params = (vector_literal(vector), 100)
            plan = db.execute(
                "explain (analyze,buffers,format json) " + EXACT_IMAGE_SEARCH_SQL, params,
            ).fetchone()["QUERY PLAN"][0]
            plans.append(plan)
            times.append(plan["Execution Time"])
            best = {}
            for row, image_vector in zip(rows, vectors, strict=True):
                similarity = math.fsum(a * b for a, b in zip(vector, image_vector, strict=True)) / (
                    math.hypot(*vector) * math.hypot(*image_vector)
                )
                key = (-similarity, row["product_id"], row["image_id"])
                best[row["product_id"]] = min(best.get(row["product_id"], key), key)
            reference = sorted(best.values())
            ranked = db.execute(EXACT_IMAGE_SEARCH_SQL, params).fetchall()
            parity += len(reference) == len(ranked) and all(
                row["product_id"] == product and row["image_id"] == image
                and abs(row["similarity"] + negative_similarity) < 2e-6
                for row, (negative_similarity, product, image) in zip(ranked, reference, strict=True)
            )
        index_names = {row["indexname"] for row in indexes if " using hnsw" in row["indexdef"].lower()}
        scans = Counter(
            node["Node Type"] for node in chain.from_iterable(plan_nodes(plan["Plan"]) for plan in plans)
            if node.get("Relation Name") == "image_embeddings"
        )
        ann_used = any(
            node.get("Index Name") in index_names
            for node in chain.from_iterable(plan_nodes(plan["Plan"]) for plan in plans)
        )
        representative_plan = json.loads(re.sub(
            r"'\[[\d.eE,+-]+\]'::(?:extensions\.)?vector",
            "'[vector omitted]'::vector",
            json.dumps(plans[0]),
        ))
        return {
            "eligibleImages": len(rows),
            "eligibleProducts": len(probes),
            "vectorChecksum": checksum,
            "indexes": indexes,
            "probes": len(probes),
            "matchingExhaustiveReference": parity,
            "imageScanTypes": dict(scans),
            "annUsed": ann_used,
            "p95ExecutionMs": percentile_95(times),
            "representativeExplainAnalyzeBuffers": representative_plan,
            "passed": not ann_used and parity == len(probes),
        }


@contextmanager
def internal_api(settings: Settings, *, overrides=None):
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides.update(overrides or {})
    original_connection = SearchRepository.connection

    @asynccontextmanager
    async def read_only_connection(repository):
        async with original_connection(repository) as db:
            await db.set_read_only(True)
            yield db

    config = uvicorn.Config(app, host="127.0.0.1", port=0, access_log=False, log_level="warning")
    server = uvicorn.Server(config)
    with config.bind_socket() as listener:
        thread = Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        with patch("app.main.get_settings", return_value=settings), patch.object(
            SearchRepository, "connection", read_only_connection,
        ):
            thread.start()
            try:
                deadline = perf_counter() + 120
                while not server.started:
                    if not thread.is_alive() or perf_counter() >= deadline:
                        raise RuntimeError("The local Search API did not start")
                    sleep(0.1)
                yield f"http://127.0.0.1:{listener.getsockname()[1]}"
            finally:
                server.should_exit = True
                thread.join(timeout=10)


def verify_api(dsn: str, manifest: Path) -> dict:
    dataset = json.loads(manifest.read_text(encoding="utf-8"))
    if dataset["dataset"]["split"] != "DEV":
        raise ValueError("Use only DEV images for the exact-search benchmark")
    selected = Counter()
    samples = []
    for sample in dataset["samples"]:
        group = sample["group"]
        if selected[group] >= 5:
            continue
        path = manifest.parent / sample["image"]
        content = path.read_bytes()
        if hashlib.sha256(content).hexdigest() != sample["sha256"]:
            raise ValueError(f"Dataset checksum mismatch for {sample['id']}")
        samples.append((sample["id"], (path.name, content, mimetypes.guess_type(path)[0])))
        selected[group] += 1
    if not samples:
        raise ValueError("DEV dataset contains no images")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    if platform.system() == "Windows":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    settings = Settings(
        _env_file=None,
        database_url=dsn,
        search_enabled=True,
        visual_search_enabled=True,
        visual_embedding_provider="hugging_face_local",
        visual_embedding_device="cpu",
        internal_service_token=secrets.token_urlsafe(),
        catalog_service_token=secrets.token_urlsafe(),
    )
    durations, statuses, previous = [], Counter(), {}
    deterministic = unique = nonempty = 0
    with internal_api(settings) as base_url, httpx.Client(base_url=base_url, trust_env=False, timeout=20) as client:
        client.headers["X-Internal-Service-Token"] = settings.internal_service_token
        endpoint = "/internal/v1/search/image?limit=20"
        warmup = client.post(endpoint, files={"image": samples[0][1]})
        warmup.raise_for_status()
        for repetition in range(2):
            for sample_id, image in samples:
                started = perf_counter()
                response = client.post(endpoint, files={"image": image})
                durations.append((perf_counter() - started) * 1000)
                statuses[response.status_code] += 1
                if response.status_code != 200:
                    continue
                candidates = response.json()["candidates"]
                ranked = [(row["product_id"], row["image_id"]) for row in candidates]
                unique += len({product for product, _ in ranked}) == len(ranked)
                nonempty += bool(ranked)
                if repetition == 0:
                    previous[sample_id] = ranked
                elif sample_id in previous:
                    deterministic += previous[sample_id] == ranked
    p95 = percentile_95(durations)
    return {
        "dataset": dataset["dataset"],
        "manifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
        "queriesByGroup": dict(selected),
        "queryIds": [sample_id for sample_id, _ in samples],
        "runtime": {
            "python": platform.python_version(),
            "platform": platform.system(),
            "device": settings.visual_embedding_device,
            "torch": version("torch"),
            "transformers": version("transformers"),
            "model": settings.visual_embedding_model,
            "revision": settings.visual_embedding_revision,
            "preprocessing": settings.visual_embedding_preprocessing,
            "categoryWeight": settings.visual_category_consensus_weight,
            "minimumSimilarity": settings.visual_min_similarity,
            "cpuThreads": os.getenv("OMP_NUM_THREADS"),
            "transport": "loopback HTTP",
            "warmupRequestsExcluded": 1,
        },
        "statusCounts": dict(statuses),
        "requests": len(durations),
        "nonemptyResponses": nonempty,
        "uniqueProductResponses": unique,
        "repeatRankingsMatched": deterministic,
        "p95EndToEndMs": p95,
        "passed": p95 <= 3000 and statuses == {200: len(durations)}
        and unique == len(durations) and nonempty > 0 and deterministic == len(samples),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, nargs="?", help="DEV manifest for the loopback API benchmark")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        parser.error("Set DATABASE_URL to the Search PostgreSQL DSN")
    report = {
        "schemaVersion": 1,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "sql": verify_sql(dsn),
    }
    if args.manifest:
        report["api"] = verify_api(dsn, args.manifest)
    report["passed"] = report["sql"]["passed"] and report.get("api", {"passed": True})["passed"]
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(args.output), "passed": report["passed"]}))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
