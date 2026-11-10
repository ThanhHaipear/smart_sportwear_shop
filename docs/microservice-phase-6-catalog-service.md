# Phase 6 - catalog-service

## Kết quả

Phase 6 đã hoàn thành implementation gate ngày 10/11/2026. Public/write cut-over đang giữ tắt
cho đến khi migration được áp dụng trên môi trường đích, count/checksum backfill, shadow read và
permission probe bằng chính role `catalog_app` đều đạt. Việc giữ cut-over không làm core hiện tại
dual-write và là điều kiện an toàn đã quy định trong kế hoạch migration.

## Ranh giới runtime

- Spring Boot 3.5/Java 21 service độc lập tại `catalog-service`, Docker image/health/readiness/metrics riêng.
- Sở hữu schema `catalog`, owner `catalog_owner`, runtime role `catalog_app`; pool mặc định 2-4.
- Product, taxonomy, promotion, inventory, wishlist và review nằm trong migration riêng.
- `user_ref`, `order_ref`, `order_item_ref` là external reference; không có FK sang `public`, Identity hoặc Sales.
- Migration role và runtime role có datasource credential tách biệt.
- V2 backfill idempotent giữ nguyên bảng legacy để rollback; không rename/drop và không dual-write.
- Product/inventory mutation ghi event v1 vào `integration_outbox` bằng database trigger trong cùng transaction.

## API và tính đúng đắn tồn kho

Contract v1: `contracts/rest/catalog-service-v1.yaml`.

- `GET /internal/v1/variants/{id}/sale-snapshot` trả snapshot giá/trạng thái/available/version.
- `GET /internal/v1/products/{id}/index-document` phục vụ Search rebuild.
- `POST /internal/v1/inventory/reservations` giữ nhiều SKU nguyên tử.
- `POST /internal/v1/inventory/reservations/{id}/confirm` xác nhận trừ kho.
- `DELETE /internal/v1/inventory/reservations/{id}` nhả giữ kho.

Reservation gộp SKU trùng, khóa row theo UUID đã sort, kiểm tra product/variant active và available
trong cùng transaction. `idempotencyKey` unique và được kiểm tra lại sau lock để xử lý replay đồng
thời. Confirm/release khóa reservation và variant, không thể trừ/nhả hai lần.

Internal API dùng constant-time service-token comparison, timeout do consumer cấu hình, structured
error envelope và `X-Correlation-Id`. Readiness dùng `/actuator/health/readiness`.

## Bằng chứng implementation

Chạy lại:

```powershell
.\scripts\verify-microservice-phase-6.ps1 -ImplementationOnly
```

Gate bao gồm build/test Spring service, concurrency 12 checkout tranh 5 sản phẩm (chỉ đúng một
reservation thành công), replay idempotency, security filter, source boundary, migration invariant,
REST contract và Docker Compose surface.

## Acceptance, deploy và rollback

1. Tạo login/secret riêng; áp dụng V1/V2 bằng migration role sau backup/restore drill.
2. Đối chiếu count/checksum từng bảng legacy và `catalog`; chạy API smoke và shadow response diff.
3. Chạy `verify_schema_permissions.py` bằng `CATALOG_PERMISSION_TEST_URL` của `catalog_app` để chứng
   minh write-own/deny-public.
4. Trong cửa sổ cut-over, dừng inventory writer ở core rồi mới bật Sales gọi reservation API.
5. Theo dõi conflict rate, latency, pool, data diff; chỉ sau đó chuyển public catalog route.

Rollback bằng route/feature flag về core release trước. Tắt writer mới trước khi bật writer cũ;
giữ schema/reservation/bảng legacy để điều tra và replay, không reverse-copy hay drop dữ liệu.

Không có `CATALOG_PERMISSION_TEST_URL` trong workspace hiện tại nên acceptance trên Supabase chưa
được tuyên bố đạt. Đây là hold vận hành duy nhất; implementation và concurrency gate đã hoàn tất.
