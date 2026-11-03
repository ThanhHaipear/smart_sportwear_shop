# Supabase live recovery — 02–03/11/2026

## Trạng thái

Kết nối live đã `RECOVERED` ngày 03/11/2026. Lỗi `tenant/user not found` ngày
02/11 xuất hiện khi project Supabase bị tạm dừng; project ref và Session Pooler
hiện tại vẫn hợp lệ.

Audit sau khi project hoạt động lại xác nhận:

- Flyway ở V8 và model SigLIP2 768 chiều là model ảnh ACTIVE duy nhất.
- 36/36 sản phẩm và 81/81 ảnh đang ACTIVE.
- Embedding đạt 81/81 READY, không thiếu và không có item `not_ready`.
- `color_signature` đã được backfill từ 0/81 lên 81/81.
- Outbox đã publish; main queue, retry queues và DLQ visual search đều về 0.

Nguyên nhân ban đầu đã được loại trừ:

- Không cần đổi project ref `tzczrxihhveofjlnkvul` hoặc tạo project mới.
- Không có migration/restore nào được chạy trong quá trình recovery.
- `visual-search-service/.env` đã được đồng bộ Cloudinary cloud name với catalog
  để worker tải được 21 ảnh Cloudinary khi backfill màu.

Không chạy migration, backfill hoặc thay `.env` bằng hostname đoán khi chưa xác
nhận đúng project integration/demo.

## Quy trình khôi phục đã thực hiện

1. Mở Supabase Dashboard và xác nhận project integration/demo còn tồn tại.
2. Nếu project còn tồn tại, lấy lại Session Pooler host, port, database và user
   từ Connect; cập nhật đồng bộ `backend/.env` và
   `visual-search-service/.env`.
3. Nếu project đã bị xóa, tạo project integration/demo thay thế, review restore
   plan trước khi chạy Flyway V1–V8 hoặc restore backup đã kiểm chứng.
4. Chạy audit read-only trước mọi thao tác ghi:

```powershell
visual-search-service\.venv\Scripts\python.exe scripts\audit_supabase_readonly.py `
  --output evidence\image-search-v2\supabase-live-audit-2026-11-03.json
```

Audit đã xác nhận Flyway V8, đúng một model ảnh `ACTIVE`, coverage embedding,
coverage `color_signature` và backlog outbox. Benchmark/tune DEV v2 chỉ được chạy
sau khi các kiểm tra này đạt.
