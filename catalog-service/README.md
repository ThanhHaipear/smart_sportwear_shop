# catalog-service

Spring Boot service sở hữu product/taxonomy/promotion/inventory/wishlist/review trong schema `catalog`.

## Runtime boundary

- Runtime dùng `catalog_app`, `search_path=catalog`, pool mặc định 2-4 connection.
- Flyway dùng migration role riêng; không dùng runtime role để tạo schema/role.
- `user_ref`, `order_ref`, `order_item_ref` là external reference, không có FK xuyên service.
- Mọi internal endpoint yêu cầu `X-Internal-Service-Token`; correlation ID được truyền/khởi tạo và trả về.
- Trigger ghi `catalog.product.changed.v1` và `catalog.inventory.changed.v1` vào transactional outbox trong cùng transaction.

## API v1

- `GET /internal/v1/variants/{id}/sale-snapshot`
- `GET /internal/v1/products/{id}/index-document`
- `POST /internal/v1/inventory/reservations`
- `POST /internal/v1/inventory/reservations/{id}/confirm`
- `DELETE /internal/v1/inventory/reservations/{id}`

Reservation khóa các SKU theo UUID đã sort trong một transaction. `idempotencyKey` ngăn reserve lặp;
confirm/release cũng idempotent. Không bật write authority ở core và service mới cùng lúc.

## Deploy / cut-over

1. Backup/restore drill, tạo login secret riêng cho `catalog_app` và migration role.
2. Chạy V1/V2; đối chiếu count/checksum cho từng bảng, sau đó chạy permission probe.
3. Shadow-read sale snapshot/index document; chạy concurrency và API smoke.
4. Chuyển Sales sang reservation API bằng một cửa sổ cut-over; chỉ một phía được ghi inventory.
5. Bật route catalog public sau khi error/latency/data-diff gate đạt.

Rollback: chuyển route về core release cũ và tắt writer mới. Giữ schema, reservation và legacy tables;
không reverse-copy hoặc drop trong cửa sổ rollback.
