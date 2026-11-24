"""Check the phase 8 SQL of PostgresRepository against the real `recommendation` schema without changing it.

Inserts three throw-away baskets, rebuilds association rules and serves one recommendation inside a single
transaction, prints what was stored (model config, cutoff, checksum, user_ref, latency_ms, reason codes),
then rolls back and confirms row counts are unchanged.

Usage (same environment as the service, e.g. via scripts/run/common-local.ps1):
    python scripts/verify_model_tracking.py
"""
import sys
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from uuid import uuid4

sys.path.insert(0, ".")
import psycopg  # noqa: E402

from app.config import get_settings  # noqa: E402
from app.repository import PostgresRepository  # noqa: E402
from app.service import RecommendationService  # noqa: E402

settings = get_settings()
conn = psycopg.connect(settings.recommendation_database_url, options="-c search_path=recommendation,public")
before = {t: conn.execute(f"select count(*) from {t}").fetchone()[0]
          for t in ("recommendation_models", "association_rules", "recommendation_requests", "order_baskets")}
repo = PostgresRepository(settings.recommendation_database_url)


@contextmanager
def shared():
    yield conn


repo.connection = shared
try:
    products = [row[0] for row in conn.execute("select product_id from catalog_products where status='ACTIVE' "
                                               "and available_quantity>0 order by product_id limit 3").fetchall()]
    past = datetime.now(timezone.utc) - timedelta(days=1)
    for _ in range(3):
        conn.execute("insert into order_baskets(order_id,user_ref,product_ids,occurred_at) values(%s,%s,%s,%s)",
                     (uuid4(), "verify-phase8", products[:2], past))
    service = RecommendationService(repo, content_mode="COMPLEMENT")
    version = "verify-phase8-" + uuid4().hex[:8]
    rules = service.rebuild(version)
    model = conn.execute("select algorithm,status,config,training_cutoff_at,training_event_checksum from "
                         "recommendation_models where version=%s", (version,)).fetchone()
    response = service.recommend({products[0]}, 4, "FREQUENTLY_BOUGHT_TOGETHER", "verify-user")
    request = conn.execute("select user_ref,latency_ms,model_version from recommendation_requests where id=%s",
                           (response.requestId,)).fetchone()
    reasons = [r[0] for r in conn.execute("select reason_code from recommendation_items where request_id=%s "
                                          "order by rank", (response.requestId,)).fetchall()]
    print("rules mined:", rules)
    print("model row:", model[0], model[1], "| config keys:", sorted(model[2]), "| baskets:", model[2]["baskets"],
          "| cutoff set:", model[3] is not None, "| checksum len:", len(model[4]))
    print("request row: user_ref =", request[0], "| latency_ms =", request[1], "| model_version =", request[2])
    print("reason codes:", reasons)
finally:
    conn.rollback()
    after = {t: conn.execute(f"select count(*) from {t}").fetchone()[0] for t in before}
    conn.close()
    print("row counts before:", before)
    print("row counts after rollback:", after, "| unchanged:", before == after)
