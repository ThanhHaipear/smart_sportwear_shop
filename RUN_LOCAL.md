# Chạy Smart Sportswear Shop cục bộ sau khi tách microservice

## Mô hình runtime

- Docker chỉ chạy Redis và RabbitMQ.
- Gateway, sáu microservice, hai worker và frontend chạy trong terminal Windows riêng.
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
3. mở terminal Catalog và Identity, chờ hai service sẵn sàng;
4. mở Sales, Search, Recommendation và các worker;
5. mở Chatbot, Gateway, Storefront và Admin;
6. ghi log riêng vào `logs/local`.

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

Mỗi service vừa ghi log ra terminal vừa append vào `logs/local/<service>.log`.

Tìm lỗi trong toàn bộ service:

```powershell
.\scripts\run\show-local-logs.cmd
```

Theo dõi một service:

```powershell
.\scripts\run\show-local-logs.cmd gateway
.\scripts\run\show-local-logs.cmd sales-service
```

Theo dõi Redis và RabbitMQ:

```powershell
.\scripts\run\show-local-logs.cmd infra
```

Khi một request lỗi, lấy `X-Correlation-ID` từ response/gateway log rồi tìm xuyên service:

```powershell
rg "<correlation-id>" .\logs\local
```

## Dừng hệ thống

- Nhấn `Ctrl+C` trong từng terminal application/service.
- Dừng hạ tầng Docker khi không còn dùng:

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
