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
