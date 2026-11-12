# Chatbot Service

Chatbot độc lập cho Smart Sportswear Shop. Service sở hữu lịch sử hội thoại trong
schema `chatbot` và không đọc/ghi trực tiếp dữ liệu Catalog hoặc Search.

## Phụ thuộc runtime

- Search API: `http://localhost:8090`
- Catalog API: `http://localhost:8092`
- Redis: `redis://localhost:6379`
- PostgreSQL: role `chatbot_app`, schema `chatbot`

Chatbot gọi Search qua `POST /internal/v1/search`, sau đó lấy tài liệu sản phẩm
hiện hành từ Catalog API. Việc index, embedding và RabbitMQ consumer thuộc
`search-service`, không thuộc Chatbot.

## Chạy local

Từ thư mục gốc repository:

```powershell
.\scripts\run\start-chatbot-service.cmd
```

Hoặc chạy trực tiếp trong `chatbot-service`:

```powershell
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-test.txt
Copy-Item .env.example .env
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8002
```

## API

- `POST /chat`
- `POST /chat/stream`
- `GET /health/live`
- `GET /health/ready`

Các API public được truy cập qua Gateway. Token nội bộ và DSN được nạp từ
`.env.phase10.local` khi chạy bằng launcher của repository.

## Kiểm thử

```powershell
.\.venv\Scripts\python -m pytest -q
```
