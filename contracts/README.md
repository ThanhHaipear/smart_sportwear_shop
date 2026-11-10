# Integration event contracts

Các file trong thư mục này là contract dùng chung giữa producer và consumer. Contract đã phát hành là bất biến; thay đổi không tương thích phải tạo version/file mới.

## Quy tắc v1

- Mọi event có `eventId`, `eventType`, `eventVersion`, `occurredAt` và `traceId` theo `event-envelope-v1.schema.json`.
- `eventId` là khóa idempotency của consumer.
- `eventVersion` version hóa schema, không phải version của aggregate.
- `sourceVersion` (nếu contract domain yêu cầu) dùng để bỏ qua event cũ hoặc out-of-order.
- Chỉ thêm field optional trong cùng version. Xóa/đổi nghĩa field, đổi type hoặc thêm field required phải phát hành version mới.
- Payload không chứa secret, token, dữ liệu thanh toán thô hoặc binary.
- Producer ghi event bằng transactional outbox; consumer chỉ ACK sau khi hoàn tất hoặc chuyển an toàn sang retry/DLQ.

## Contract hiện hành

| Contract | Producer | Consumer | Routing |
|---|---|---|---|
| `catalog-event-v1.schema.json` | Spring Boot Catalog | Visual Search, Product Search | topic exchange `catalog.events` |
