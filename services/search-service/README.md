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
- `POST /internal/v1/product-search`: compatibility alias for the Spring client.
- `POST /internal/v1/search/image`: canonical multipart image search API.
- `GET /health/live`, `GET /health/ready`, `GET /metrics`.

All search endpoints require `X-Internal-Service-Token` (the legacy
`X-Internal-Token` header remains accepted during cut-over). Query images are
never persisted.

Image results use the locked DEV v2.2 category-consensus weight and similarity
threshold. Changing either value requires a new DEV/independent TEST cycle; do
not retune against TEST v2.

## Backfill and rollback

Use `scripts/backfill.py --ids products.txt` to rebuild from the Catalog API.
It is dry-run by default and requires `--execute` to write. Rollback is routing
only: set Spring `PRODUCT_SEARCH_SERVICE_URL` back to the chatbot endpoint and
`VISUAL_SEARCH_SERVICE_URL` back to the legacy visual service. `ai_search` is
derived and can be dropped/rebuilt only after rollback verification.
