# Search Service

Phase 3 unified service for attribute/keyword, semantic, and image search. It owns
only derived data in `ai_search`. Catalog state enters through
`GET /internal/v1/products/{id}/index-document` and durable catalog events; this
codebase contains no SQL against catalog-owned tables.

## Run

```powershell
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-test.txt
Copy-Item .env.example .env
.\.venv\Scripts\python -m uvicorn app.main:app --port 8090
.\.venv\Scripts\python -m app.worker
```

Apply `migrations/V1__create_ai_search.sql` with the migration role first. Set
`search_app` as the runtime database user and use the same internal token in the
backend and this service.

## Contracts

- `POST /internal/v1/search`: canonical JSON text/hybrid search API.
- `POST /internal/v1/search/image`: canonical multipart image search API.
- `GET /health/live`, `GET /health/ready`, `GET /metrics`.

All search endpoints require `X-Internal-Service-Token`. Query images are never
persisted.

Image results use the locked DEV v2.2 category-consensus weight and similarity
threshold. Changing either value requires a new DEV/independent TEST cycle; do
not retune against TEST v2.

## Exact image search pilot

The image query scores all READY vectors for ACTIVE products before ranking.
It takes the closest image per product, using image ID for ties, then uses
product ID and image ID to order tied product scores. The final reranker uses
the same product/image tie-break. V3 drops only the HNSW index on the image
table. Deploy V3 with the migration role after backing up the image table or
capturing its current index definition.

Use a Search PostgreSQL DSN in `DATABASE_URL` and run from this service
directory. Omitting the manifest runs only the read-only SQL checks. With a
DEV manifest, the script starts a loopback-only Search API and measures two
requests per selected query after one warmup. The SigLIP2 revision must already
be in the local cache.

```powershell
python -m scripts.verify_exact_image_search ../../datasets/image-search/dev-v2/manifest.json --output exact-search-report.json
```

To restore the index for a release that requires it, use the migration role
and allow time for index creation to lock the image table. Restoring this index
leaves the current pilot query exact.

```sql
set role search_owner;
create index if not exists idx_ai_search_image_embedding
    on ai_search.image_embeddings using hnsw (embedding extensions.vector_cosine_ops);
reset role;
```

## Backfill and rollback

Use `scripts/backfill.py --ids products.txt` to rebuild from the Catalog API.
It is dry-run by default and requires `--execute` to write. Rollback is routing
only: stop routing traffic to this service while preserving `ai_search` for
investigation. The index is derived and can be rebuilt from Catalog after the
incident is resolved.
