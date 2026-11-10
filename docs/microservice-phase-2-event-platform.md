# Microservice migration - Phase 2 event platform

Ngày cập nhật: 10/11/2026

Trạng thái: `DONE`

## Mục tiêu và phạm vi

Phase 2 chuẩn hóa nền tảng event đang hoạt động giữa Catalog, Visual Search và Product Search trước khi tách process tiếp theo. Phase này hoàn thiện platform dùng chung; các event Sales/Identity/Recommendation cụ thể sẽ phát hành contract riêng khi bounded context tương ứng được tách.

Phase này không tạo migration mới, không thay đổi schema và không ghi dữ liệu Supabase production.

## Contract v1

- `contracts/event-envelope-v1.schema.json` định nghĩa metadata bắt buộc: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `traceId`.
- `contracts/catalog-event-v1.schema.json` là contract domain hiện hành và đóng `additionalProperties`.
- Contract Catalog v1 yêu cầu `productId`, `sourceVersion`; image event yêu cầu `imageId` UUID khác null.
- Producer và hai consumer đều có contract test đối chiếu với cùng file schema.
- Thay đổi breaking phải tạo version mới; quy tắc compatibility được ghi tại `contracts/README.md`.

## Transactional outbox relay

Spring Boot ghi catalog mutation và `integration_outbox` trong cùng transaction. Relay:

1. Claim batch bằng `FOR UPDATE SKIP LOCKED`.
2. Đặt `PUBLISHING` và tăng `attempts`.
3. Gửi persistent message tới topic exchange `catalog.events`.
4. Chỉ đánh dấu `PUBLISHED` sau publisher confirm ACK và không bị trả lại vì unroutable.
5. Lỗi tạm thời dùng bounded exponential backoff.
6. Khi đạt `max-attempts`, bản ghi giữ `FAILED`, có `last_error` bắt đầu bằng `EXHAUSTED` và không được claim tự động nữa.

Message có `messageId = eventId`, AMQP `type = eventType` và header `eventVersion = 1`.

## Consumer idempotency

| Consumer | Cơ chế |
|---|---|
| Visual Search | Kiểm tra `processed_events`; cập nhật embedding/read model và marker hoàn tất trong transaction repository; duplicate event không gọi provider lần hai |
| Product Search | Insert `(consumer_name, event_id)` và materialize read model trong cùng transaction; conflict trả `DUPLICATE` |

`eventId` là khóa idempotency. `sourceVersion` cho phép consumer nhận biết version aggregate và tránh ghi đè bằng snapshot cũ.

## Retry và DLQ

Hai consumer có topology giống nhau:

```text
catalog.events
  -> <consumer>.indexing
      -> retry.30s -> main
      -> retry.5m  -> main
      -> retry.1h  -> main
      -> <consumer>.indexing.dlq
```

- Lỗi transient đi tuần tự qua ba TTL retry queue.
- Contract không hợp lệ hoặc lỗi permanent đi thẳng DLQ.
- Consumer publish an toàn sang retry/DLQ trước khi ACK message gốc.
- Queue, exchange và message đều durable/persistent.
- `rabbitmq/definitions.json` được kiểm tra tự động cho binding, TTL và dead-letter routing của cả Visual Search và Product Search.

## Quan sát vận hành

- `GET /actuator/integrationevents` (chỉ role `ADMIN`) trả số lượng outbox `pending`, `publishing`, `retryable_failed`, `exhausted`, `published`.
- Visual Search admin operations tiếp tục hiển thị độ sâu main/retry/DLQ.
- RabbitMQ Management UI dùng để xem queue depth và thao tác DLQ có kiểm soát.
- Correlation ID từ HTTP được giữ trong `traceId` của event để đối chiếu log producer/consumer.

## Feature flag và rollback

| Thành phần | Flag tắt |
|---|---|
| Spring outbox relay | `CATALOG_EVENTS_ENABLED=false` |
| Visual Search HTTP/worker | `VISUAL_SEARCH_ENABLED=false` |
| Product Search indexing worker | `PRODUCT_SEARCH_INDEXING_ENABLED=false` |

Tắt consumer không làm mất event vì queue durable. Tắt publisher không làm mất mutation vì event còn trong outbox. Sau khi khắc phục, bật lại từng thành phần và theo dõi endpoint/queue depth.

## Runbook outbox và DLQ

### Outbox exhausted

1. Kiểm tra `/actuator/integrationevents` và log theo `eventId`/`traceId`.
2. Xác minh RabbitMQ, exchange/binding và consumer trước khi retry.
3. Chỉ sau khi nguyên nhân đã hết, operator có quyền DB mới đặt lại đúng event cần xử lý: `attempts = 0`, `available_at = now()`, `last_error = null`, giữ `status = 'FAILED'`.
4. Không sửa payload, `eventId`, `aggregate_version` hoặc tạo event mới bằng tay.

### Consumer DLQ

1. Xem header error/retry count và validate payload với contract tương ứng.
2. Event permanent/contract sai phải được sửa ở producer hoặc bằng migration có review; không requeue lặp vô hạn.
3. Event transient chỉ được chuyển từ DLQ về main queue theo batch nhỏ sau khi dependency ổn định.
4. Theo dõi main/retry/DLQ về 0 và xác nhận `processed_events`/read model sau replay.

## Bằng chứng kiểm thử

| Gate | Kết quả |
|---|---|
| Backend contract/outbox/operations tests | 7 test, 0 failure/error |
| Visual Search contract, duplicate, retry/DLQ, topology | 23 test, 0 failure/error |
| Product Search contract, atomic duplicate, retry/DLQ | 7 test, 0 failure/error |
| RabbitMQ thật: publish, manual ACK và retry route | 1 test, 0 failure/error |
| Backend unit baseline | 89 test, 0 failure/error |
| Backend current-runtime integration | 369 test, 0 failure/error |
| Visual Search full suite | 129 passed, 2 skipped |
| Chatbot và Product Search full suite | 243 passed |
| Storefront/Admin lint | Đạt |

## Exit gate Phase 2

- [x] Có event envelope và Catalog JSON Schema v1 dùng chung.
- [x] Producer/consumer contract test đạt.
- [x] Outbox dùng transaction, publisher confirm, bounded retry và trạng thái exhausted rõ ràng.
- [x] Hai consumer xử lý duplicate idempotent.
- [x] Retry queue, DLQ và topology test đạt.
- [x] Có endpoint quan sát, feature flag và runbook rollback/replay.
- [x] Không thay đổi Supabase production.

Phase 2 hoàn tất. Công việc tiếp theo là Phase 3: hoàn thiện và tách `search-service` thành process/deployment có ownership rõ ràng.
