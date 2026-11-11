# Phase 11 - Loại bỏ backend legacy

## Trạng thái

`IMPLEMENTED / ACCEPTANCE_HELD` từ ngày 11/11/2026.

Phần code, contract, gateway allowlist và regression gate cục bộ đã hoàn tất. Phase chưa được ghi `DONE` vì
E2E acceptance, stability window, xác nhận writer cũ dừng và restore point là bằng chứng môi trường bắt buộc,
không thể thay thế bằng test cục bộ.

Phase 10 đã hoàn tất phần phân vùng dữ liệu, backfill/parity, role isolation và local traffic cut-over mở rộng.
Phase 11 chịu trách nhiệm đóng phần API còn thiếu để gateway không còn phụ thuộc `legacy_upstream`, sau đó
chạy E2E và stability window trước khi cho phép Phase 10 chạy cleanup V3.

## Baseline đầu Phase 11

- 44 bảng nghiệp vụ đã được map sang sáu schema đích; count/PK/shared-data checksum không có mismatch.
- Sáu runtime role không có cross-schema write grant; không còn FK xuyên service.
- Sáu microservice chạy trong Docker; Identity, Catalog, Sales, Chatbot và Recommendation đã nhận traffic qua
  gateway local.
- Public Catalog, cart, checkout preview, order query, payment, notification và các read API quản trị chính đã
  có owner mới.
- JWT được gateway xác minh bằng HS256; header identity do client tự gửi bị loại bỏ; route admin bắt buộc role
  `ADMIN`.
- Legacy relation trong `public` và schema `ai_retrieval` vẫn được giữ nguyên để rollback; V3 chưa chạy.

## Backlog bắt buộc

- [x] Hoàn thiện customer order cancel và refund-destination.
- [x] Hoàn thiện return/refund workflow cho storefront và admin, gồm manual confirmation.
- [x] Hoàn thiện notification SSE; EventSource token được gateway xác minh và loại khỏi query chuyển tiếp.
- [x] Hoàn thiện product image upload và review API của Catalog; review kiểm tra purchase qua Sales internal API.
- [x] Kiểm kê toàn bộ endpoint storefront/admin theo method; mọi endpoint có owner mới hoặc quyết định
  loại bỏ được ghi nhận.
- [x] Xóa route fallback `legacy_upstream` khỏi gateway và thêm regression gate fail nếu frontend endpoint rơi
  về legacy.
- [ ] Chạy E2E COD/VietQR/cancel/return/refund trên môi trường acceptance không dùng dữ liệu production chung.
- [ ] Chốt writer cũ đã dừng, chạy incremental parity lần cuối và theo dõi stability window được duyệt.
- [ ] Tạo restore point mới; chỉ sau đó mới duyệt bốn gate để chạy Phase 10 V3.

## Bằng chứng triển khai cục bộ

- [Endpoint inventory](microservice-phase-11-endpoint-inventory.md) ghi method, path, owner và hai contract SSE
  tổng quát đã chuyển sang polling 30 giây.
- `scripts/verify-microservice-phase-11.ps1` fail nếu gateway/Compose còn fallback legacy hoặc frontend còn gọi
  `/api/v1/realtime/stream` và `/api/v1/inventory/stream`.
- Gateway regression: `12 passed`; có test owner cho cancel/refund/return/manual confirmation/review/upload và
  test cấm toàn bộ symbol legacy/cut-over.
- `sales-service`: Maven test đạt sau khi bổ sung workflow transaction và review eligibility internal API.
- `catalog-service`: Maven test đạt sau khi bổ sung review API, migration V4 và image storage riêng.
- Storefront `npm run typecheck` đạt; Admin `npm run lint` đạt.

## Gate acceptance còn mở

1. Chạy E2E COD/VietQR/cancel/return/refund với database acceptance riêng và lưu ID giao dịch đã redacted.
2. Xác nhận deployment backend cũ không còn writer/job/consumer hoạt động; chạy incremental parity cuối.
3. Theo dõi stability window theo thời lượng được người vận hành duyệt.
4. Tạo và restore thử restore point mới, sau đó mới phê duyệt chạy Phase 10 V3.

## Điều kiện hoàn thành

Phase 11 chỉ được đánh dấu `DONE` khi gateway không còn fallback legacy, endpoint inventory đạt 100%, E2E và
stability window đạt, writer cũ không còn hoạt động và restore point đã được kiểm tra. Cleanup legacy là bước
cuối có phê duyệt riêng; không tự động chạy trong test hoặc deploy.
