# Phase 9 - Gateway và vận hành

## Trạng thái

Phase 9 hoàn thành implementation và acceptance ngày 11/11/2026, trạng thái `DONE`.

Gateway, observability manifests, SLO, alert và resilience drill đã đạt gate. Cut-over được điều khiển độc lập theo bounded context; các flag mặc định `false` nên deployment Phase 9 không tự đổi route hoặc write authority của Phase 3-8.

## Public entrypoint và network boundary

- Gateway độc lập tại `gateway`, cổng public duy nhất của các application service là `8080`.
- Identity, Catalog, Sales, Search, Chatbot và Recommendation chỉ dùng `expose` trong mạng Compose; Redis/RabbitMQ và UI vận hành chỉ bind loopback.
- Gateway dùng allowlist ưu tiên cho Identity, Sales, Chatbot và Recommendation. Public `/api/**` chưa tách được chuyển về `LEGACY_UPSTREAM`, là core release ổn định trước đó.
- `GATEWAY_IDENTITY_CUTOVER_ENABLED`, `GATEWAY_SALES_CUTOVER_ENABLED`, `GATEWAY_CHATBOT_CUTOVER_ENABLED` và `GATEWAY_RECOMMENDATION_CUTOVER_ENABLED` là kill switch riêng. Khi tắt, API tương ứng đi về stable upstream; bật/tắt một context không ảnh hưởng context khác.
- `/internal/**`, mọi path chứa `/internal/`, `/actuator/**`, docs và admin surface không được khai báo đều trả 404 tại gateway.
- Header nội bộ do client gửi vào bị loại bỏ. Gateway đặt `X-Forwarded-For`, `X-Forwarded-Proto`, correlation ID và W3C `traceparent` hợp lệ.
- Rate limit mặc định 120 request/phút + burst 30 theo client; timeout upstream mặc định 10 giây; lỗi transport trả envelope 503 không lộ topology.
- Proxy giữ streaming response để `/chat/stream` tiếp tục hoạt động.

## Health, metrics, log và trace

- Gateway: `/health/live`, `/health/ready`, `/metrics`.
- Spring services: `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` với Prometheus registry.
- Python services: `/health/live`, `/health/ready`, `/metrics`.
- Gateway ghi access log JSON gồm status, latency, correlation ID, trace ID và upstream; các service ghi correlation/trace context vào log và echo header.
- Prometheus scrape 7 application services; Grafana được provision dashboard `phase9-operations`; alert có service-down, tỷ lệ 5xx và upstream failure.
- Gateway xuất root span OTLP/HTTP sang OpenTelemetry Collector và Collector chuyển trace sang Tempo. Gateway luôn tạo/giữ `traceparent`, vì vậy có thể tìm cùng trace/correlation xuyên gateway và service ngay cả trong giai đoạn rollout child-span instrumentation từng service.

## SLO khởi điểm

| Luồng | SLI | SLO 30 ngày | Alert sớm |
|---|---|---|---|
| Gateway public API | response không phải 5xx | >= 99.5% | 5xx > 2% trong 5 phút |
| Gateway availability | `/health/ready` thành công | >= 99.9% | down liên tục 2 phút |
| Public API latency | gateway latency | p95 < 800 ms, trừ chat streaming | theo dashboard/on-call review |
| Payment webhook | 2xx sau commit hoặc 4xx có chủ ý | >= 99.9% | bất kỳ chuỗi transport failure > 5/5 phút |

## Acceptance và resilience drill

Chạy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-9.ps1
```

Gate kiểm tra:

- route allowlist, method boundary, controlled cut-over fallback, chặn internal/actuator và loại internal token từ client;
- correlation/trace propagation, rate limit, streaming proxy và Prometheus metric;
- service-down drill trả 503 có giới hạn, không lộ lỗi upstream;
- health/readiness/metrics contract và Prometheus/Grafana/Tempo/alert manifests;
- Compose config hợp lệ và không publish trực tiếp cổng application service;
- test của Identity, Catalog và Sales vẫn đạt sau thay đổi observability.

Kết quả acceptance ban đầu ngày 10/11/2026: gateway/resilience 5/5; Identity/Catalog/Sales 20/20 (10 suites, 0 failure/error/skip); Docker Compose config hợp lệ; image `smart-sportswear-shop-gateway:latest` build thành công. Gate ngày 11/11/2026 bổ sung controlled-cutover fallback và đạt 6/6 gateway tests.

## Deploy, sự cố và rollback

1. Giữ core release chạy tại `LEGACY_UPSTREAM`; tạo secret production riêng và đổi Grafana password.
2. Khởi động application profiles cần thiết, sau đó `gateway` và `observability`; kiểm tra readiness và Prometheus targets.
3. Gửi một request có correlation ID, xác nhận cùng ID/trace ID trong gateway và service log.
4. Chỉ đổi một nhóm route sang service mới sau shadow/E2E gate của phase sở hữu đạt. Không đổi writer bằng thao tác deploy gateway.
5. Khi 5xx tăng: lọc dashboard theo upstream, tra `docker compose logs gateway <service>` bằng correlation ID, kiểm tra readiness, DB pool và RabbitMQ.
6. Khi service lỗi: giữ gateway, đổi upstream route về core release hoặc rollback image service; không replay request tài chính tùy ý.
7. Khi gateway lỗi: route load balancer tạm về core release ổn định trước đó; giữ log/metrics/trace để điều tra.
8. Rollback không drop schema, không reverse-copy dữ liệu, không bật dual-write và không thay đổi các event/SePay flag đang tắt.

