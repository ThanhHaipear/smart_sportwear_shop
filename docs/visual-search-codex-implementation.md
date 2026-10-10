# Codex implementation brief: Visual Product Search

## 1. Nhiệm vụ

Triển khai hoàn chỉnh chức năng tìm kiếm sản phẩm bằng hình ảnh cho repository này. Đọc toàn bộ tài liệu này và `docs/visual-search-plan.md` trước khi sửa code. Thực hiện theo từng phase nhỏ, chạy kiểm thử sau mỗi phase và không làm hỏng chatbot, forecasting, tìm kiếm chữ hoặc nghiệp vụ commerce hiện tại.

Không tự huấn luyện model. Provider mặc định là Voyage `voyage-multimodal-3.5`; kiến trúc phải cho phép bổ sung Cohere sau này.

## 2. Context bắt buộc

- Backend: Java 21, Spring Boot 3.5.x trong `backend/`.
- Storefront: Next.js trong `frontend/storefront/`.
- Admin: Next.js trong `frontend/admin/`.
- AI chatbot hiện tại: FastAPI/Python trong `chatbot-service/`; không đặt visual search vào module này.
- Database: Supabase PostgreSQL có pgvector và Flyway migrations chính thức trong `backend/src/main/resources/db/v2-migration/`.
- Ảnh ACTIVE hiện gồm 442 URL `cdn.shopify.com` và 134 URL `res.cloudinary.com`; 142 ảnh thiếu `public_id`. Không giả định mọi ảnh đều nằm trên Cloudinary.
- `product_embeddings vector(1536)` hiện dành cho OpenAI text embedding; không sửa, xóa hoặc dùng bảng này cho ảnh.
- Dữ liệu đã đo: 228 sản phẩm, 107 ACTIVE, 676 ảnh, trong đó 576 ảnh thuộc sản phẩm ACTIVE; chỉ 102 sản phẩm ACTIVE có ảnh và 5 sản phẩm ACTIVE chưa có ảnh.
- 100 ảnh INACTIVE hiện dùng đường dẫn tương đối; không backfill chúng cho tới khi URL được sửa/migrate.
- Docker Compose hiện có Redis; cần bổ sung RabbitMQ mà không làm ảnh hưởng Redis.

## 3. Kết quả cuối cùng phải có

1. Module mới `visual-search-service/` chạy độc lập bằng FastAPI.
2. Voyage provider tạo document/query embeddings 1024 chiều.
3. Image source resolver/downloader an toàn cho Cloudinary và Shopify CDN, có normalize/resize chung.
4. Supabase pgvector repository và migrations.
5. RabbitMQ consumer với manual ACK, retry, DLQ và idempotency.
6. Spring Boot transactional outbox và RabbitMQ publisher confirms.
7. Tự đồng bộ khi ảnh/sản phẩm được thêm, cập nhật, xóa hoặc đổi ACTIVE/INACTIVE.
8. Backfill và reconciliation cho dữ liệu hiện hữu.
9. Spring public endpoint `POST /api/v1/products/search-by-image`.
10. Storefront camera/upload/preview/crop/results.
11. Admin coverage/usage/retry tối thiểu.
12. Unit, integration và contract tests phù hợp.
13. README, `.env.example`, Docker Compose và run script cập nhật.

## 4. Quy tắc triển khai

- Bảo toàn mọi thay đổi không liên quan đang có trong worktree.
- Trước khi chọn số migration, liệt kê toàn bộ migrations và lấy version kế tiếp thực tế; không mặc định version trong tài liệu còn trống.
- Không ghi secret thật vào repository, log, exception hoặc response.
- Không gọi Voyage trong unit test; dùng fake provider.
- Không tải internet trong unit test; dùng mock HTTP transport hoặc fixture ảnh nhỏ.
- Không lưu ảnh query của khách mặc định.
- Không đưa binary ảnh hoặc vector vào RabbitMQ.
- Consumer phải idempotent vì RabbitMQ có thể giao message nhiều lần.
- Chỉ ACK sau khi transaction lưu kết quả/processed event thành công.
- Nếu provider, RabbitMQ hoặc visual service lỗi, các luồng commerce khác vẫn hoạt động.
- Không tạo lại embedding khi chỉ đổi giá, tồn kho, alt text, sort order hoặc primary image.
- Mọi query kết quả public phải loại sản phẩm không ACTIVE.
- Tất cả URL tải server-side phải qua allowlist chính xác cho Cloudinary và Shopify CDN để tránh SSRF; không cho phép hostname tùy ý.

## 5. Cấu trúc module yêu cầu

```text
visual-search-service/
├── app/
│   ├── api/
│   │   ├── search.py
│   │   ├── indexing.py
│   │   ├── admin.py
│   │   └── health.py
│   ├── consumers/catalog_event_consumer.py
│   ├── messaging/
│   │   ├── rabbitmq.py
│   │   ├── topology.py
│   │   └── retry.py
│   ├── providers/
│   │   ├── base.py
│   │   ├── voyage.py
│   │   └── fake.py
│   ├── repositories/
│   ├── services/
│   ├── schemas/
│   ├── config/
│   └── main.py
├── scripts/
│   ├── backfill_embeddings.py
│   ├── reconcile_embeddings.py
│   └── benchmark_providers.py
├── tests/
├── Dockerfile
├── requirements.txt
├── requirements-test.txt
├── pytest.ini
├── README.md
└── .env.example
```

Có thể điều chỉnh tên file nhỏ nếu phù hợp convention hiện hữu, nhưng phải giữ separation giữa API, provider, service, repository và messaging.

## 6. Database design

Tạo schema `visual_search` và các bảng dưới đây bằng Flyway:

### 6.1. `visual_search.model_versions`

- `id uuid primary key`
- `provider`, `model`, `dimensions`
- trạng thái `BUILDING`, `ACTIVE`, `INACTIVE`, `FAILED`
- chỉ một version ACTIVE tại một thời điểm
- timestamps và coverage fields phù hợp

### 6.2. `visual_search.image_embeddings`

- FK `image_id -> product_images(id) ON DELETE CASCADE`
- FK `product_id -> products(id) ON DELETE CASCADE`
- FK model version
- `embedding vector(1024)` cho Voyage version đầu tiên
- `image_hash`, Cloudinary version/etag nếu có
- trạng thái `PENDING`, `PROCESSING`, `READY`, `FAILED`, `STALE`
- attempts, error message, timestamps
- unique/primary key đảm bảo một vector trên mỗi image/model version

Không dùng một row trên mỗi product. Mỗi ảnh phải có vector riêng. Khi trả sản phẩm, score sản phẩm MVP là `max(image_similarity)`.

### 6.3. `visual_search.processed_events`

Lưu `event_id` duy nhất để consumer xử lý message trùng an toàn.

### 6.4. `visual_search.indexing_jobs`

Theo dõi backfill/reindex với total, pending, processing, completed, failed và timestamps.

### 6.5. `visual_search.usage_events`

Lưu provider, model, operation, request count, text tokens, image pixels, estimated cost USD, latency, success/failure và timestamp. Không lưu ảnh query.

### 6.6. `integration_outbox`

Nếu repository chưa có outbox tổng quát, tạo bảng có event ID, type, version, aggregate ID/type, payload JSONB, status, attempts, available time, created/published time và last error. Việc ghi outbox phải nằm cùng transaction với thay đổi catalog.

## 7. Image source integration

- Tạo `ImageSourceResolver` phân loại ít nhất `res.cloudinary.com` và `cdn.shopify.com`; host khác là permanent error.
- Với Cloudinary, ưu tiên `public_id` để sinh delivery URL có transformation; fallback sang `image_url` đã validate. Transformation mặc định: `c_limit,w_1024,h_1024,q_auto:good,f_jpg`.
- Với Shopify CDN, dùng URL HTTPS đã validate, tải về rồi normalize/resize trong Python; không nối transformation Cloudinary.
- Chỉ chấp nhận HTTPS và hostname/path đúng allowlist cấu hình. Chống DNS rebinding/private IP nếu HTTP client tự resolve host.
- Giới hạn redirect, timeout, content length, decoded pixel và MIME.
- Giới hạn input catalog/query mặc định 5 MB và 16 triệu decoded pixels; ngưỡng này bao phủ ảnh catalog đã audit tới 4000 x 4000, sau đó resize tối đa 1024 x 1024 trước khi gửi model.
- Tính SHA-256 trên normalized bytes. Nếu hash và model version không đổi thì không gọi provider.
- Gửi bytes/base64 đã normalize cho provider thay vì để provider tự tải URL catalog.
- Ảnh public không yêu cầu Cloudinary API secret ở visual service; chỉ thêm secret nếu một yêu cầu cụ thể bắt buộc.

## 8. Provider interface

Tạo interface tương đương:

```python
class MultimodalEmbeddingProvider(Protocol):
    async def embed_document(
        self, image: bytes, text: str | None = None
    ) -> EmbeddingResult: ...

    async def embed_query(self, image: bytes) -> EmbeddingResult: ...
```

`EmbeddingResult` phải chứa vector, model, dimensions và usage (image pixels/text tokens nếu provider trả về).

Voyage implementation:

- model mặc định `voyage-multimodal-3.5`
- dimensions mặc định 1024
- dùng `input_type=document` khi index catalog
- dùng `input_type=query` khi xử lý ảnh người dùng
- timeout và retry tạm thời phải có giới hạn
- kiểm tra dimension trước khi lưu
- không log API key, binary hoặc vector đầy đủ

Fake provider phải deterministic để test ranking và idempotency.

## 9. RabbitMQ và outbox

### Topology

```text
exchange: catalog.events (topic, durable)
queue: visual-search.indexing
retry: visual-search.indexing.retry.30s
retry: visual-search.indexing.retry.5m
retry: visual-search.indexing.retry.1h
dlq: visual-search.indexing.dlq
```

Bindings bao gồm:

```text
product.image.created
product.image.deleted
product.activated
product.deactivated
product.reindex.requested
```

Message contract version 1:

```json
{
  "eventId": "uuid",
  "eventType": "PRODUCT_IMAGE_CREATED",
  "eventVersion": 1,
  "productId": "uuid",
  "imageId": "uuid",
  "occurredAt": "ISO-8601",
  "traceId": "string-or-uuid"
}
```

Chỉ thêm binding `product.image.updated` khi triển khai nghiệp vụ thay thế nội dung/URL ảnh. Source hiện có create/upload, delete và set-primary nhưng chưa có replace-image; set-primary hoặc sửa metadata không được reindex.

Spring publisher:

- thêm `spring-boot-starter-amqp`
- durable/persistent delivery
- correlated publisher confirms
- chỉ đánh dấu outbox PUBLISHED sau broker confirm
- retry outbox có backoff và không chặn request commerce

Python consumer:

- dùng thư viện async RabbitMQ phù hợp, ưu tiên `aio-pika`
- manual ACK
- prefetch cấu hình, mặc định 3-5
- validate schema/version
- kiểm tra `processed_events`
- retry lỗi mạng, 429, 5xx và DB timeout
- ảnh hỏng, message sai version/schema hoặc URL catalog 404 ổn định đi DLQ
- không `sleep` giữ message unacked; dùng retry queue TTL/dead-lettering

## 10. Event production points

Tìm các service Spring hiện quản lý product/image/status và thêm outbox event tại đúng transaction:

- ảnh tạo mới sau khi upload Cloudinary hoặc thêm URL catalog và `product_images` lưu thành công
- ảnh được thay thế/cập nhật URL hoặc nội dung nếu nghiệp vụ replace-image được bổ sung
- ảnh bị xóa
- sản phẩm chuyển ACTIVE
- sản phẩm chuyển INACTIVE

Không phát reindex event khi chỉ thay đổi price, stock, alt text, sort order hoặc primary flag.
Khi update product, phải so sánh trạng thái cũ/mới và chỉ phát activation/deactivation event khi có transition thực sự.

Xóa ảnh vẫn phải hoạt động nếu RabbitMQ down. Database cascade xử lý vector; event phục vụ cleanup/observability bổ sung.

## 11. Indexing logic

1. Nhận event và validate.
2. Kiểm tra idempotency.
3. Đọc trạng thái mới nhất từ Supabase bằng `imageId`; không tin dữ liệu catalog snapshot trong message.
4. Nếu ảnh/sản phẩm đã xóa: cleanup nếu cần, ghi processed và ACK.
5. Nếu sản phẩm INACTIVE: không bắt buộc gọi model; đánh dấu/giữ trạng thái phù hợp và ACK.
6. Resolve nguồn Cloudinary/Shopify và xây URL tải hợp lệ.
7. Download, validate, normalize và resize ảnh.
8. Tính hash.
9. Nếu row READY cùng hash/model: ghi processed và ACK.
10. Đặt PROCESSING có lock/version tránh hai worker tranh nhau.
11. Gọi provider document embedding.
12. Trong một transaction: upsert READY, ghi usage, ghi processed event, cập nhật job count.
13. ACK sau commit.

Nếu lỗi trước commit, reject/dead-letter sang retry theo policy. Sau số lần giới hạn, đặt FAILED và chuyển DLQ.

## 12. Search logic

### Visual service

1. Validate multipart image.
2. Decode, normalize/resize và không lưu lâu dài.
3. Tạo query embedding.
4. Truy vấn exact cosine trên các row READY thuộc active model version.
5. Join/filter products ACTIVE hoặc trả candidate IDs để Spring kiểm tra lại.
6. Lấy top khoảng 50 ảnh, group theo product, score là max similarity.
7. Trả top 20 mặc định gồm productId, imageId, matchedImageUrl, similarity.
8. Ghi usage/search event không chứa ảnh.

Không tạo ANN index cho 576 vector nếu benchmark cho thấy exact search đã đủ. Chỉ thêm HNSW khi catalog lớn hoặc query plan chứng minh cần thiết.

### Spring public API

`POST /api/v1/products/search-by-image`:

- multipart field `image`
- optional `limit`, category, gender, min/max price
- xây rate limit riêng cho visual search theo user/session/IP; không giả định backend đã có rate limiter dùng chung
- gọi visual service bằng internal token
- lấy thông tin product/price/thumbnail/inventory mới nhất từ backend repository/service
- luôn lọc ACTIVE lần cuối
- timeout/failure trả lỗi có kiểm soát; không làm lỗi các API commerce khác

Contract response cần phù hợp envelope `ApiResponse` hiện tại.

## 13. Storefront và admin

Storefront:

- thêm camera icon cạnh search hiện tại
- hỗ trợ file picker và mobile capture
- preview, remove và crop vùng sản phẩm
- trạng thái loading/error/empty
- render product cards hiện hữu và matched image
- client-side validation nhưng server vẫn validate lại
- accessibility: label, keyboard, alt text và thông báo lỗi

Admin tối thiểu:

- active provider/model
- tổng ảnh ACTIVE và READY/PENDING/PROCESSING/FAILED
- coverage phần trăm
- RabbitMQ/outbox pending và DLQ count nếu có API khả dụng
- usage/cost theo ngày/tháng
- retry failed, reindex image/product và backfill missing

Không expose provider secret hoặc internal service token cho browser.

## 14. Backfill và reconciliation

Backfill tối đa 576 ảnh ACTIVE bằng cùng pipeline RabbitMQ, không tạo một pipeline đặc biệt gọi AI trực tiếp:

1. Tạo indexing job.
2. Query ảnh của products ACTIVE.
3. Ghi một outbox event trên mỗi image ID theo batch nhỏ.
4. Publisher phát dần lên RabbitMQ.
5. Consumer xử lý và cập nhật job counters.

Backfill phải ghi riêng số ảnh Cloudinary, Shopify, host không hỗ trợ, URL tương đối, download/decode lỗi và READY. Năm sản phẩm ACTIVE chưa có ảnh không được tính là lỗi embedding; chúng được báo trong product visual coverage.

Reconciliation chạy định kỳ:

- tìm ảnh ACTIVE không có READY embedding cho active model
- tìm FAILED/STALE đủ điều kiện retry
- tìm PROCESSING quá timeout
- phát hiện row/model version không khớp
- không gọi lại provider nếu hash/model đã đúng
- cung cấp dry-run/report trước khi requeue hàng loạt

## 15. Configuration

Tạo `.env.example`, không commit giá trị thật. Tối thiểu:

```text
VISUAL_SEARCH_ENABLED=false
VISUAL_EMBEDDING_PROVIDER=voyage
VISUAL_EMBEDDING_MODEL=voyage-multimodal-3.5
VISUAL_EMBEDDING_DIMS=1024
VOYAGE_API_KEY=
DATABASE_URL=
RABBITMQ_URL=amqp://visual_search:change-me@rabbitmq:5672/
RECONCILIATION_ENABLED=true
RECONCILIATION_INTERVAL_SECONDS=3600
RECONCILIATION_INITIAL_DELAY_SECONDS=60
RECONCILIATION_PROCESSING_TIMEOUT_MINUTES=15
RECONCILIATION_BATCH_SIZE=100
INTERNAL_SERVICE_TOKEN=
CLOUDINARY_CLOUD_NAME=
CATALOG_IMAGE_ALLOWED_HOSTS=res.cloudinary.com,cdn.shopify.com
MAX_UPLOAD_BYTES=5242880
MAX_IMAGE_PIXELS=16000000
TARGET_IMAGE_MAX_WIDTH=1024
TARGET_IMAGE_MAX_HEIGHT=1024
SEARCH_RATE_LIMIT_PER_MINUTE=10
MONTHLY_BUDGET_USD=20
STORE_QUERY_IMAGES=false
```

Feature flag mặc định false cho đến khi migration, model version và backfill sẵn sàng.

## 16. Docker và local run

- Bổ sung RabbitMQ management image vào root `docker-compose.yml`, giữ nguyên Redis.
- Dùng named volume và health check.
- Bổ sung visual-search-service với dependency health condition phù hợp.
- Port management 15672 chỉ dành local; ghi rõ không public production.
- Tạo script start tương ứng theo convention repository nếu cần.
- README phải mô tả thứ tự: start infra, backend, visual service, backfill, storefront.

## 17. Tests bắt buộc

### Python unit tests

- Cloudinary/Shopify allowlist, host lạ và SSRF rejection
- image MIME/size/pixel validation
- hash skip logic
- provider dimension validation
- similarity grouping dùng max image score
- event schema/version validation
- idempotent duplicate event
- retryable và permanent error classification
- usage cost calculation

### Python integration tests

- PostgreSQL/pgvector insert và cosine ordering
- RabbitMQ publish/consume/ACK/retry/DLQ nếu test infra cho phép
- event -> fake provider -> READY embedding
- deleted/inactive image behavior

### Spring tests

- outbox nằm cùng transaction với catalog mutation
- rollback không để lại outbox event
- publisher confirm mới đánh dấu PUBLISHED
- public endpoint validation, timeout và ACTIVE filtering
- API security/rate-limit behavior

### Frontend tests

- chọn/xóa/crop ảnh
- reject file sai định dạng hoặc quá lớn
- loading/error/empty/result states
- request contract chính xác

## 18. Verification và rollout

Rollout theo thứ tự:

1. Merge migrations và infra với feature flag off.
2. Deploy visual service và kiểm tra health/readiness.
3. Tạo active model version.
4. Chạy backfill tối đa 576 ảnh ACTIVE và xuất report theo nguồn/lỗi.
5. Xác nhận embedding coverage `READY / ảnh ACTIVE hợp lệ, decode được` tối thiểu 98%; báo product visual coverage riêng.
6. Chạy benchmark ít nhất 100 ảnh ngoài catalog, phân tầng áo/quần/giày/phụ kiện và không dùng bản sao/crop trực tiếp từ catalog.
7. Xác nhận Recall@1, Recall@5 >= 80%, category accuracy >= 90% và p95 <= 3 giây; lưu rõ ground truth và cách tính.
8. Bật endpoint cho admin/internal trước.
9. Bật storefront theo rollout nhỏ.
10. Theo dõi usage, DLQ, latency, errors và budget.

Rollback bằng feature flag; commerce và text search phải tiếp tục hoạt động. Không xóa model/vector cũ cho đến khi model mới đã ổn định.

## 19. Definition of Done

- Toàn bộ test liên quan pass.
- Không có secret hoặc ảnh query trong source/log/database ngoài thiết kế.
- Thêm/cập nhật/xóa ảnh tự đồng bộ qua outbox và RabbitMQ.
- Duplicate delivery không tạo lỗi hoặc chi phí lặp không cần thiết.
- Ảnh Cloudinary hoặc Shopify hợp lệ mới searchable trong không quá một phút ở điều kiện bình thường.
- Sản phẩm INACTIVE không xuất hiện.
- Có backfill, reconciliation, retry và DLQ.
- Coverage và usage/cost xem được từ admin hoặc API quản trị.
- README và run instructions tái lập được trên môi trường mới.
- `docs/visual-search-plan.md` được giữ đồng bộ nếu quyết định kiến trúc thay đổi.

## 20. Cách Codex nên thực hiện

Không triển khai tất cả trong một thay đổi khổng lồ. Trước khi code, kiểm tra worktree, conventions, security config, product image lifecycle và test infrastructure. Sau đó chia thành các phase có thể chạy độc lập:

1. Database + contracts.
2. Visual service core + fake provider tests.
3. Voyage + Cloudinary/Shopify image pipeline.
4. RabbitMQ consumer + retry/DLQ.
5. Spring outbox + publisher.
6. Search API.
7. Backfill/reconciliation.
8. Storefront.
9. Admin/observability.
10. Benchmark, hardening và rollout docs.

Sau mỗi phase, chạy test phù hợp và báo rõ file thay đổi, test đã chạy, rủi ro còn lại và phase tiếp theo. Không chạy backfill production hoặc bật feature flag production nếu chưa được người dùng cho phép rõ ràng.

## 21. Tiến độ triển khai

> Cập nhật checklist này ngay sau khi code và kiểm thử của từng phase hoàn tất. Không đánh dấu hoàn tất cho công việc chỉ mới được tạo khung.

- [x] Phase 0 - Audit URL + POC Voyage + ground truth (`evidence/visual-search/phase0-decision.md` and machine-readable report; preliminary GO on 2026-07-30)
  - [x] Audit dữ liệu Supabase và phân bố nguồn ảnh (số liệu tại `docs/visual-search-plan.md`, mục 2)
  - [x] Chọn và kiểm tra mẫu ảnh đại diện từ cả Cloudinary và Shopify CDN
  - [x] Chạy POC `voyage-multimodal-3.5` document/query embedding trên mẫu
  - [x] Định nghĩa ground-truth nghiệm thu ngoài catalog; POC đo bộ alternate-view riêng và không dùng thay benchmark cuối
  - [x] Ghi quyết định go/no-go của model và pipeline

### Tiến độ theo kế hoạch 0-15 trong `visual-search-plan.md`

- [x] Phase 1 - Cấu hình môi trường Supabase, Cloudinary, Shopify allowlist, Voyage và RabbitMQ (`Settings` kiểm tra fail-fast khi bật feature; RabbitMQ local có healthcheck, volume và management chỉ bind loopback; hướng dẫn local được cập nhật; verified 2026-07-30 bằng unit tests và `docker compose config`)
  - [x] Runtime `.env` chạy trực tiếp dùng RabbitMQ `localhost`; Supabase có đúng một model `voyage-multimodal-3.5` 1024 chiều ở trạng thái ACTIVE; readiness kiểm tra thật database/model/RabbitMQ và trả 503 khi dependency chưa sẵn sàng (verified 2026-07-30 bằng `.env` hiện tại)
- [x] Phase 3 - RabbitMQ exchange, main/retry/DLQ và message contract (`rabbitmq/definitions.json` tự nạp durable topology; retry TTL 30s/5m/1h quay về main queue; reject từ main vào DLQ; JSON Schema v1 giới hạn đúng metadata; verified 2026-07-30 bằng 43 Python tests, `docker compose config`, broker healthy và `rabbitmqctl` xác nhận exchange/queues/bindings thực tế)
- [x] Phase 7 - Consumer idempotent, manual ACK, retry và DLQ (`aio-pika` worker với prefetch 5; validate event v1; kiểm tra `processed_events`; đọc catalog mới nhất; hash-skip; claim PROCESSING; commit READY + usage + processed; lỗi transient đi retry TTL, lỗi permanent/cạn retry ghi FAILED và vào DLQ; verified 2026-07-30 bằng 55 Python tests, gồm publish/consume/ACK/retry trên RabbitMQ thật)

### Tiến độ chuỗi triển khai kỹ thuật trong brief

- [x] Phase 1 - Database + contracts (`V34__visual_search_core.sql`, `contracts/catalog-event-v1.schema.json`; verified 2026-07-30 with Spring context + Flyway on PostgreSQL/pgvector Testcontainers)
- [x] Phase 2 - Visual service core + fake provider tests (FastAPI health endpoints, typed settings/provider contract and deterministic fake; verified 2026-07-30 with 6 unit tests)
- [x] Phase 3 - Voyage + Cloudinary/Shopify image pipeline (official multimodal REST contract, base64 normalized bytes, document/query input types, bounded retry/dimension checks, exact host/path allowlist, DNS/peer SSRF checks, redirect/byte/MIME/pixel limits and deterministic JPEG hashing; verified 2026-07-30 with 28 Python tests)
- [x] Phase 4 - RabbitMQ consumer + retry/DLQ (manual ACK sau commit hoặc sau khi publish retry/DLQ thành công; persistent republish, retry header phân tầng, idempotency theo event/hash, ACTIVE/deleted/inactive handling và exhausted FAILED state; verified 2026-07-30 bằng 55 Python tests với broker integration)
- [x] Phase 5 - Spring outbox + publisher (catalog image create/delete and real ACTIVE transitions write contract-v1 events in the same DB transaction; scheduled publisher uses persistent messages, mandatory routing, correlated confirms, `FOR UPDATE SKIP LOCKED`, claim lease and bounded exponential retry; compiled and focused unit tests verified 2026-07-31)
- [x] Phase 6 - Search API (FastAPI internal multipart endpoint validates/normalizes query images, creates query embeddings, runs exact pgvector cosine search grouped by product and records usage; Spring public endpoint applies a separate IP rate limit, calls with an internal token, re-filters ACTIVE products, enriches current commerce data and supports category/gender/price filters; verified 2026-07-31 with 59 Python tests and Spring compilation/focused unit tests)
- [x] Phase 7 - Backfill/reconciliation (verified 2026-08-01: migrations V34/V35 applied; catalog limit raised to 16 MP for audited 3506-4000 px source images while normalized output remains 1024 px; transient failures release PROCESSING claims to PENDING and reconciliation includes PENDING; the worker runs reconciliation automatically every hour with a PostgreSQL advisory lock/recent-job guard and skips permanent failures; admin API `/internal/v1/admin/coverage|usage|jobs|retry-failed` uses internal token auth; Supabase coverage is 576/576 READY with 0 PENDING/PROCESSING/FAILED/MISSING; final recovery jobs completed 9/9, 22/22 and 6/6)
- [x] Phase 8 - Storefront (camera/upload entry beside header search, client validation, preview with adjustable square crop, multipart search, accessible loading/error/empty states and existing product cards with matched image/similarity; verified 2026-08-01 with storefront lint, typecheck and production build)
- [x] Phase 9 - Admin/observability (verified 2026-08-01: ADMIN-only Spring proxy keeps the internal token server-side; `/visual-search` shows active model, 576/576 coverage, embedding/outbox status, 30-day usage/cost, recent jobs and live RabbitMQ main/retry/DLQ depth; retry FAILED, backfill MISSING and targeted image/product reindex all enqueue normal outbox/RabbitMQ jobs; 85/85 Python tests including real RabbitMQ integration pass, Spring compile/focused visual-search tests pass, admin lint/typecheck/production build pass. Full Spring Testcontainers suite runs 367 tests with four failures outside visual search in seed, collection, notification and payment.)
  - [x] Admin dashboard `/visual-search` hiển thị active provider/model, embedding coverage/status, outbox status, usage/cost 30 ngày và indexing job gần đây; browser chỉ gọi Spring API có JWT, internal service token không bị đưa ra client (verified 2026-08-01 bằng 81 Python tests, Spring compile, admin lint/typecheck và Supabase runtime audit)
  - [x] Retry toàn bộ embedding FAILED từ admin qua Spring proxy và internal-token protected visual service API
  - [x] Bổ sung RabbitMQ main/retry/DLQ message counts và thao tác reindex theo image/product trực tiếp trên admin
- [x] Phase 10 - Benchmark, hardening và rollout docs (verified 2026-08-01: approved deterministic synthetic robustness set of 100 catalog-derived camera simulations, balanced 34 tops/33 bottoms/33 shoes with provenance; accessories N/A because the ACTIVE catalog has zero accessory products with images; Voyage/Supabase end-to-end Recall@1 98%, Recall@5 100%, category accuracy 100%, p95 2978.23 ms; report at `evidence/visual-search/phase10-benchmark-report.json`)
  - [x] Bộ benchmark end-to-end có manifest ground truth, kiểm tra tối thiểu 100 ảnh ngoài catalog thuộc ba nhóm hiện có ảnh là áo/quần/giày, Recall@1/5, category accuracy và p95; phụ kiện được ghi N/A do catalog ACTIVE có 0 sản phẩm phụ kiện có ảnh và tự trở thành bắt buộc khi catalog bổ sung dữ liệu (điều chỉnh được người dùng phê duyệt 2026-08-01)
  - [x] Hardening lỗi provider ở internal search thành response 503 có kiểm soát, không trả chi tiết upstream; 88 Python tests pass, 1 RabbitMQ integration test được skip khi không bật cờ
  - [x] Runbook rollout/rollback, acceptance gate, monitoring và incident checks tại `docs/visual-search-rollout.md`; `RUN_LOCAL.md` bổ sung worker/backfill dry-run đúng thứ tự
  - [x] Theo phê duyệt của người dùng, tạo 100 ảnh mô phỏng camera có seed/provenance từ catalog (không tuyên bố là real-world generalization) và chạy nghiệm thu thật: Recall@5 100%, category accuracy 100%, p95 2978.23 ms
- [x] Final plan-gap audit (verified 2026-08-01): public `GET /api/v1/visual-search/status` checks live service readiness; Spring/FastAPI local ports are aligned at `8090`; search results prioritize products with available ACTIVE variant stock; usage now records a configurable per-megapixel cost and V36 backfills historical Supabase usage; monthly budget is visible in admin and blocks new queries at 100%; admin lists model versions and supports transaction-safe activate/rollback only at >=98% READY coverage. Verified with 89 Python tests (1 optional RabbitMQ integration skipped), backend compilation, admin TypeScript and ESLint; V36 applied successfully to Supabase. Audited 2,696 historical DLQ reindex messages: all were valid events for 466 images already recovered to READY; recorded them idempotently and cleared the resolved main/DLQ backlog. Final queues and unpublished outbox count are zero.
