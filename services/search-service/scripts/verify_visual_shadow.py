"""Replay pinned DEV queries against an isolated visual-search deployment."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from os import environ
from pathlib import Path
from time import perf_counter, sleep
from urllib.parse import quote, urlsplit

import httpx

from scripts.benchmark_test_v3 import load_inputs
from scripts.verify_exact_image_search import percentile_95


ROOT = Path(__file__).resolve().parents[3]
LOCK = ROOT / "datasets/image-search/dev-v4-garment/locked-profile.json"
DEV = LOCK.with_name("manifest.json")
IMAGE_PATH = "/internal/v1/search/image"
PUBLIC_PATH = "/api/v1/products/search-by-image"
STATUS_PATH = "/api/v1/visual-search/status"
CANARY = "/api/v1/products/ao-chay-bo-nam-aero-navy"
QUEUES = ("search.indexing", "search.indexing.retry.30s", "search.indexing.retry.5m",
          "search.indexing.retry.1h", "search.indexing.dlq")
QUEUE_COUNTERS = ("messages", "messages_ready", "messages_unacknowledged", "consumers")


def configured_url(name: str, scope: str) -> str:
    value = environ[name].rstrip("/")
    parsed = urlsplit(value)
    loopback = parsed.hostname in {"127.0.0.1", "localhost", "::1"}
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or (scope == "local" and not loopback)):
        raise ValueError(f"Invalid endpoint setting: {name}")
    return value


def rabbit_snapshot(client: httpx.Client, scope: str) -> dict:
    if "HAI_SHADOW_RABBIT_URL" not in environ:
        if scope == "staging":
            raise ValueError("RabbitMQ management endpoint is required for staging evidence")
        return {"observed": False}
    base = configured_url("HAI_SHADOW_RABBIT_URL", scope)
    auth = (environ["HAI_SHADOW_RABBIT_USER"], environ["HAI_SHADOW_RABBIT_PASSWORD"])
    vhost = quote(environ.get("HAI_SHADOW_RABBIT_VHOST", "/"), safe="")
    queues = {}
    for name in QUEUES:
        url = f"{base}/api/queues/{vhost}/{quote(name, safe='')}"
        deadline = perf_counter() + 15
        while True:
            response = client.get(url, auth=auth)
            if response.status_code != 200:
                raise ValueError(f"RabbitMQ queue {name}: HTTP {response.status_code}")
            data = response.json()
            if all(type(data.get(key)) is int for key in QUEUE_COUNTERS):
                queues[name] = {key: data[key] for key in QUEUE_COUNTERS}
                break
            if perf_counter() >= deadline:
                raise ValueError(f"RabbitMQ queue {name} did not report depth and consumers")
            sleep(1)
    return {"observed": True, "queues": queues}


def timed_post(client, url, image, *, params=None):
    began = perf_counter()
    response = client.post(url, files={"image": image}, params=params)
    return response, (perf_counter() - began) * 1000


def replay_request(client: httpx.Client, url: str, image, params: dict):
    try:
        response, duration = timed_post(client, url, image, params=params)
        return response, round(duration, 3), None
    except httpx.RequestError as error:
        return None, None, type(error).__name__


def locked_expectations(payloads: list) -> dict:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    reference = lock["devCheck"]
    pinned_files = (
        (DEV, reference["manifestSha256"]),
        (DEV.with_name("labels.json"), reference["labelsSha256"]),
        (ROOT / reference["evidence"], reference["evidenceSha256"]),
    )
    for path, checksum in pinned_files:
        if sha256(path.read_text(encoding="utf-8").encode()).hexdigest() != checksum:
            raise ValueError(f"DEV reference checksum differs: {path.name}")
    evidence = json.loads(pinned_files[-1][0].read_text(encoding="utf-8"))
    baseline = evidence["results"]["selectedOnCalibration"]["freshDEVCheck"]["cases"]
    expected = {row["queryId"]: row["top5ProductIds"] for row in baseline}
    if expected.keys() != {sample["id"] for sample, _, _ in payloads}:
        raise ValueError("DEV replay does not match the pinned reference cases")
    return expected


def shadow(search: httpx.Client, gateway: httpx.Client, payloads: list, expected: dict) -> dict:
    cases = []
    for sample, _, image in payloads:
        internal, search_ms, search_error = replay_request(search, IMAGE_PATH, image, {"pilot_v4": "true", "limit": 20})
        external, gateway_ms, gateway_error = replay_request(gateway, PUBLIC_PATH, image, {"limit": 20})
        case = {"id": sample["id"], "group": sample["group"],
                "searchHttp": internal.status_code if internal is not None else None,
                "gatewayHttp": external.status_code if external is not None else None,
                "searchMs": search_ms, "gatewayMs": gateway_ms, "searchError": search_error,
                "gatewayError": gateway_error, "top5Parity": False, "catalogCardsValid": False}
        if case["searchHttp"] == case["gatewayHttp"] == 200:
            candidates = internal.json()["candidates"]
            ids = [row["product_id"] for row in candidates]
            cards = external.json()["data"]
            pairs = {row["product_id"]: row["image_id"] for row in candidates}
            card_ids = [row["product"]["id"] for row in cards]
            card_rank = [(row["product"]["availableQuantity"] <= 0, ids.index(row["product"]["id"]))
                         for row in cards if row["product"]["id"] in pairs]
            case["top5Parity"] = ids[:5] == expected[sample["id"]]
            case["catalogCardsValid"] = (
                len(card_ids) == len(set(card_ids)) == len(card_rank)
                and card_rank == sorted(card_rank)
                and (bool(cards) if sample["group"] != "unknown" else not cards)
                and all(pairs.get(row["product"]["id"]) == row["matchedImageId"]
                        and row["matchedImageUrl"].startswith("https://") for row in cards)
            )
            case["cards"] = len(cards)
        cases.append(case)
    failures = sum(row[key] != 200 for row in cases for key in ("searchHttp", "gatewayHttp"))
    parity = sum(row["top5Parity"] for row in cases)
    hydration = sum(row["catalogCardsValid"] for row in cases)
    search_ms = [row["searchMs"] for row in cases if row["searchMs"] is not None]
    gateway_ms = [row["gatewayMs"] for row in cases if row["gatewayMs"] is not None]
    search_p95 = percentile_95(search_ms) if search_ms else None
    gateway_p95 = percentile_95(gateway_ms) if gateway_ms else None
    return {"cases": cases, "requests": 2 * len(cases), "failures": failures, "top5Parity": parity,
            "catalogCardsValid": hydration, "searchP95Ms": search_p95, "gatewayP95Ms": gateway_p95,
            "passed": failures == 0 and parity == hydration == len(cases) and gateway_p95 <= 3000}


def run(mode: str, scope: str) -> dict:
    _, payloads = load_inputs(DEV, "DEV")
    expected = locked_expectations(payloads)
    gateway_url = configured_url("HAI_SHADOW_GATEWAY_URL", scope)
    search_url = configured_url("HAI_SHADOW_SEARCH_URL", scope)
    headers = {"X-Internal-Service-Token": environ["HAI_SHADOW_SEARCH_TOKEN"]}
    access_token = environ.get("HAI_SHADOW_GATEWAY_ACCESS_TOKEN")
    viewer_headers = {"Authorization": f"Bearer {access_token}"} if access_token else {}
    result = {"mode": mode, "scope": scope, "checkedAt": datetime.now(timezone.utc).isoformat()}
    with (httpx.Client(base_url=gateway_url, headers=viewer_headers, timeout=25, trust_env=False) as gateway,
          httpx.Client(base_url=search_url, headers=headers, timeout=25, trust_env=False) as search,
          httpx.Client(timeout=25, trust_env=False) as broker):
        before = rabbit_snapshot(broker, scope)
        status_response = gateway.get(STATUS_PATH)
        result["publicStatus"] = status_response.json()["data"] if status_response.status_code == 200 else {}
        result["catalogCanaryHttp"] = gateway.get(CANARY).status_code
        health = search.get("/health/ready")
        result["searchDatabaseReady"] = health.status_code == 200 and health.json().get("checks", {}).get("database") is True
        internal = search.get(IMAGE_PATH + "/status")
        result["internalPilotReady"] = internal.status_code == 200 and internal.json()["available"] is True
        result["internalRouteGatewayHttp"] = gateway.get(IMAGE_PATH + "/status").status_code
        state = "READY" if mode == "shadow" else "DISABLED"
        result["passed"] = (
            result["publicStatus"] == {"status": state, "available": mode == "shadow", "enabled": mode == "shadow"}
            and result["catalogCanaryHttp"] == 200 and result["searchDatabaseReady"]
            and result["internalPilotReady"] and result["internalRouteGatewayHttp"] == 404
        )
        if result["passed"] and mode == "shadow":
            warm, _ = timed_post(gateway, PUBLIC_PATH, payloads[0][2])
            result["warmupHttp"] = warm.status_code
            result["replay"] = shadow(search, gateway, payloads, expected)
            result["passed"] = warm.status_code == 200 and result["replay"]["passed"]
        if mode == "rollback":
            blocked, _ = timed_post(gateway, PUBLIC_PATH, payloads[0][2])
            result["rollbackHttp"] = blocked.status_code
            result["passed"] = result["passed"] and blocked.status_code == 503
        after = rabbit_snapshot(broker, scope)
        result["rabbitmq"] = {"before": before, "after": after}
        if before["observed"] and after["observed"]:
            stable = all(after["queues"][queue]["messages"] <= before["queues"][queue]["messages"]
                         for queue in QUEUES)
            result["rabbitmq"]["noBacklogGrowth"] = stable
            result["passed"] = result["passed"] and stable
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("shadow", "rollback"))
    parser.add_argument("--scope", required=True, choices=("local", "staging"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = run(args.mode, args.scope)
    except httpx.RequestError as error:
        parser.exit(1, f"Deployment request failed: {type(error).__name__}\n")
    except (ValueError, KeyError) as error:
        parser.exit(1, f"Verification failed: {error}\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("mode", "scope", "passed")}))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
