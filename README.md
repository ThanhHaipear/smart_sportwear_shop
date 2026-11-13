# Smart Sportswear Shop

Nền tảng thương mại điện tử thời trang thể thao gồm cửa hàng cho khách hàng, trang quản trị và hệ thống backend microservice. Dự án hỗ trợ quản lý danh mục - tồn kho, tài khoản, giỏ hàng - đơn hàng, thanh toán VietQR/SePay, đổi trả, chatbot, tìm kiếm và gợi ý sản phẩm.

## Tính năng chính

- Storefront: duyệt danh mục, tìm kiếm, chi tiết sản phẩm, yêu thích, giỏ hàng, thanh toán, theo dõi đơn hàng và quản lý tài khoản.
- Admin: quản lý sản phẩm, danh mục, thương hiệu, tồn kho, khách hàng, đơn hàng, đổi trả, báo cáo, cấu hình hệ thống và audit log.
- Xác thực: đăng ký/đăng nhập, Google Login, JWT access/refresh token, quên mật khẩu, hồ sơ và sổ địa chỉ.
- Bán hàng: checkout, giữ tồn kho, đơn hàng, hóa đơn, thông báo, VietQR, webhook SePay, hủy đơn, đổi trả và hoàn tiền.
- AI: chatbot tư vấn sản phẩm/chính sách, tìm kiếm lai từ khóa - semantic, tìm kiếm ảnh (đang giữ ở trạng thái feature hold) và gợi ý sản phẩm.
- Vận hành: API Gateway, rate limit, correlation ID, health check, metrics, event qua RabbitMQ và log tập trung cho runtime local.

## Kiến trúc hiện tại

Backend monolith cũ tại cổng `8082` đã được retire. Storefront và Admin chỉ gọi API qua Gateway; mỗi microservice sở hữu schema riêng trên cùng Supabase PostgreSQL project.

```text
Storefront :3000 ─┐
                  ├──> Gateway :8085
Admin      :3001 ─┘       ├── Identity       :8093 ──> schema identity
                           ├── Catalog        :8092 ──> schema catalog
                           ├── Sales          :8094 ──> schema sales
                           ├── Chatbot        :8002 ──> schema chatbot
                           ├── Recommendation :8091 ──> schema recommendation
                           └── Search         :8090 ──> schema ai_search

Redis :6379       phiên chatbot/cache
RabbitMQ :5672    event và worker; giao diện quản trị :15672
```

| Thành phần | Công nghệ | Trách nhiệm |
|---|---|---|
| Storefront | Next.js 16, React 19, TypeScript, Tailwind CSS 4 | Giao diện mua sắm |
| Admin | Next.js 15, React 19, TypeScript | Giao diện quản trị |
| Gateway | FastAPI, HTTPX | Định tuyến, xác thực, CORS, rate limit, observability |
| Identity | Spring Boot 3.5, Java 21 | Người dùng, vai trò, JWT và địa chỉ |
| Catalog | Spring Boot 3.5, Java 21 | Sản phẩm, danh mục, thương hiệu, tồn kho và wishlist |
| Sales | Spring Boot 3.5, Java 21 | Giỏ hàng, checkout, đơn hàng, thanh toán và đổi trả |
| Chatbot | FastAPI, LangGraph | Hội thoại và công cụ tư vấn bán hàng |
| Search | FastAPI, PostgreSQL/pgvector | Tìm kiếm text, semantic và ảnh |
| Recommendation | FastAPI, PostgreSQL | Gợi ý sản phẩm và xử lý interaction event |
| Hạ tầng | Supabase PostgreSQL, Redis, RabbitMQ, Docker Compose | Dữ liệu, cache/session và messaging |

## Cấu trúc repository

```text
frontend/
  storefront/                 website khách hàng
  admin/                      website quản trị
gateway/                      API Gateway
catalog-service/              Catalog microservice
identity-service/             Identity microservice
sales-service/                Sales microservice
chatbot-service/              Chatbot microservice
recommendation-service/       Recommendation API và worker
services/search-service/      Search API và indexing worker
contracts/                    OpenAPI và JSON Schema cho REST/event
infra/                        database migration và observability
scripts/
  run/                        launcher runtime local
  setup/                      cài công cụ local
  maintenance/                migration, audit và bảo trì dữ liệu
docs/                         tài liệu thiết kế, migration và runbook
evidence/                     kết quả benchmark/acceptance đã lưu
```

## Chạy local

### Yêu cầu

- Windows PowerShell và Docker Desktop với Docker Compose.
- Python 3 cho Gateway và các service FastAPI; mỗi service ưu tiên virtual environment tại `.venv` nếu có.
- Java 21 cho ba service Spring Boot. Repository có Maven Wrapper nên không cần cài Maven toàn cục.
- Node.js 24 cho frontend. Launcher hiện dùng bản project-local tại `.tools/node-v24.14.1-win-x64`.
- Quyền truy cập Supabase và các khóa dịch vụ/LLM tương ứng với môi trường phát triển.

Có thể cài JDK 21 project-local bằng:

```powershell
.\scripts\setup\setup-local-tools.cmd
```

### Cấu hình

Runtime local lấy cấu hình chung từ file không commit `.env.phase10.local`. File này phải chứa DSN theo từng schema, JWT secret, service token và thông tin RabbitMQ cần thiết. Các `.env` ở từng service chỉ bổ sung cấu hình riêng; launcher nạp cấu hình chung sau cùng.

Các nhóm biến quan trọng:

- `CATALOG_*`, `IDENTITY_*`, `SALES_*`, `CHATBOT_DATABASE_URL`, `SEARCH_DATABASE_URL`, `RECOMMENDATION_DATABASE_URL`.
- `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` và các internal service token.
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`.
- `OPENAI_API_KEY`/`ANTHROPIC_API_KEY` khi bật chức năng AI tương ứng.
- `SEPAY_*`, `VIETQR_*` khi kiểm thử thanh toán chuyển khoản.

Không commit `.env.phase10.local`, `.env.local`, mật khẩu database, JWT secret hoặc API key. `.env.legacy-backend.local` chỉ là snapshot phục vụ audit/backup lịch sử, không được launcher nạp vào runtime hiện tại.

### Khởi động nhanh

Từ thư mục gốc repository:

```powershell
.\scripts\run\start-all-local.cmd
```

Lệnh này khởi động Redis và RabbitMQ bằng Docker, sau đó chạy nền sáu microservice, hai worker, Gateway, Storefront và Admin. Log được ghi vào `logs/local`.

Một số tùy chọn hữu ích:

```powershell
# Không chạy hai frontend
.\scripts\run\start-all-local.cmd -NoFrontends

# Khởi động và theo dõi log trong terminal hiện tại
.\scripts\run\start-all-local.cmd -FollowLogs

# Kiểm tra trạng thái
.\scripts\run\status-local.cmd

# Theo dõi log của các tiến trình đang chạy
.\scripts\run\show-local-logs.cmd

# Dừng application/worker/frontend, giữ Redis và RabbitMQ
.\scripts\run\stop-all-local.cmd

# Dừng cả application và hạ tầng Docker
.\scripts\run\stop-all-local.cmd -Infrastructure
```

Chi tiết cách chạy từng service, thứ tự phụ thuộc, xử lý log và SePay tunnel nằm trong [RUN_LOCAL.md](RUN_LOCAL.md).

## Địa chỉ local

| Thành phần | URL |
|---|---|
| Storefront | <http://localhost:3000> |
| Admin | <http://localhost:3001> |
| Gateway | <http://localhost:8085> |
| Gateway readiness | <http://localhost:8085/health/ready> |
| Search readiness | <http://localhost:8090/health/ready> |
| Recommendation readiness | <http://localhost:8091/health/ready> |
| Catalog health | <http://localhost:8092/actuator/health> |
| Identity health | <http://localhost:8093/actuator/health> |
| Sales health | <http://localhost:8094/actuator/health> |
| Chatbot readiness | <http://localhost:8002/health/ready> |
| RabbitMQ Management | <http://localhost:15672> |

## Kiểm thử và nghiệm thu

Gate nghiệm thu microservice hiện tại:

```powershell
# Kiểm tra tĩnh, không cần runtime đang chạy
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\verify-microservice-phase-12.ps1 -StaticOnly

# Chạy nghiệm thu và lưu evidence
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\verify-microservice-phase-12.ps1 `
  -EvidencePath evidence/microservice-phase-12-local-acceptance.json
```

Các nhóm test riêng nằm trong từng service (`pytest` cho Python, Maven test cho Spring Boot). Frontend storefront cung cấp các lệnh:

```powershell
cd frontend\storefront
npm run lint
npm run typecheck
npm run build
```

## Tài liệu liên quan

- [Hướng dẫn chạy local](RUN_LOCAL.md)
- [Hướng dẫn scripts](scripts/README.md)
- [Hợp đồng API và event](contracts/README.md)
- [Kế hoạch migration microservice](docs/microservice-migration-plan.md)
- [Final acceptance](docs/microservice-phase-12-final-acceptance.md)
- [Thiết kế VietQR/SePay](docs/vietqr-sepay-local-payment-design.md)
- [Kế hoạch Visual Search](docs/visual-search-plan.md)

## Lưu ý dữ liệu

- Runtime startup luôn tắt Flyway; migration/finalization database phải được chạy bằng script bảo trì có kiểm soát.
- Không dùng `docker compose down -v` nếu cần giữ volume Redis/RabbitMQ.
- Visual Search hiện mặc định tắt theo feature hold; text search vẫn phục vụ qua Search service.
