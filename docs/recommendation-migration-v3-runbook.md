# Runbook: áp dụng migration V3 của Recommendation

> Thuộc review **R3** của PR #1. Migration: `recommendation-service/migrations/V3__extend_evaluation_runs.sql`.
> **Quy tắc O03:** mỗi lần triển khai chỉ **một người được giao** chạy migration đã duyệt lên Supabase; không sửa schema bằng Table Editor; lưu bằng chứng.

| Vai trò | Người |
|---|---|
| Người chạy V3 trên Supabase | _(nhóm điền khi phân công)_ |
| Người xác nhận schema và cho phép triển khai | _(nhóm điền; khác người chạy)_ |

## 1. V3 làm gì

- Thêm 7 cột **cho phép NULL** vào `recommendation.evaluation_runs`: `task`, `segment`, `case_count`, `hit_rate_at_k`, `ndcg_at_k`, `coverage_at_k`, `report_sha256`.
- Thêm ràng buộc `chk_evaluation_runs_v3_ranges`: các metric nằm trong [0, 1], `case_count ≥ 0`, `segment` thuộc `all`/`warm`/`cold`.
- Chỉ thêm, không sửa hay xóa dữ liệu. Chạy lại nhiều lần không lỗi (`add column if not exists`, ràng buộc chỉ tạo khi chưa có).

**Vì sao phải chạy V3 trước khi triển khai code mới:** code của PR #1 ghi 7 cột mới khi lưu kết quả đánh giá (`PostgresRepository.save_evaluation`). Trên schema V2, `POST /internal/v1/evaluations` lỗi `UndefinedColumn hit_rate_at_k`. Các API gợi ý và ghi hành vi không dùng các cột này, nên không bị ảnh hưởng.

## 2. Bằng chứng kiểm thử trên PostgreSQL cô lập

Đã kiểm thử bằng `recommendation-service/scripts/verify_migration_v3.py` trên PostgreSQL 15.20 và 17.10 chạy trong Docker. Script từ chối mọi host không phải localhost. Kết quả: 11/11 kiểm tra đạt trên cả hai bản:

- `evidence/recommendation/migration-v3-rehearsal-pg15-20261123.json`
- `evidence/recommendation/migration-v3-rehearsal-pg17-20261123.json`

| Kịch bản | Kiểm tra |
|---|---|
| DB sạch | V1 → V2 → V3 tạo đủ 7 cột và ràng buộc; role runtime `recommendation_app` ghi được qua `POST /internal/v1/evaluations` |
| Nâng V2 có dữ liệu lên V3 | Code mới trên V2 lỗi (lý do phải chạy V3 trước); V3 chạy hai lần vẫn đúng; các dòng đánh giá cũ giữ nguyên, cột mới NULL; số dòng mọi bảng không đổi; role runtime ghi được qua API; ràng buộc từ chối `ndcg_at_k = 1.5`; permission probe đạt |
| Quay về ứng dụng cũ | Câu lệnh ghi của code cũ vẫn chạy trên schema V3, nên quay về ứng dụng cũ **không cần** quay về schema |

Chạy lại kiểm thử:

```powershell
docker run -d --name rec-v3-pg17 -e POSTGRES_PASSWORD=rehearsal -p 55417:5432 postgres:17-alpine
cd recommendation-service
.\.venv\Scripts\python.exe scripts\verify_migration_v3.py `
  --admin-url postgresql://postgres:rehearsal@127.0.0.1:55417/postgres `
  --evidence ..\evidence\recommendation\migration-v3-rehearsal-pg17-<ngày>.json
docker rm -f rec-v3-pg17
```

## 3. Các bước triển khai

Thứ tự bắt buộc: **kiểm tra → sao lưu → V3 → xác nhận → bật code mới**. Không đảo bước 3 và 5.

**Bước 0. Điều kiện**
- PR #1 đã được duyệt; commit sẽ triển khai đã chốt.
- Có DSN của role quản trị (role đã chạy V1/V2 lần trước, là thành viên của `recommendation_owner`; xem `scripts/maintenance/apply-phase-5-supabase.py`). Dùng kết nối **direct hoặc session pooler (cổng 5432)**, không dùng transaction pooler, vì V3 dùng `set role`.
- Không ai khác đang chạy migration trên Supabase (O03).

**Bước 1. Kiểm tra trước (chỉ đọc)**

```powershell
cd recommendation-service
.\.venv\Scripts\python.exe scripts\verify_migration_v3.py --check-only "<DSN>"
```

Kết quả mong đợi trước V3: `"v3Applied": false` và đủ 7 cột trong `v3ColumnsMissing`. Ghi lại `evaluationRows`.
Nếu đã `"v3Applied": true` thì V3 đã chạy rồi: bỏ qua bước 2–3.

**Bước 2. Sao lưu**: xuất bảng `recommendation.evaluation_runs` (và backup định kỳ theo O01) trước khi đổi schema.

**Bước 3. Chạy V3** bằng role quản trị, trong một transaction, dừng ngay khi có lỗi. Không cần cài psql trên máy:

```powershell
Get-Content -Raw migrations\V3__extend_evaluation_runs.sql |
  docker run --rm -i postgres:17-alpine psql "<DSN quản trị>" -v ON_ERROR_STOP=1 --single-transaction -f -
```

Lỗi `permission denied to set role "recommendation_owner"` nghĩa là role đang dùng không phải thành viên của `recommendation_owner`. Dừng lại, không tự cấp quyền khi chưa thống nhất.

**Bước 4. Xác nhận**: người thứ hai chạy lại lệnh của bước 1:
- `"v3Applied": true`, `v3ColumnsMissing` rỗng, `rangeConstraint: true`;
- `evaluationRows` bằng giá trị ghi ở bước 1.

Lưu output của bước 1, 3, 4 vào `evidence/recommendation/migration-v3-supabase-<ngày>.json` (không ghi DSN hay mật khẩu).

**Bước 5. Mốc cho phép triển khai**: chỉ khi bước 4 đạt mới triển khai code mới (recommendation-service API + worker).

**Bước 6. Kiểm tra sau triển khai**
- `GET /health/ready` trả `ready`.
- Gọi `POST /internal/v1/evaluations` với một ca nhỏ (header `X-Internal-Service-Token`), trả 200 và có `ndcgAtK`.
- Hoặc chạy `scripts/record_evaluation_runs.py` ở chế độ chạy thử.

## 4. Quay lại (rollback)

| Tình huống | Cách xử lý |
|---|---|
| Code mới có lỗi | Triển khai lại phiên bản ứng dụng trước. **Giữ nguyên schema V3**: code cũ không đọc cột mới và vẫn ghi được (đã kiểm thử) |
| Cần tắt Recommendation | Đặt `RECOMMENDATION_ENABLED=false`. Storefront ẩn khối gợi ý, chatbot quay về Catalog |
| V3 lỗi giữa chừng | Chạy với `--single-transaction` thì không có thay đổi nào được ghi. Sửa nguyên nhân rồi chạy lại từ bước 1 |
| Bắt buộc gỡ V3 (không khuyến nghị) | Mất dữ liệu của 7 cột. Chỉ làm khi nhóm đồng ý, sau khi sao lưu: |

```sql
set role recommendation_owner;
alter table recommendation.evaluation_runs drop constraint if exists chk_evaluation_runs_v3_ranges;
alter table recommendation.evaluation_runs
    drop column if exists task, drop column if exists segment, drop column if exists case_count,
    drop column if exists hit_rate_at_k, drop column if exists ndcg_at_k,
    drop column if exists coverage_at_k, drop column if exists report_sha256;
reset role;
```

Sau khi gỡ V3, **không** được chạy code của PR #1 (ghi đánh giá sẽ lỗi).
