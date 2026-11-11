# Phase 11 - Loại bỏ backend legacy

## Trạng thái

`IN_PROGRESS / LOCAL_CUTOVER` từ ngày 11/11/2026.

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

- [ ] Hoàn thiện customer order cancel và refund-destination.
- [ ] Hoàn thiện return/refund workflow cho storefront và admin, gồm manual confirmation.
- [ ] Hoàn thiện notification SSE hoặc thay contract frontend bằng cơ chế được duyệt.
- [ ] Hoàn thiện product image upload và review API của Catalog.
- [ ] Kiểm kê toàn bộ endpoint storefront/admin theo method; mọi endpoint phải có owner mới hoặc quyết định
  loại bỏ được ghi nhận.
- [ ] Xóa route fallback `legacy_upstream` khỏi gateway và thêm regression gate fail nếu frontend endpoint rơi
  về legacy.
- [ ] Chạy E2E COD/VietQR/cancel/return/refund trên môi trường acceptance không dùng dữ liệu production chung.
- [ ] Chốt writer cũ đã dừng, chạy incremental parity lần cuối và theo dõi stability window được duyệt.
- [ ] Tạo restore point mới; chỉ sau đó mới duyệt bốn gate để chạy Phase 10 V3.

## Điều kiện hoàn thành

Phase 11 chỉ được đánh dấu `DONE` khi gateway không còn fallback legacy, endpoint inventory đạt 100%, E2E và
stability window đạt, writer cũ không còn hoạt động và restore point đã được kiểm tra. Cleanup legacy là bước
cuối có phê duyệt riêng; không tự động chạy trong test hoặc deploy.
