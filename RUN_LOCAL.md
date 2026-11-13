# Chạy Smart Sportswear Shop cục bộ sau khi tách microservice

## Mô hình runtime

- Docker chỉ chạy Redis và RabbitMQ.
- Gateway, sáu microservice, hai worker và frontend chạy nền dưới sự quản lý của launcher local.
- Toàn bộ log được gom vào `logs/local`; không cần mở một terminal cho mỗi service.
- Mỗi microservice kết nối trực tiếp tới schema/role riêng trên cùng Supabase project qua
  `.env.phase10.local`.
- Backend monolith tại cổng `8082` đã retire và không còn nằm trong luồng chạy.

```text
Storefront/Admin -> Gateway :8085
                    |-- Identity :8093 -> Supabase schema identity
                    |-- Catalog :8092 -> Supabase schema catalog
                    |-- Sales :8094 -> Supabase schema sales
                    |-- Chatbot :8002 -> Supabase schema chatbot
                    |-- Recommendation :8091 -> Supabase schema recommendation
                    `-- Search :8090 -> Supabase schema ai_search

Docker: Redis :6379 + RabbitMQ :5672/:15672
```

## Chạy toàn bộ bằng một lệnh

Từ thư mục gốc repository:

```powershell
.\scripts\run\start-all-local.cmd
```

Launcher tổng sẽ:

1. dừng các application container còn sót từ lần chạy Compose đầy đủ;
2. chỉ khởi động `redis` và `rabbitmq` trong Docker;
3. chạy nền Catalog và Identity, chờ hai service sẵn sàng;
4. chạy nền Sales, Search, Recommendation và các worker;
5. chạy nền Chatbot, Gateway, Storefront và Admin;
6. ghi log riêng vào `logs/local`.

Không có nhiều tab terminal được mở. Để khởi động rồi theo dõi log ngay trong terminal hiện tại:

```powershell
.\scripts\run\start-all-local.cmd -FollowLogs
```

Nhấn `Ctrl+C` trong chế độ này chỉ dừng màn hình theo dõi log; các service vẫn chạy nền.

Chỉ chạy backend/gateway, không mở frontend:

```powershell
.\scripts\run\start-all-local.cmd -NoFrontends
```

## Chạy thủ công từng thành phần

Hạ tầng:

```powershell
.\scripts\run\start-infrastructure.cmd
```

Application services:

```powershell
.\scripts\run\start-catalog-service.cmd
.\scripts\run\start-identity-service.cmd
.\scripts\run\start-sales-service.cmd
.\scripts\run\start-search-service.cmd
.\scripts\run\start-search-worker.cmd
.\scripts\run\start-recommendation-service.cmd
.\scripts\run\start-recommendation-worker.cmd
.\scripts\run\start-chatbot-service.cmd
.\scripts\run\start-gateway.cmd
```

Frontend:

```powershell
.\scripts\run\start-storefront.cmd
.\scripts\run\start-admin.cmd
```

Thứ tự phụ thuộc khi chạy thủ công:

1. Redis và RabbitMQ.
2. Catalog và Identity.
3. Sales, Search và Recommendation.
4. Search Worker và Recommendation Worker.
5. Chatbot.
6. Gateway.
7. Storefront và Admin.

## Địa chỉ cục bộ

| Thành phần | URL |
|---|---|
| Storefront | <http://localhost:3000> |
| Admin | <http://localhost:3001> |
| Gateway | <http://localhost:8085> |
| Gateway readiness | <http://localhost:8085/health/ready> |
| Search | <http://localhost:8090/health/ready> |
| Chatbot | <http://localhost:8002/health/ready> |
| Recommendation | <http://localhost:8091/health/ready> |
| Catalog | <http://localhost:8092/actuator/health> |
| Identity | <http://localhost:8093/actuator/health> |
| Sales | <http://localhost:8094/actuator/health> |
| RabbitMQ management | <http://localhost:15672> |

## Đọc log

Mỗi backend, worker và frontend ghi log vào `logs/local/<service>.log`.

Theo dõi realtime log của toàn bộ service trong cùng một terminal:

```powershell
.\scripts\run\show-local-logs.cmd
```

Theo dõi riêng một service:

```powershell
.\scripts\run\show-local-logs.cmd gateway
.\scripts\run\show-local-logs.cmd sales-service
```

Tìm các lỗi đã ghi trong toàn bộ service:

```powershell
.\scripts\run\show-local-logs.cmd errors
```

Theo dõi Redis và RabbitMQ:

```powershell
.\scripts\run\show-local-logs.cmd infra
```

Khi một request lỗi, lấy `X-Correlation-ID` từ response/gateway log rồi tìm xuyên service:

```powershell
Get-ChildItem .\logs\local\*.log | Select-String -Pattern "<correlation-id>"
```

## Kiểm tra trạng thái

```powershell
.\scripts\run\status-local.cmd
```

`RUNNING (managed)` là tiến trình do launcher nền quản lý. `RUNNING (external)` là tiến trình đã được mở thủ công trong terminal hoặc IDE.

## Dừng hệ thống

Dừng toàn bộ application service, worker và frontend do launcher quản lý:

```powershell
.\scripts\run\stop-all-local.cmd
```

Dừng cả application lẫn Redis/RabbitMQ:

```powershell
.\scripts\run\stop-all-local.cmd -Infrastructure
```

Nếu một service được chạy thủ công trong terminal/IDE (`RUNNING (external)`), dừng nó bằng `Ctrl+C` tại terminal hoặc nút Stop của IDE.

Dừng riêng hạ tầng Docker khi không còn dùng:

```powershell
docker compose stop redis rabbitmq
```

Không dùng `docker compose down -v`; tùy chọn `-v` sẽ xóa volume Redis/RabbitMQ.

## Cấu hình và an toàn dữ liệu

- `.env.phase10.local` là nguồn runtime cho DSN, token nội bộ, JWT và RabbitMQ credential.
- Các `.env` riêng chỉ là cấu hình service bổ sung; launcher nạp runtime chung sau cùng để tránh dùng nhầm
  database cũ.
- Launcher host đổi Docker DNS (`catalog-service`, `rabbitmq`, `redis`...) thành `127.0.0.1`.
- Flyway bị tắt trong launcher runtime. Migration/finalization database phải chạy bằng maintenance script riêng.
- Backend monolith cổng `8082` đã retire và launcher cũ đã được loại bỏ.
- Visual Search tiếp tục tắt theo visual hold; text search vẫn hoạt động qua `search-service`.

## SePay tunnel

Sau khi Gateway `8085` sẵn sàng:

```powershell
.\scripts\run\start-sepay-tunnel.cmd
```

Tunnel chỉ forward `POST /api/v1/webhooks/sepay` vào Gateway.
