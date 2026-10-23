# Visual Search Service

Independent FastAPI service for visual product search. The V2 query path uses the
pinned local SigLIP2 model and the versioned image index in `ai_retrieval`.

## Local development

```powershell
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-test.txt
.\.venv\Scripts\pytest
.\.venv\Scripts\python scripts\run_api.py
```

Copy `.env.example` to `.env` for local overrides. Never commit provider or internal-service secrets.

## Phase 3 image pipeline

Catalog downloads accept only HTTPS URLs from the configured Cloudinary cloud or
Shopify `/s/files/` path. Redirect targets and resolved/connected IP addresses are
checked to prevent SSRF, and response bytes, MIME type, decoded pixels and image
format are bounded before a deterministic JPEG is produced and hashed.

Install `requirements-embeddings.txt` on the host serving image queries. Set
`VISUAL_EMBEDDING_PROVIDER=hugging_face_local`; the model name, immutable revision,
preprocessing version and 768 dimensions must match the ACTIVE `PRODUCT_IMAGE`
row in `ai_retrieval.embedding_models`. The legacy Voyage provider remains only
for historical comparison and rollback evidence.

## Phase 6 search API

`POST /internal/v1/search?limit=20` accepts a multipart field named `image` and
allows up to 50 internal candidates so Spring can apply commerce filters before
returning at most 20 public results. It requires `X-Internal-Service-Token`,
validates and normalizes the upload,
creates a normalized SigLIP2 query embedding, calls
`ai_retrieval.search_product_images_exact`, groups matches by product with a
stable tie-break, and excludes stale/non-READY embeddings through the database
function. Spring applies current Commerce status, inventory, category, gender
and price filters. Query images are not stored.

## Phase 4 indexing worker

Start the durable catalog consumer separately from the HTTP process:

```powershell
python -m app.worker
```

The worker uses manual acknowledgements and a configurable prefetch (default 5).
Transient database/network/provider failures are republished as persistent
messages through the 30-second, 5-minute and 1-hour TTL queues. Invalid events,
permanent image errors and exhausted retries are sent to
`visual-search.indexing.dlq`. Duplicate event IDs and unchanged READY image
hashes are skipped without another provider call.

`GET /health/ready` verifies the database connection, an ACTIVE model matching
the configured provider/model/dimensions, and the main RabbitMQ queue. It returns
HTTP 503 with component states when any dependency is unavailable. When running
the Python process directly on the host, `RABBITMQ_URL` must use `localhost`;
the `rabbitmq` hostname is only valid from another Docker Compose container.

Run the broker integration test after starting RabbitMQ:

```powershell
$env:RUN_RABBITMQ_INTEGRATION='1'
python -m pytest -q
```

## Phase 7 backfill and reconciliation

Both maintenance commands are dry-run by default and print only counts/reasons:

```powershell
python scripts/backfill_embeddings.py
python scripts/reconcile_embeddings.py --include-failed
```

After Flyway applies `V35`, the backend outbox publisher and the visual worker
are healthy, enqueue in a bounded batch:

```powershell
python scripts/backfill_embeddings.py --enqueue --limit 25
python scripts/reconcile_embeddings.py --enqueue --include-failed --limit 25
```

To avoid retrying permanent image-policy failures, retry only transient provider
failures with `python scripts/reconcile_embeddings.py --enqueue --retryable-failed-only`.

An operator can inspect a delayed retry queue, then move a bounded number back
to the main queue after fixing the cause:

```powershell
python scripts/requeue_retry_messages.py visual-search.indexing.retry.1h --max 25
python scripts/requeue_retry_messages.py visual-search.indexing.retry.1h --max 25 --execute
python scripts/requeue_retry_messages.py visual-search.indexing.dlq --max 25 --execute
```

These commands never call the embedding provider directly. They create an
indexing job plus contract-v1 outbox events; the normal RabbitMQ consumer does
the indexing and updates job completion counters.

The worker also runs reconciliation automatically. By default it starts after
60 seconds and then checks once per hour. A PostgreSQL advisory lock plus the
recent-job check prevents multiple worker replicas from creating duplicate
scheduled jobs. Permanent image-policy failures are reported but are not
automatically retried.

```dotenv
RECONCILIATION_ENABLED=true
RECONCILIATION_INTERVAL_SECONDS=3600
RECONCILIATION_INITIAL_DELAY_SECONDS=60
RECONCILIATION_PROCESSING_TIMEOUT_MINUTES=15
RECONCILIATION_BATCH_SIZE=100
```

## Phase 9 admin observability

The internal admin API is protected by `X-Internal-Service-Token` and is never
called directly by browser code. Spring Boot exposes an ADMIN-only proxy under
`/api/v1/admin/visual-search`; the admin application renders it at
`/visual-search`.

Available internal endpoints:

- `GET /internal/v1/admin/operations`: active model, outbox status and
  main/retry/DLQ queue depth. Database metrics remain available if RabbitMQ is
  temporarily unreachable.
- `GET /internal/v1/admin/coverage`, `/usage` and `/jobs`.
- `POST /internal/v1/admin/retry-failed` and `/backfill-missing`.
- `POST /internal/v1/admin/reindex` with exactly one `image_id` or `product_id`.
- `GET /internal/v1/admin/models` and `POST /models/{id}/activate` provide
  transaction-safe model activation/rollback with a minimum 98% READY gate.

All maintenance actions create indexing jobs and transactional outbox events;
they never call the embedding provider from the HTTP request.

Usage cost is estimated using `IMAGE_COST_PER_MEGAPIXEL_USD` (default
`0.0006`). Operations exposes monthly cost/budget, and query embedding stops
with HTTP 429 when `MONTHLY_BUDGET_USD` reaches 100%.

## Phase 10 benchmark and rollout

The acceptance benchmark uses at least 100 independently sourced images across
`tops`, `bottoms` and `shoes`. `accessories` is N/A because the audited ACTIVE
catalog currently has no accessory product with an image, and becomes required
when one is added. The benchmark rejects a rollout unless
Recall@5 is at least 80%, top-result category accuracy is at least 90%, and
end-to-end p95 latency is at most 3 seconds. See
`evidence/visual-search/benchmark-manifest.example.json` for the manifest shape:

```powershell
python scripts/benchmark_search.py ..\evidence\visual-search\benchmark-manifest.json `
  --output ..\evidence\visual-search\phase10-benchmark-report.json
```

Benchmark images must remain outside Git and must not be catalog originals,
copies or direct crops. The full staged rollout, monitoring and non-destructive
rollback procedure is in `docs/visual-search-rollout.md`.

When independent photos are unavailable, an explicitly approved synthetic
robustness set can be reproduced with `python scripts/build_synthetic_benchmark.py
../evidence/visual-search/synthetic-benchmark`. Generated images are ignored by
Git; the manifest records source IDs, seeds and transformations. This mode does
not claim real-world generalization.
