# Phase 5 - recommendation-service

## Kết quả

Phase 5 đã hoàn thành implementation và Supabase acceptance gate. Public cut-over
mặc định giữ tắt (`RECOMMENDATION_ENABLED=false`) cho đến khi TEST evaluation độc
lập được phê duyệt.

## Ranh giới runtime

- Runtime độc lập ở `recommendation-service`, API/worker build bằng Docker riêng.
- Sở hữu schema `recommendation`, role `recommendation_owner` và
  `recommendation_app`; pool runtime chỉ dùng `search_path=recommendation,public`.
- Catalog snapshot và order basket là read model dẫn xuất nhận qua event. Schema
  không có foreign key tới bảng thương mại và app role không có quyền trên `public`.
- Interaction, association rule, model registry, request/item/log, processed event
  và evaluation run nằm trong schema riêng.
- Toàn bộ entity/repository/service `recommendation` và `interaction` đã rời Spring
  core; migration legacy trong `public` được giữ để rollback và backfill idempotent.

## API và event

- Product: `GET /api/v1/recommendations/products/{id}` và alias tương thích
  `GET /api/v1/products/{id}/frequently-bought-together`.
- Cart: `POST /api/v1/recommendations/cart` với danh sách product ID snapshot.
- Interaction: `POST /api/v1/recommendations/interactions`.
- Internal event/model/evaluation yêu cầu `X-Internal-Service-Token`.
- Contract v1: catalog product, sales order created, interaction recorded.
- Consumer dùng `eventId` để idempotency; retry 30s/5m/1h rồi DLQ.

## Model và evaluation

Association-rule job chỉ học từ order basket đã nhận qua event. Ranking theo
confidence/lift/support và loại sản phẩm trong source; sản phẩm inactive/hết hàng
không được trả về. Khi thiếu rule, feature flag cho phép fallback popularity hoặc
empty. Endpoint evaluation tính Precision@K và Recall@K từ dataset đóng băng;
không dùng TEST để chỉnh threshold/model.

Implementation gate ngày 10/11/2026:

- `recommendation-service`: 11 tests đạt (domain, API, contract, idempotency,
  evaluation, fallback và boundary).
- Backend compile đạt; architecture gate xác nhận core không còn persistence
  recommendation/interaction.
- RabbitMQ definitions và Docker Compose hợp lệ.

Supabase acceptance gate ngày 10/11/2026:

- Tạo schema `recommendation`, owner role `recommendation_owner` và runtime role
  `recommendation_app` với secret riêng.
- `recommendation_app` không có superuser/createdb/createrole, có `USAGE` trên
  schema sở hữu và không có `CREATE` trên `public`.
- Write-own/deny-public probe đạt khi chạy trực tiếp bằng runtime DSN.
- Backfill idempotent hoàn tất với count interaction/rule/log đều bằng 0 vì nguồn
  legacy không có bản ghi; không drop hoặc đổi tên bảng legacy.
- Evidence: `evidence/microservice-phase-5-supabase-2026-11-10.json`.
- Runtime và public traffic tiếp tục tắt; secret không được in hoặc commit.

Chạy lại:

```powershell
.\scripts\verify-microservice-phase-5.ps1 -ImplementationOnly
```

Acceptance gate dùng DSN `RECOMMENDATION_PERMISSION_TEST_URL` của chính role
`recommendation_app` để chứng minh write-own/deny-public.

## Deploy, backfill và rollback

1. Tạo login role/secret, áp dụng V1 bằng migration role.
2. Áp dụng V2 để copy idempotent interaction/rule/log legacy; đối chiếu count.
3. Chạy permission probe, API smoke, event duplicate/replay và model job.
4. Chạy DEV, khóa model/config, rồi chạy TEST độc lập; lưu Precision@K/Recall@K.
5. Chỉ chuyển route khi gate được duyệt.

Rollback bằng cách tắt flag/route và dùng popularity/empty fallback. Giữ queue,
schema mới và bảng legacy; không drop dữ liệu trong cửa sổ rollback.
