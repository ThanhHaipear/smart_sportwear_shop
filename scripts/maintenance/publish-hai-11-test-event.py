from __future__ import annotations

import argparse
import asyncio
import json
import os
from datetime import datetime, timezone
from uuid import UUID

import aio_pika
from aio_pika import DeliveryMode, Message


async def publish(args: argparse.Namespace) -> None:
    connection = await aio_pika.connect_robust(args.rabbit_url)
    try:
        channel = await connection.channel(publisher_confirms=True)
        exchange = await channel.declare_exchange(args.exchange, passive=True)
        payload = {
            "eventId": str(args.event_id),
            "eventType": "PRODUCT_REINDEX_REQUESTED",
            "eventVersion": args.event_version,
            "productId": str(args.product_id),
            "sourceVersion": args.source_version,
            "occurredAt": datetime.now(timezone.utc).isoformat(),
            "traceId": args.trace_id,
        }
        headers = {}
        if args.retry_count is not None:
            headers["x-search-retry-count"] = args.retry_count
        await exchange.publish(
            Message(
                body=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
                content_type="application/json",
                content_encoding="UTF-8",
                delivery_mode=DeliveryMode.PERSISTENT,
                message_id=str(args.event_id),
                correlation_id=args.trace_id,
                headers=headers,
            ),
            routing_key="product.reindex.requested",
            mandatory=True,
        )
        print(json.dumps({
            "published": True,
            "exchange": args.exchange,
            "eventId": str(args.event_id),
            "eventVersion": args.event_version,
            "retryCount": args.retry_count,
            "secretsPrinted": False,
        }))
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish an isolated HAI-11 Catalog test event")
    parser.add_argument("--rabbit-url", default=os.getenv("RABBITMQ_URL", ""))
    parser.add_argument("--exchange", default="catalog.search.hai11")
    parser.add_argument("--event-id", type=UUID, required=True)
    parser.add_argument("--product-id", type=UUID, required=True)
    parser.add_argument("--source-version", type=int, required=True)
    parser.add_argument("--event-version", type=int, default=1)
    parser.add_argument("--retry-count", type=int)
    parser.add_argument("--trace-id", default="hai-11-controlled-publish")
    args = parser.parse_args()
    if not args.rabbit_url:
        raise SystemExit("RABBITMQ_URL is required")
    asyncio.run(publish(args))


if __name__ == "__main__":
    main()
