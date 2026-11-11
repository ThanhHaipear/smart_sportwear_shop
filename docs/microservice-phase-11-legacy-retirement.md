# Phase 11 - Loại bỏ backend legacy

## Trạng thái

`DONE / LOCAL_ACCEPTANCE` từ ngày 11/11/2026. Destructive cleanup của Phase 10 có approval riêng.

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
- [x] Chạy E2E COD/VietQR/cancel/return/refund trên PostgreSQL acceptance cục bộ tách biệt; toàn bộ luồng đạt qua gateway.
- [x] Chốt writer cũ đã dừng, chạy incremental parity lần cuối và theo dõi stability window sau redeploy.
- [x] Tạo restore point mới và restore thử thành công. Phase 10 V3 vẫn cần xác nhận destructive riêng.

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

E2E acceptance đã đạt ngày 11/11/2026 trên database PostgreSQL cục bộ riêng. Evidence:
`evidence/microservice-phase-11-acceptance-e2e-2026-11-11.json`. E2E phát hiện và giúp sửa hai lỗi chỉ xuất hiện
khi chạy PostgreSQL/service thật: lệch field contract reservation Sales→Catalog và binding `Instant` vào
`timestamptz`.

Writer/session audit không phát hiện legacy writer; runtime local không có backend monolith và cổng 8080 thuộc
Apache ngoài stack. Evidence: `evidence/microservice-phase-11-writer-audit-2026-11-11.json`.

Runtime Gateway/Catalog/Sales đã được build lại từ source hiện tại và redeploy. Cửa sổ theo dõi sau deploy đạt
20/20 lần kiểm tra, ba endpoint health/public luôn trả 200, không có lỗi runtime thật; parity sau cửa sổ vẫn
`readyForLegacyRetirement=true`. Evidence:
`evidence/microservice-phase-11-stability-window-2026-11-11.json`.

Phase 11 vì vậy đã đạt local acceptance. Phase 10 V3 là thao tác xóa dữ liệu không thể đảo ngược và chỉ còn
chờ xác nhận destructive rõ ràng của chủ dự án.

## Điều kiện hoàn thành

Phase 11 chỉ được đánh dấu `DONE` khi gateway không còn fallback legacy, endpoint inventory đạt 100%, E2E và
stability window đạt, writer cũ không còn hoạt động và restore point đã được kiểm tra. Cleanup legacy là bước
cuối có phê duyệt riêng; không tự động chạy trong test hoặc deploy.
