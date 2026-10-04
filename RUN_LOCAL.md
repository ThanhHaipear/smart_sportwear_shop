# Chạy dự án local

Redis và RabbitMQ chạy bằng Docker. Backend, admin, storefront, AI service và visual-search service chạy trực tiếp trong các cửa sổ PowerShell riêng.

## Chuẩn bị lần đầu

Mở Docker Desktop, sau đó chạy:

```powershell
.\setup-local-tools.cmd
```

Cấu hình local được đọc từ:

- `backend/.env`
- `frontend/admin/.env.local`
- `frontend/storefront/.env.local`

Các file này chứa thông tin riêng và không được commit. Các file `*.example` là cấu hình mẫu an toàn để đưa lên Git.

## Chạy từng tác vụ

### 1. Redis và RabbitMQ

```powershell
docker compose up -d redis rabbitmq
```

Dừng hạ tầng local:

```powershell
docker compose stop redis rabbitmq
```

### 2. Backend

Mở một cửa sổ PowerShell riêng:

```powershell
.\start-backend.cmd
```

### 3. Admin

Mở một cửa sổ PowerShell riêng:

```powershell
.\start-admin.cmd
```

### 4. Storefront

Mở một cửa sổ PowerShell riêng:

```powershell
.\start-storefront.cmd
```

### 5. AI forecasting (khi cần)

```powershell
.\start-ai-service.ps1
```

### 6. Visual search (khi cần)

Sao chép `visual-search-service/.env.example` thành `visual-search-service/.env`, điền cấu hình local và chỉ đặt `VISUAL_SEARCH_ENABLED=true` sau khi các giá trị bắt buộc đã có. Sau đó:

```powershell
cd visual-search-service
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-test.txt
.\.venv\Scripts\python scripts\run_api.py
```

Mở thêm một cửa sổ PowerShell cho worker (bắt buộc với index/backfill):

```powershell
cd visual-search-service
python -m app.worker
```

Sau khi backend publisher và worker đều healthy, kiểm tra backfill ở chế độ dry-run
trước khi enqueue. Không chạy lại backfill production nếu coverage đã đầy đủ:

```powershell
cd visual-search-service
python scripts/backfill_embeddings.py
```

Quy trình benchmark, rollout theo nhóm nhỏ và rollback nằm tại
`docs/visual-search-rollout.md`.

RabbitMQ Management chỉ được bind vào loopback cho môi trường local: <http://127.0.0.1:15672>. Tài khoản mặc định local là `visual_search` / `change-me`; hãy đặt `RABBITMQ_DEFAULT_USER` và `RABBITMQ_DEFAULT_PASS` khác khi dùng ngoài máy phát triển.

RabbitMQ tự nạp topology từ `rabbitmq/definitions.json`: exchange `catalog.events`, main queue, ba retry queue và DLQ. Nếu thay đổi topology trên một volume đã tồn tại, hãy dùng Management UI để kiểm tra trước; không xóa volume chứa message nếu chưa sao lưu hoặc chưa xác nhận dữ liệu có thể bỏ.

Dừng backend, admin, storefront hoặc AI service bằng `Ctrl+C` trong đúng cửa sổ đang chạy tác vụ đó.

## Địa chỉ local

- Storefront: <http://localhost:3000>
- Admin: <http://localhost:3001>
- AI forecasting: <http://localhost:8081>
- Backend: <http://localhost:8082>
- Backend health: <http://localhost:8082/actuator/health>
- Redis: `localhost:6379`
- RabbitMQ AMQP: `localhost:5672`
- RabbitMQ Management: <http://127.0.0.1:15672>
- Visual search: <http://localhost:8090/health/ready>

Dữ liệu chính nằm trên Supabase. Docker Compose không chạy PostgreSQL, backend, frontend hoặc visual-search service.
# Product hybrid search

Product hybrid search requires Redis and RabbitMQ from Docker, the customer
chatbot service, its indexing worker, and the Spring backend.

```powershell
docker compose up -d redis rabbitmq
.\start-chatbot-service.ps1
.\start-product-search-indexer.ps1
.\start-backend.ps1
```

Use the corresponding `.cmd` launchers when starting from Command Prompt.
`PRODUCT_SEARCH_INTERNAL_TOKEN` must be present and identical in
`backend/.env` and `chatbot-service/.env`. Keep
`PRODUCT_HYBRID_SEARCH_ENABLED=false` until migrations, embedding coverage,
the queue consumer, and the acceptance benchmark have been verified.
For local development, keep `PRODUCT_SEARCH_TIMEOUT_SECONDS=12` in
`backend/.env` so a cold semantic-search request has enough time to finish.
