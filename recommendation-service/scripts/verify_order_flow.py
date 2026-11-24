"""Review R5: ORDER_CREATED -> RabbitMQ -> recommendation worker -> order_baskets, in an isolated environment.

Starts a scratch PostgreSQL (V1-V3) and a RabbitMQ loaded with the repo's rabbitmq/definitions.json, a Catalog
stub for variant -> product lookups, and the real worker (`python -m app.worker`). It then publishes messages
exactly as SalesOutboxRelay does (exchange business.events, routing key order.created, payload built like
JdbcSalesRepository.createOrder: items are VARIANT ids) and checks:

  1. the message is routable (business.events -> recommendation.events binding exists);
  2. the basket stores PRODUCT ids resolved from the variant ids, and purchase_count goes up once;
  3. replaying the same message (same eventId) does not create a second basket or count;
  4. Sales re-publishing the same order with a new eventId does not either;
  5. variants of the same product collapse to one product in the basket;
  6. after a second order with the same pair, rebuilding the model mines an association rule.

Usage (Docker running):  .venv\\Scripts\\python.exe scripts\\verify_order_flow.py [--evidence FILE] [--keep]
Never touches Supabase or the shared local RabbitMQ: everything runs in throwaway containers on other ports.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from uuid import UUID, uuid4

import aio_pika
import psycopg

SERVICE = Path(__file__).resolve().parents[1]
ROOT = SERVICE.parent
sys.path.insert(0, str(SERVICE / "scripts"))
sys.path.insert(0, str(SERVICE))
import verify_migration_v3 as migrations  # noqa: E402

PG = {"name": "r5-postgres", "port": 55434, "password": "r5"}
MQ = {"name": "r5-rabbitmq", "volume": "r5-rabbitmq-data", "port": 55673, "user": "r5", "password": "r5"}

SHOE, SOCK, SHORTS = (UUID(f"{n}{n * 7}-{n * 4}-4{n * 3}-8{n * 3}-{n * 12}") for n in ("a", "b", "c"))
VARIANTS = {uuid4(): SHOE, uuid4(): SOCK, uuid4(): SOCK, uuid4(): SHORTS}  # two sock sizes
VARIANT_OF = {}
for variant, product in VARIANTS.items():
    VARIANT_OF.setdefault(product, []).append(variant)


def docker(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(["docker", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args[:3])} failed: {(result.stderr or result.stdout).strip()[-500:]}")
    return result


class CatalogStub(BaseHTTPRequestHandler):
    """Only what EventEnricher needs: variant -> product (products are already in catalog_products)."""

    def do_GET(self):
        parts = self.path.strip("/").split("/")
        if parts[:3] == ["internal", "v1", "variants"] and UUID(parts[3]) in VARIANTS:
            body = json.dumps({"productId": str(VARIANTS[UUID(parts[3])])}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        else:
            self.send_response(404); self.end_headers()

    def log_message(self, *args):
        pass


def start_containers() -> tuple[str, str]:
    for container in (PG["name"], MQ["name"]):
        docker("rm", "-f", container, check=False)
    docker("run", "-d", "--name", PG["name"], "-e", f"POSTGRES_PASSWORD={PG['password']}", "-p",
           f"{PG['port']}:5432", "postgres:17-alpine")
    # Copy (not bind-mount) the repo config: single-file bind mounts from Windows break the image's startup.
    rabbit_dir = ROOT / "rabbitmq"
    # Every `docker exec` below runs as user rabbitmq: a root CLI call during boot would create
    # /var/lib/rabbitmq/.erlang.cookie owned by root and the server would then fail with eacces.
    docker("volume", "rm", "-f", MQ["volume"], check=False)
    docker("create", "--name", MQ["name"], "-p", f"{MQ['port']}:5672", "-v", f"{MQ['volume']}:/var/lib/rabbitmq",
           "rabbitmq:3.13-management-alpine")
    for name in ("definitions.json", "rabbitmq.conf"):
        docker("cp", str(rabbit_dir / name), f"{MQ['name']}:/etc/rabbitmq/{name}")
    docker("start", MQ["name"])
    admin = f"postgresql://postgres:{PG['password']}@127.0.0.1:{PG['port']}/postgres"
    for _ in range(90):
        ready = docker("exec", "-u", "rabbitmq", MQ["name"], "rabbitmq-diagnostics", "-q", "check_running", check=False).returncode == 0
        try:
            with psycopg.connect(admin, connect_timeout=2):
                pg_ready = True
        except psycopg.OperationalError:
            pg_ready = False
        if ready and pg_ready:
            break
        time.sleep(2)
    # A throwaway user (the definitions' user has an unknown password hash).
    docker("exec", "-u", "rabbitmq", MQ["name"], "rabbitmqctl", "add_user", MQ["user"], MQ["password"])
    docker("exec", "-u", "rabbitmq", MQ["name"], "rabbitmqctl", "set_permissions", "-p", "/", MQ["user"], ".*", ".*", ".*")
    url = migrations.fresh_database(admin, "rec_r5")
    migrations.apply(url, migrations.V1, migrations.V2, migrations.V3)
    migrations.enable_runtime_login(url, "rec_r5")
    with psycopg.connect(url) as connection:
        for index, product in enumerate((SHOE, SOCK, SHORTS)):
            snapshot = {"id": str(product), "name": f"R5 product {index}", "slug": f"r5-{index}",
                        "availableQuantity": 5, "status": "ACTIVE"}
            connection.execute("insert into recommendation.catalog_products(product_id,source_version,status,"
                               "available_quantity,snapshot) values(%s,1,'ACTIVE',5,%s)", (product, json.dumps(snapshot)))
    return url, f"amqp://{MQ['user']}:{MQ['password']}@127.0.0.1:{MQ['port']}/"


def sales_payload(order_id: UUID, variants: list[UUID], event_id: UUID | None = None) -> dict:
    """Same fields as JdbcSalesRepository.createOrder's outbox payload (items = variant ids)."""
    return {"eventId": str(event_id or uuid4()), "eventType": "ORDER_CREATED", "eventVersion": 1,
            "occurredAt": datetime.now(timezone.utc).isoformat(), "traceId": f"sales-{order_id}",
            "orderId": str(order_id), "userRef": str(uuid4()), "items": [str(v) for v in variants]}


async def publish(amqp_url: str, payload: dict) -> bool:
    """Publish like SalesOutboxRelay; mandatory=True makes an unroutable message an error instead of a drop."""
    connection = await aio_pika.connect(amqp_url)
    try:
        channel = await connection.channel(publisher_confirms=True, on_return_raises=True)
        exchange = await channel.get_exchange("business.events", ensure=True)
        await exchange.publish(aio_pika.Message(json.dumps(payload).encode(), content_type="application/json",
                                                message_id=str(uuid4())), routing_key="order.created", mandatory=True)
        return True
    finally:
        await connection.close()


def state(url: str) -> dict:
    with psycopg.connect(url) as connection:
        baskets = {str(row[0]): sorted(str(p) for p in row[1]) for row in connection.execute(
            "select order_id, product_ids from recommendation.order_baskets").fetchall()}
        counts = {str(row[0]): row[1] for row in connection.execute(
            "select product_id, purchase_count from recommendation.catalog_products").fetchall()}
        processed = connection.execute("select count(*) from recommendation.processed_events "
                                       "where consumer_name='recommendation-events'").fetchone()[0]
    return {"baskets": baskets, "purchaseCount": counts, "processedEvents": processed}


def wait_for(url: str, predicate, timeout: float = 30) -> dict:
    deadline = time.time() + timeout
    while True:
        current = state(url)
        if predicate(current) or time.time() > deadline:
            return current
        time.sleep(0.5)


def queue_depths() -> dict:
    out = docker("exec", "-u", "rabbitmq", MQ["name"], "rabbitmqctl", "-q", "list_queues", "name", "messages").stdout
    return {line.split()[0]: int(line.split()[1]) for line in out.splitlines()[1:]
            if line.startswith("recommendation.events")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--keep", action="store_true")
    args = parser.parse_args()
    checks: list[dict] = []
    stub = HTTPServer(("127.0.0.1", 0), CatalogStub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    worker = None
    try:
        url, amqp_url = start_containers()
        runtime = migrations.with_database(url, "rec_r5", "recommendation_app", migrations.RUNTIME_PASSWORD)
        env = {**os.environ, "RECOMMENDATION_ENABLED": "true", "RECOMMENDATION_DATABASE_URL": runtime,
               "RECOMMENDATION_INTERNAL_TOKEN": "r5", "RABBITMQ_URL": amqp_url,
               "CATALOG_API_URL": f"http://127.0.0.1:{stub.server_port}", "CATALOG_SERVICE_TOKEN": "r5"}
        worker = subprocess.Popen([sys.executable, "-m", "app.worker"], cwd=SERVICE, env=env,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        time.sleep(4)
        shoe, sock_m, sock_l, shorts = VARIANT_OF[SHOE][0], VARIANT_OF[SOCK][0], VARIANT_OF[SOCK][1], VARIANT_OF[SHORTS][0]

        order1 = uuid4()
        first = sales_payload(order1, [shoe, sock_m])
        routable = asyncio.run(publish(amqp_url, first))
        after1 = wait_for(url, lambda s: str(order1) in s["baskets"])
        checks.append({"check": "1 order.created is routable to recommendation.events", "passed": routable})
        checks.append({"check": "2 basket holds product ids resolved from variant ids; purchase_count +1",
                       "passed": after1["baskets"].get(str(order1)) == sorted([str(SHOE), str(SOCK)])
                       and after1["purchaseCount"][str(SHOE)] == 1 and after1["purchaseCount"][str(SOCK)] == 1,
                       "basket": after1["baskets"].get(str(order1)), "variantsSent": [str(shoe), str(sock_m)]})

        asyncio.run(publish(amqp_url, first))  # identical message, same eventId
        time.sleep(3)
        after_replay = state(url)
        checks.append({"check": "3 replaying the same event creates no duplicate basket or count",
                       "passed": len(after_replay["baskets"]) == 1 and after_replay["purchaseCount"] == after1["purchaseCount"]
                       and after_replay["processedEvents"] == after1["processedEvents"]})

        asyncio.run(publish(amqp_url, sales_payload(order1, [shoe, sock_m])))  # Sales re-publish, new eventId
        after_republish = wait_for(url, lambda s: s["processedEvents"] > after1["processedEvents"])
        checks.append({"check": "4 same order with a new eventId creates no duplicate basket or count",
                       "passed": len(after_republish["baskets"]) == 1
                       and after_republish["purchaseCount"] == after1["purchaseCount"]})

        order2 = uuid4()
        asyncio.run(publish(amqp_url, sales_payload(order2, [shoe, sock_m, sock_l, shorts])))
        after2 = wait_for(url, lambda s: str(order2) in s["baskets"])
        checks.append({"check": "5 two sizes of the same product collapse to one product id",
                       "passed": after2["baskets"].get(str(order2)) == sorted([str(SHOE), str(SOCK), str(SHORTS)]),
                       "basket": after2["baskets"].get(str(order2))})

        from app.repository import PostgresRepository
        from app.service import RecommendationService
        rules = RecommendationService(PostgresRepository(runtime)).rebuild("r5-check")
        recommended = RecommendationService(PostgresRepository(runtime)).recommend({SHOE}, 3, "FREQUENTLY_BOUGHT_TOGETHER")
        checks.append({"check": "6 rebuild mines rules from the delivered baskets; shoe -> sock is served",
                       "passed": rules > 0 and recommended.items[0].product.id == SOCK
                       and recommended.items[0].algorithm == "ASSOCIATION_RULE",
                       "rules": rules, "top": [(str(i.product.id), i.algorithm) for i in recommended.items]})
        depths = queue_depths()
        checks.append({"check": "7 nothing left in recommendation queues, retries or DLQ",
                       "passed": all(v == 0 for v in depths.values()), "queues": depths})
    finally:
        if worker:
            worker.terminate()
            try:
                worker.wait(10)
            except subprocess.TimeoutExpired:
                worker.kill()
        stub.shutdown()
        if not args.keep:
            for container in (PG["name"], MQ["name"]):
                docker("rm", "-f", container, check=False)
            docker("volume", "rm", "-f", MQ["volume"], check=False)

    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
    report = {"review": "R5", "commit": commit, "ranAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
              "scope": "RabbitMQ (repo definitions) -> real worker -> scratch PostgreSQL; Sales relay not included",
              "passed": len(checks) == 7 and all(c["passed"] for c in checks), "checks": checks}
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(text + "\n", encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(text)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
