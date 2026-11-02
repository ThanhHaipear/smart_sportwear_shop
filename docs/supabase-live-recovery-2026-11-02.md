# Supabase live recovery — 02/11/2026

## Trạng thái

Kết nối live đang `BLOCKED_CONFIGURATION`, không phải lỗi migration hay lỗi
password đã được xác minh:

- Session Pooler trả `tenant/user ... not found` cho project ref đang có trong
  `backend/.env` và `visual-search-service/.env`.
- hostname REST của cùng project ref không còn phân giải DNS.
- lịch sử Git không chứa project ref Supabase thay thế.

Không chạy migration, backfill hoặc thay `.env` bằng hostname đoán khi chưa xác
nhận đúng project integration/demo.

## Cách khôi phục

1. Mở Supabase Dashboard và xác nhận project integration/demo còn tồn tại.
2. Nếu project còn tồn tại, lấy lại Session Pooler host, port, database và user
   từ Connect; cập nhật đồng bộ `backend/.env` và
   `visual-search-service/.env`.
3. Nếu project đã bị xóa, tạo project integration/demo thay thế, review restore
   plan trước khi chạy Flyway V1–V8 hoặc restore backup đã kiểm chứng.
4. Chạy audit read-only trước mọi thao tác ghi:

```powershell
visual-search-service\.venv\Scripts\python.exe scripts\audit_supabase_readonly.py `
  --output evidence\image-search-v2\supabase-live-audit-2026-11-02.json
```

Audit phải xác nhận Flyway V8, đúng một model ảnh `ACTIVE`, coverage embedding,
coverage `color_signature` và backlog outbox. Chỉ sau đó mới chạy benchmark/tune
DEV v2 trên service và database live.
