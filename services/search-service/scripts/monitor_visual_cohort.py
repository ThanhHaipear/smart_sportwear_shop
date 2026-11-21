"""Measure member traffic and RabbitMQ during an image-search pilot window."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from os import fstat
from pathlib import Path
from time import monotonic, sleep

import httpx

from scripts.verify_exact_image_search import percentile_95
from scripts.verify_visual_shadow import PUBLIC_PATH, rabbit_snapshot

MAIN = "search.indexing"
DLQ = "search.indexing.dlq"


def observe(log: Path, seconds: float, interval: float) -> dict:
    began = datetime.now(timezone.utc)
    statuses = Counter()
    successful_ms = []
    blocked = 0
    samples = []
    with log.open("rb") as source:
        prefix = source.read(3)
    encoding = {b"\xff\xfe": "utf-16-le", b"\xfe\xff": "utf-16-be"}.get(prefix[:2], "utf-8")
    with (log.open("r", encoding=encoding, errors="replace") as stream,
          httpx.Client(timeout=10, trust_env=False, limits=httpx.Limits(max_keepalive_connections=0)) as broker):
        original = fstat(stream.fileno())
        stream.seek(0, 2)
        position = stream.tell()
        deadline = monotonic() + seconds
        while True:
            try:
                queues = rabbit_snapshot(broker, "staging")["queues"]
            except httpx.TransportError:
                queues = rabbit_snapshot(broker, "staging")["queues"]
            samples.append({"at": datetime.now(timezone.utc).isoformat(), "queues": queues})
            current = log.stat()
            if original.st_ino != current.st_ino or current.st_size < position:
                raise ValueError("Gateway log was replaced or truncated during the pilot window")
            for line in iter(stream.readline, ""):
                if not line.endswith("\n"):
                    stream.seek(position)
                    break
                position = stream.tell()
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if not isinstance(event, dict) or (
                    event.get("event"), event.get("method"), event.get("path")
                ) != ("gateway_request", "POST", PUBLIC_PATH):
                    continue
                if event.get("visualCohortMember") is True:
                    status = event["status"]
                    statuses[status] += 1
                    if status == 200:
                        successful_ms.append(event["durationMs"])
                elif event.get("visualCohortMember") is False:
                    blocked += 1
            remaining = deadline - monotonic()
            if remaining <= 0:
                break
            sleep(min(remaining, interval))

    count = statuses.total()
    p95 = percentile_95(successful_ms) if successful_ms else None
    dlq_max = max(row["queues"][DLQ]["messages"] for row in samples)
    consumer_min = min(row["queues"][MAIN]["consumers"] for row in samples)
    errors = count - len(successful_ms)
    decision = "INSUFFICIENT_TRAFFIC"
    if errors or (p95 is not None and p95 > 3000) or dlq_max or not consumer_min:
        decision = "HOLD"
    elif count:
        decision = "PASS_OPERATIONAL_WINDOW"
    return {"startedAt": began.isoformat(), "finishedAt": datetime.now(timezone.utc).isoformat(),
            "memberRequests": count, "blockedRequests": blocked, "httpStatuses": dict(statuses),
            "memberErrors": errors, "gatewayP95Ms": p95, "peakDlqDepth": dlq_max,
            "minimumConsumers": consumer_min, "queueSamples": samples, "decision": decision}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-log", type=Path, required=True)
    parser.add_argument("--seconds", type=float, required=True)
    parser.add_argument("--interval", type=float, default=15)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not (0 < args.seconds < float("inf") and 0 < args.interval < float("inf")):
        parser.error("Window duration and polling interval must be positive and finite")
    try:
        result = observe(args.gateway_log, args.seconds, args.interval)
    except (OSError, ValueError, KeyError, httpx.HTTPError) as error:
        parser.exit(1, f"Could not complete the observation window ({type(error).__name__})\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("memberRequests", "gatewayP95Ms", "memberErrors", "peakDlqDepth", "decision")}))
    if result["decision"] != "PASS_OPERATIONAL_WINDOW":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
