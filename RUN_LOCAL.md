# Chạy dự án local

Tài liệu này mô tả runtime mặc định theo kiến trúc V2. Trong giai đoạn database mới còn trắng, không bật Flyway, seed, hybrid search hoặc visual search cho tới khi baseline migration V2 được duyệt.

## Phạm vi runtime

Core stack:

- `backend`: Commerce Core, cổng `8082`.
- `frontend/storefront`: Storefront, cổng `3000`.
- `frontend/admin`: Admin, cổng `3001`.
- Redis và RabbitMQ từ Docker Compose.

Legacy AI đang được giữ để chuyển đổi dần:

- `chatbot-service`: Assistant hiện tại đồng thời chứa một phần hybrid text search.
- `visual-search-service`: image embedding và visual search hiện tại.

Experimental, không chạy mặc định và không phải dependency của core stack:

- `ai_forecasting_service`.
- `chatbot-admin-service`.
- Các màn hình Admin Copilot/AI Insights, được khóa bởi `NEXT_PUBLIC_ADMIN_AI_WORKSPACES_ENABLED=false`.

## Chuẩn bị cấu hình

Không commit file chứa secret. Tạo file local từ các mẫu:

```powershell
Copy-Item backend/.env.example backend/.env
Copy-Item frontend/admin/.env.local.example frontend/admin/.env.local
Copy-Item frontend/storefront/.env.local.example frontend/storefront/.env.local
Copy-Item chatbot-service/.env.example chatbot-service/.env
Copy-Item visual-search-service/.env.example visual-search-service/.env
```

Trong thời gian chưa có baseline V2, giữ:

```dotenv
SPRING_FLYWAY_ENABLED=false
APP_SEED_ENABLED=false
PRODUCT_HYBRID_SEARCH_ENABLED=false
VISUAL_SEARCH_ENABLED=false
NEXT_PUBLIC_ADMIN_AI_WORKSPACES_ENABLED=false
```

`backend/.env` có thể chứa kết nối Supabase integration/demo, nhưng test tự động và phát triển schema phải dùng PostgreSQL local/Testcontainers. Không chạy test hoặc reset dữ liệu trên Supabase dùng chung.

## Chạy hạ tầng local

```powershell
docker compose up -d redis rabbitmq
docker compose ps
```

RabbitMQ Management: <http://127.0.0.1:15672>. Credential mặc định trong Compose chỉ dành cho máy local; phải đổi khi triển khai ra môi trường khác.

## Chạy core stack

Mỗi tiến trình chạy trong một cửa sổ PowerShell riêng:

```powershell
.\setup-local-tools.cmd
.\start-backend.cmd
.\start-admin.cmd
.\start-storefront.cmd
```

Địa chỉ:

- Storefront: <http://localhost:3000>
- Admin: <http://localhost:3001>
- Backend: <http://localhost:8082>
- Backend health: <http://localhost:8082/actuator/health>

Lưu ý: backend chưa thể khởi động nghiệp vụ trên Supabase trắng khi `ddl-auto=validate` mà baseline V2 chưa tồn tại. Đây là trạng thái chủ động trong giai đoạn thiết kế database.

## Chạy các AI service legacy khi cần đối chiếu

### Chatbot và hybrid text search

Chỉ bật sau khi database/migration legacy tương ứng đã sẵn sàng và token nội bộ khớp giữa backend với service:

```powershell
.\start-chatbot-service.ps1
.\start-product-search-indexer.ps1
```

Giữ `PRODUCT_HYBRID_SEARCH_ENABLED=false` cho tới khi coverage embedding và benchmark acceptance đạt yêu cầu.

### Visual search

```powershell
cd visual-search-service
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-test.txt
.\.venv\Scripts\python scripts\run_api.py
```

Worker chạy trong cửa sổ khác:

```powershell
cd visual-search-service
.\.venv\Scripts\python -m app.worker
```

Không chạy backfill có ghi dữ liệu trước khi kiểm tra đúng database, model version, Cloudinary allowlist và chế độ dry-run.

## Experimental services

Forecasting và Admin Copilot chỉ được khởi động khi thực hiện thí nghiệm riêng:

```powershell
.\start-ai-service.ps1
.\start-chatbot-admin-service.ps1
```

Chúng không được thêm vào quy trình khởi động mặc định, smoke test bắt buộc hoặc tiêu chí demo chính.

## Dừng dịch vụ

- Dừng tiến trình ứng dụng bằng `Ctrl+C` trong đúng terminal.
- Dừng hạ tầng bằng `docker compose stop redis rabbitmq`.
- Không xóa Docker volume hoặc dữ liệu Supabase/Cloudinary khi chưa xác nhận phạm vi và có backup.
