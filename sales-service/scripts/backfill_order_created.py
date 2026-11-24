"""Plan or enqueue ORDER_CREATED for an explicit list of Sales orders."""

import argparse
from collections import Counter
import json
import os
from uuid import UUID, uuid4

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


EVENT_TYPE = "sales.order.created.v1"


def backfill(connection, order_id, *, execute, replay, include_cancelled):
    query = "select id,user_ref,placed_at,order_status from sales.orders where id=%s"
    order = connection.execute(query + (" for update" if execute else ""), (order_id,)).fetchone()
    if order is None:
        return "missing_order"
    if order["order_status"] == "CANCELLED" and not include_cancelled:
        return "cancelled"

    query = """
        select id,status from sales.integration_outbox where event_type=%s and aggregate_id=%s
        order by occurred_at desc, id desc limit 1
        """
    existing = connection.execute(query + (" for update" if execute else ""), (EVENT_TYPE, order_id)).fetchone()
    if existing is not None:
        if not replay or existing["status"] not in {"PUBLISHED", "FAILED"}:
            return "already_queued"
        if execute:
            connection.execute("""
                update sales.integration_outbox set status='PENDING', attempts=0,
                    published_at=null, next_attempt_at=null where id=%s
                """, (existing["id"],))
        return "requeued"

    variants = connection.execute("""
        select distinct variant_ref from sales.order_items where order_id=%s order by variant_ref
        """, (order_id,)).fetchall()
    if not variants:
        return "empty_order"
    if execute:
        payload = {"eventId": str(uuid4()), "eventType": "ORDER_CREATED", "eventVersion": 1,
                   "occurredAt": order["placed_at"].isoformat(), "traceId": f"sales-{order_id}",
                   "orderId": str(order_id), "userRef": str(order["user_ref"]),
                   "items": [str(row["variant_ref"]) for row in variants]}
        connection.execute("""
            insert into sales.integration_outbox(event_type,aggregate_type,aggregate_id,payload,occurred_at)
            values (%s,'Order',%s,%s,%s)
            """, (EVENT_TYPE, order_id, Jsonb(payload), order["placed_at"]))
    return "enqueued"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--order-id", action="append", type=UUID, required=True)
    parser.add_argument("--include-cancelled", action="store_true")
    parser.add_argument("--replay", action="store_true", help="also requeue a PUBLISHED or FAILED event with its original ID")
    parser.add_argument("--execute", action="store_true", help="commit the plan")
    args = parser.parse_args()
    database_url = os.getenv("SALES_MAINTENANCE_DATABASE_URL")
    if not database_url:
        parser.error("SALES_MAINTENANCE_DATABASE_URL is required")

    with psycopg.connect(database_url, row_factory=dict_row) as connection:
        connection.read_only = not args.execute
        counts = Counter(backfill(connection, order_id, execute=args.execute, replay=args.replay,
                                  include_cancelled=args.include_cancelled)
                         for order_id in sorted(set(args.order_id)))
    print(json.dumps({"committed": args.execute, "orders": counts}))


if __name__ == "__main__":
    main()
