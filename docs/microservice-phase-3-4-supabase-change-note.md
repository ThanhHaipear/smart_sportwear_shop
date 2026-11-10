# Change note - Supabase Phase 3 và Phase 4

Ngày thực hiện: 10/11/2026.

## Phạm vi được chấp thuận

Người dùng chấp thuận tạo role/schema Phase 3-4 trên Supabase đang cấu hình và
yêu cầu ghi note để có thể sửa nếu phát sinh vấn đề. Visual Search phải tiếp tục
tắt cho đến khi TEST v3 độc lập đạt gate; không dùng lại TEST v2 để retune.
Sự chấp thuận này được ghi nhận như một exception cho phép bắt đầu triển khai
Phase 5 trong khi Visual Search vẫn ở trạng thái hold, không phải miễn TEST v3.

## Thay đổi dự kiến

- Tạo owner role `search_owner`, `chatbot_owner` (NOLOGIN).
- Tạo runtime role `search_app`, `chatbot_app` với secret riêng.
- Tạo schema dẫn xuất `ai_search` và schema hội thoại `chatbot`.
- Thu hồi quyền `CREATE` trên `public` của runtime roles.
- Lưu DSN runtime trong `.env` đã git-ignore; không ghi secret vào evidence/log.
- Đồng bộ internal token cục bộ cho Backend, Search và Chatbot; không commit token.
- Giữ `SEARCH_ENABLED=false` và `VISUAL_SEARCH_ENABLED=false` trong lần migration.

Không rename/drop `ai_retrieval`, không di chuyển bảng thương mại, không đổi
write authority của Catalog/Sales và không bật public traffic.

## Kiểm tra sau thay đổi

- Runtime role đăng nhập được và có `USAGE` trên schema sở hữu.
- Runtime role không có `CREATE` trên `public`.
- Phase 3/4 implementation gates vẫn đạt.
- Evidence không chứa password/token.

## Nhật ký thực hiện

- Lần 1: transaction rollback hoàn toàn do Supabase từ chối câu
  `ALTER ROLE ... NOSUPERUSER` với credential quản trị bị giới hạn. Audit sau lỗi
  xác nhận không có schema/role nào còn lại. Script được sửa để dùng thuộc tính
  an toàn mặc định của `CREATE ROLE` và kiểm tra `rolsuper/rolcreatedb/rolcreaterole`
  sau khi runtime role đăng nhập.
- Lần 2: role/schema được commit, nhưng verification login dừng trước khi ghi
  `.env` vì Supabase pooler yêu cầu username dạng `role.project-ref`. Không có
  secret nào bị log/ghi file; lần chạy kế tiếp xoay password và tạo DSN đúng
  tenant suffix. Migration schema là idempotent.
- Một lần xoay credential tiếp theo gặp password-cache propagation của Supabase
  pooler. Script dừng trước khi ghi `.env`; đã bổ sung retry exponential có
  giới hạn cho riêng lỗi `InvalidPassword`.
- Lần cuối: migration idempotent thành công; runtime roles không có superuser,
  createdb hoặc createrole, có `USAGE` schema riêng và không có `CREATE` trên
  `public`. Hai write/deny probes chạy trong transaction và rollback thành công.

## Xử lý khi có vấn đề

1. Giữ Search/Visual Search feature flags ở `false` và route về release cũ.
2. Thu hồi login tạm thời: `ALTER ROLE search_app NOLOGIN;` và/or
   `ALTER ROLE chatbot_app NOLOGIN;`.
3. Không drop schema ngay; giữ để điều tra và đối chiếu.
4. Nếu buộc phải dọn, backup schema trước rồi chỉ drop `ai_search`/`chatbot`
   sau khi xác nhận không có traffic và dữ liệu Chatbot đã export.
5. Khôi phục login/grant bằng migration sửa đổi có review, không sửa trực tiếp
   bằng Table Editor.

## Kết quả cuối

- Migration Supabase hoàn tất lúc `2026-11-10T09:36:14Z`.
- Evidence: `evidence/microservice-phase-3-4-supabase-2026-11-10.json`.
- Phase 3 conditional gate: `11 passed`, trạng thái `DONE_WITH_VISUAL_HOLD`.
- Backend Search/Catalog contracts gần nhất: `12 passed` sau clean compile.
- Phase 4 gate: `249 passed`; `chatbot_app` permission probe đạt.
- `search_app` permission probe đạt.
- Không secret nào nằm trong evidence hoặc Git; các DSN/token chỉ nằm trong
  `.env` đã git-ignore.
- Không bật Search/Visual Search public traffic và không backfill image index.
