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
.\.venv\Scripts\python run.py
.\.venv\Scripts\python -m app.worker
```

Apply `migrations/V1__create_ai_search.sql` with the migration role first. Set
`search_app` as the runtime database user and use the same internal token in the
backend and this service.

## Contracts

- `POST /internal/v1/search`: canonical JSON text/hybrid search API.
- `POST /internal/v1/search/image`: canonical multipart image search API.
- `GET /internal/v1/search/image/status`: availability of the evaluated v4 pilot.
- `GET /health/live`, `GET /health/ready`, `GET /metrics`.

All search endpoints require `X-Internal-Service-Token`. Query images are never
persisted.

The default image profile uses the locked DEV v2.2 category-consensus weight and similarity
threshold. Changing either value requires a new DEV/independent TEST cycle; do
not retune against TEST v2.

## TEST v4 pilot

Set `SEARCH_ENABLED=true`, `VISUAL_SEARCH_ENABLED=true`,
`VISUAL_SEARCH_PILOT_V4_ENABLED=true` and `VISUAL_EMBEDDING_DEVICE=cpu` in the
isolated pilot deployment. Install `requirements-embeddings.txt` and make the
pinned SigLIP2 revision available in the model cache. For Compose, build Search
with `SEARCH_INSTALL_LOCAL_EMBEDDINGS=true`.

The Search API validates the active image index against the evaluated 81 base
vectors and adds four pinned references from the experiment. The references
participate in ranking and matched images come from the indexed catalog.
A catalog mismatch makes the pilot unavailable.

Set `VISUAL_SEARCH_PUBLIC_ENABLED=true` on Catalog in the isolated environment
to test through Storefront. Catalog uses `SEARCH_API_BASE_URL` and the same
`SEARCH_SERVICE_INTERNAL_TOKEN` as Search. It resolves the returned image and
product IDs against the current catalog before serving the cards. With the
Catalog flag off, its public POST returns 503.

[TEST v4 acceptance](../../evidence/image-search-v4/test-v4-final/final-report.md)

### Shadow và rollback trên staging

Triển khai Search và Catalog với các cờ pilot ở trên trong môi trường staging
cô lập. Trỏ `HAI_SHADOW_GATEWAY_URL` và `HAI_SHADOW_SEARCH_URL` đến hai endpoint
staging. Đặt `HAI_SHADOW_SEARCH_TOKEN` thành token nội bộ của Search. Cấu hình
`HAI_SHADOW_RABBIT_URL`, `HAI_SHADOW_RABBIT_USER` và
`HAI_SHADOW_RABBIT_PASSWORD` cho RabbitMQ Management trên mạng nội bộ.
`HAI_SHADOW_RABBIT_VHOST` mặc định là `/`.

Chạy từ `services/search-service`. Shadow gửi 24 ảnh DEV đã khóa, so thứ tự
top-5 tại Search, kiểm tra kết quả Catalog qua Gateway và đo p95. Báo cáo ghi
độ sâu và số consumer của hàng đợi chính, retry và DLQ trước và sau.

```powershell
python -m scripts.verify_visual_shadow --scope staging --mode shadow --output ../../evidence/image-search-v4/staging-shadow.json
```

Tắt `VISUAL_SEARCH_PUBLIC_ENABLED` trên Catalog staging rồi triển khai lại
Catalog. Xác minh Gateway trả 503 khi tải ảnh, trong khi trang sản phẩm và
Search nội bộ vẫn hoạt động. Bật lại cờ trên staging và chạy shadow lần nữa
để xác nhận phục hồi. Dùng tên evidence khác cho lần phục hồi.

```powershell
python -m scripts.verify_visual_shadow --scope staging --mode rollback --output ../../evidence/image-search-v4/staging-rollback.json
```

`--scope local` dùng cho diễn tập qua loopback. Báo cáo local ghi rõ khi thiếu
RabbitMQ. Chạy staging và kiểm tra backlog chưa xử lý trước khi mở cohort.

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
