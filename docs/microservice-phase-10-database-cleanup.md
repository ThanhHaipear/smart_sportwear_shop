# Phase 10 - Dọn shared database

## Trạng thái

Phase 10 đã hoàn thành phần implementation ngày 11/11/2026, trạng thái
`IMPLEMENTED / FINALIZATION_HELD`.

Migration chốt GRANT, cleanup FK, manifest ownership, audit read-only, destructive-finalization gate và
rollback policy đã có đầy đủ. Việc áp dụng V3 lên Supabase **chưa được thực hiện** vì bằng chứng Phase 6-8
và cấu hình mặc định hiện vẫn ghi public route/write authority ở trạng thái `CUTOVER_HELD`. Đây là gate bắt
buộc của chính kế hoạch migration, không phải lỗi implementation.

## Ownership cuối cùng

| Service | Schema | Owner | Runtime role | Search path |
|---|---|---|---|---|
| Identity | `identity` | `identity_owner` | `identity_app` | `identity,pg_catalog` |
| Catalog | `catalog` | `catalog_owner` | `catalog_app` | `catalog,pg_catalog` |
| Sales | `sales` | `sales_owner` | `sales_app` | `sales,pg_catalog` |
| Search | `ai_search` | `search_owner` | `search_app` | `ai_search,extensions,pg_catalog` |
| Chatbot | `chatbot` | `chatbot_owner` | `chatbot_app` | `chatbot,pg_catalog` |
| Recommendation | `recommendation` | `recommendation_owner` | `recommendation_app` | `recommendation,pg_catalog` |

Machine-readable source of truth: `infra/database/phase10/ownership.json`.

## Sản phẩm bàn giao

- `V1__finalize_service_role_grants.sql`: thu hồi mọi table/sequence/function grant chéo rồi chỉ cấp lại
  quyền cho schema sở hữu; thu hồi `PUBLIC CREATE`; chốt `search_path` tường minh.
- `V2__remove_cross_service_foreign_keys.sql`: bỏ FK giữa sáu service schema; ID ngoài boundary tiếp tục là
  external reference.
- `V3__retire_legacy_structures.sql`: xóa schema `ai_retrieval` và các relation legacy `public` theo allowlist,
  dùng `RESTRICT`; không chạy tự động và fail-closed nếu thiếu một trong bốn approval gate.
- `audit-phase-10-database.py`: audit read-only schema owner, role elevation, `PUBLIC CREATE`, write grant chéo,
  search path, FK chéo và cấu trúc legacy; có thể xuất JSON evidence mà không chứa credential.
- `verify-microservice-phase-10.ps1`: implementation gate và tùy chọn live audit.

## Gate implementation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-10.ps1
```

Live permission audit trước V3:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-10.ps1 -LiveAudit
```

Acceptance cuối sau V3 bắt buộc thêm `-RequireLegacyRetired`. Chỉ khi lệnh này đạt mới được đổi trạng thái
Phase 10 thành `DONE`.

Preflight live ngày 11/11/2026 đạt toàn bộ isolation gate: 6/6 schema đúng owner, 0 cross-schema write grant,
0 FK xuyên service, mọi runtime role đều bị từ chối `CREATE` trên `public`. Database vẫn còn 45 relation
legacy và schema `ai_retrieval`, đúng với trạng thái chưa chạy V3. Evidence:
`evidence/microservice-phase-10-preflight-2026-11-11.json`.

## Trình tự finalization bắt buộc

1. Có approval ID cho từng route/write cut-over và chứng minh writer legacy đã dừng; event/SePay writer mới
   hoạt động đúng.
2. Đối chiếu count/checksum và shadow/E2E của Identity, Catalog, Sales, Search, Chatbot, Recommendation.
3. Hoàn tất khoảng ổn định đã duyệt; không còn rollback traffic về core trong khoảng này.
4. Tạo backup mới và diễn tập restore thành công; ghi restore-point ID vào change record.
5. Chạy audit; xử lý toàn bộ vi phạm. Áp dụng V1/V2 trong transaction được review rồi chạy sáu permission probe.
6. Trong maintenance window, set bốn transaction-local gate (cut-over, restore, data parity, stability) và chạy V3 thủ công. Bất kỳ dependency lạ nào
   cũng làm transaction fail vì script không dùng `CASCADE`.
7. Chạy audit với `--require-legacy-retired`, lưu JSON evidence, theo dõi error/DB permission/pool tối thiểu
   một cửa sổ vận hành.

## Rollback

- Trước V3: rollback route/write authority theo runbook của service; grant tạm chỉ cấp đúng relation/quyền cần
  thiết, có incident ID và hạn tối đa 24 giờ.
- Sau V3: dữ liệu legacy chỉ phục hồi từ restore point đã xác minh. Không có migration tự tạo lại grant rộng
  hoặc shared-schema coupling.
- Sau mọi grant tạm phải revoke trước hạn và audit lại. Hướng dẫn chi tiết nằm tại
  `infra/database/phase10/README.md`.

## Điều kiện còn thiếu để đóng Phase 10

- Bằng chứng route/write cut-over thực tế của các phase đang `CUTOVER_HELD`.
- Approval và timestamp kết thúc stability window.
- Restore-point mới ngay trước cleanup.
- Live evidence sau V1/V2/V3, gồm audit `passed=true` và không còn relation/schema legacy.

## Cut-over attempt ngày 11/11/2026

Đã thực hiện các bước an toàn có thể hoàn thành mà không làm gián đoạn storefront:

- Xác nhận không có container ứng dụng hoặc database session khác đang hoạt động; local legacy writer đã dừng.
- Bổ sung ownership/backfill còn thiếu cho Catalog taxonomy/dataset/inventory ledger, Sales operational tables
  và Search read model.
- Transactional dry-run và live backfill đều đạt 22/22 cặp nguồn-đích.
- Audit sau backfill: 44 bảng nghiệp vụ được map, 0 bảng chưa có đích, 0 count/PK/shared-data checksum mismatch.
- Legacy outbox có 489 sự kiện và toàn bộ ở trạng thái `PUBLISHED`.
- `ai_search` hiện có 36 document, 178 variant facet và 81 image embedding. Text embedding 1024 chiều
  của legacy không được copy sang target 1536 chiều; text search dùng keyword cho tới khi re-embed bằng model mới.

Evidence:

- `evidence/microservice-phase-10-backfill-2026-11-11.json`
- `evidence/microservice-phase-10-data-inventory-2026-11-11.json`

## Local traffic cut-over ngày 11/11/2026

Toàn bộ runtime đã được dựng bằng Docker Compose và gateway chạy tại `http://127.0.0.1:8085`. Cổng `8085`
được dùng vì cổng `8080` trên máy đang do một tiến trình Apache ngoài stack chiếm giữ. Các cut-over flag của
Identity, Catalog, Sales, Chatbot và Recommendation đều đang bật; Search phục vụ nội bộ và Visual Search vẫn
giữ tắt theo exception đã được phê duyệt.

API đã chuyển sang microservice trong cửa sổ local:

- Identity: auth, profile/địa chỉ với cả contract `/api/v1/me/**` của frontend và alias `/api/v1/users/me/**`,
  cùng API quản trị user.
- Catalog: product/category/brand/promotion/collection/banner/page read, wishlist và phần API quản trị
  product/category/brand/inventory.
- Sales: guest/user cart, checkout preview, create/query order, payment/VietQR, notification, public settings,
  SePay và phần API quản trị order/report/return/settings/audit.
- Chatbot và Recommendation: các route đã được allowlist từ Phase 4-5.

Smoke qua gateway đạt: public Catalog, guest cart và public settings trả 200; route được bảo vệ trả 401 khi
thiếu JWT; customer JWT thật theo contract gateway trả 200 cho profile, address, order query, notification và
wishlist; admin JWT trả 200 cho user, product, inventory, order, report, settings và audit-log read. Gateway
test đạt 9/9; Identity 6/6, Catalog 4/4 và Sales 10/10 test đều đạt. Sau khi khai báo bổ sung exchange
`identity.events`, log 10 phút gần nhất của Identity/Catalog/Sales/Gateway không còn ERROR/Exception.

Live Supabase audit cùng ngày tiếp tục đạt: 6/6 runtime role chỉ ghi schema sở hữu, `PUBLIC CREATE=false`,
0 cross-schema write grant, 0 FK xuyên service và `passed=true`. Evidence:
`evidence/microservice-phase-10-local-cutover-2026-11-11.json`.

Phase 10 implementation vì vậy đã đủ điều kiện bàn giao sang Phase 11, nhưng destructive finalization vẫn
**fail-closed**. Gateway còn fallback `legacy_upstream` cho surface chưa được thay thế hoàn toàn; các workflow
cancel/return/refund, notification SSE, review và product-image upload vẫn cần hoàn thiện và E2E. V3 chỉ được
mở khóa sau khi Phase 11 bỏ fallback, kết thúc stability window được duyệt và có restore point mới.
