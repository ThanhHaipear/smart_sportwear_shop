# Kế hoạch tách Smart Sportswear Shop thành microservice

## Trạng thái thực hiện

| Giai đoạn | Trạng thái | Bằng chứng |
|---|---|---|
| 0. Chốt baseline | `DONE` | [Phase 0 baseline](microservice-phase-0-baseline.md): current-runtime gate 351/351 |
| 1. Cứng hóa modular boundary | `DONE` | [Boundary hardening log](microservice-phase-1-boundaries.md): repository coupling = 0, integration gate 361/361 |
| 2. Chuẩn hóa event platform | `DONE` | [Phase 2 event platform](microservice-phase-2-event-platform.md): contract v1, outbox confirm/retry, idempotent consumer và DLQ |
| 3. Hoàn thiện `search-service` | `DONE_WITH_VISUAL_HOLD` | [Phase 3 search service](microservice-phase-3-search-service.md): implementation/contracts, schema `ai_search`, runtime role và permission gate đạt; Visual Search giữ tắt theo exception đã chấp thuận, TEST v3 vẫn bắt buộc trước enablement |
| 4. Hoàn thiện `chatbot-service` | `DONE / CUTOVER_HELD` | [Phase 4 chatbot service](microservice-phase-4-chatbot-service.md): 249 tests, schema `chatbot`, runtime role và write/deny permission probes đạt; public cut-over giữ tắt cùng Search |
| 5. Tách `recommendation-service` | `DONE / CUTOVER_HELD` | [Phase 5 recommendation service](microservice-phase-5-recommendation-service.md): 11 tests, core repository = 0, schema `recommendation`, runtime role và write/deny permission probes đạt; public cut-over giữ tắt chờ TEST evaluation độc lập |
| 6-10 | `NOT_STARTED` | Chỉ bắt đầu khi exit gate của giai đoạn trước đạt |

Nguyên tắc thực hiện: tách lần lượt, không di chuyển schema hoặc đổi write authority khi baseline, backup/restore và rollback gate chưa đạt.

## 1. Mục tiêu và phạm vi

Tài liệu này lập kế hoạch chuyển hệ thống hiện tại từ **modular monolith kết hợp các AI service** sang kiến trúc microservice có ranh giới dữ liệu và triển khai rõ ràng.

Mục tiêu:

- Phân rã theo chức năng nghiệp vụ, không tách theo controller hoặc từng bảng.
- Giữ an toàn cho luồng đặt hàng, thanh toán VietQR/SePay, hoàn tiền và tồn kho.
- Tận dụng một Supabase PostgreSQL hiện có nhưng phân quyền sở hữu dữ liệu theo schema.
- Cho phép từng service build, kiểm thử, triển khai và mở rộng độc lập.
- Di chuyển từng bước bằng mô hình strangler, không viết lại toàn bộ hệ thống trong một lần.

Ngoài phạm vi giai đoạn đầu:

- Không tạo một Supabase project cho mỗi service.
- Không tách `payment-service` khỏi `sales-service` khi chưa có Saga và transactional outbox đầy đủ.
- Không tách `admin-service`; Admin là giao diện sử dụng API của các service nghiệp vụ.
- Không cho test tự động ghi vào Supabase dùng chung; integration test tiếp tục dùng PostgreSQL/Testcontainers tạm thời.

## 2. Hiện trạng

| Thành phần | Hiện trạng | Nhận định |
|---|---|---|
| Spring Boot backend | Một ứng dụng chứa các module auth, user, catalog, product, cart, order, payment, inventory, returns, report, recommendation... | Modular monolith; các module còn gọi repository/entity của nhau |
| Supabase PostgreSQL | Một database chính, phần lớn bảng trong `public`; dữ liệu AI đã có schema `ai_retrieval` | Shared database; chưa có quyền sở hữu dữ liệu theo service |
| Visual Search | Python service riêng | Đã gần với microservice độc lập |
| Chatbot/Product Search | Python service riêng và các tiến trình indexer | Có thể phát triển thành Search và Chatbot service độc lập |
| RabbitMQ | Đã có catalog event và outbox | Có nền tảng để chuyển sang giao tiếp bất đồng bộ |
| Redis | Đã dùng cho cache/pub-sub tùy cấu hình | Hạ tầng dùng chung, không phải nơi lưu dữ liệu nguồn |
| Storefront/Admin | Hai frontend riêng | Nên gọi API Gateway; không truy cập Supabase trực tiếp |

## 3. Kiến trúc đích

```text
Storefront / Admin
        |
        v
API Gateway / Backend for Frontend
        |
        +-- identity-service
        +-- catalog-service
        +-- sales-service
        +-- search-service
        +-- chatbot-service
        +-- recommendation-service
                 |
                 v
              RabbitMQ
                 |
                 v
       Supabase PostgreSQL
       (một project vật lý,
        schema/role riêng)
```

Quyết định quan trọng:

1. Dùng **6 service**, đủ phản ánh chức năng chính nhưng vẫn phù hợp quy mô đồ án.
2. Giữ Order, Payment, VietQR/SePay, Return/Refund và Notification tài chính trong `sales-service` để duy trì transaction cục bộ.
3. Giữ Inventory trong `catalog-service` ở giai đoạn đầu vì tồn kho phụ thuộc trực tiếp vào biến thể sản phẩm. Chỉ tách `inventory-service` khi có nhu cầu triển khai hoặc mở rộng độc lập rõ ràng.
4. Gom Semantic Search và Image Search vào `search-service`; chúng dùng chung vòng đời index, embedding và đánh giá retrieval.
5. Báo cáo ban đầu là read model của `sales-service`; chỉ tách `reporting-service` sau khi event nghiệp vụ ổn định.

## 4. Bảng phân chia microservice

| Service | Chức năng sở hữu | Module nguồn hiện tại | Dữ liệu sở hữu | Giao tiếp chính |
|---|---|---|---|---|
| `identity-service` | Đăng ký, đăng nhập, Google login, JWT/refresh token, hồ sơ, địa chỉ, vai trò | `auth`, `user`, `address` | `users`, `user_identities`, `addresses`, `refresh_tokens`, `password_reset_tokens` | REST đồng bộ; phát `UserCreated`, `UserUpdated` |
| `catalog-service` | Danh mục, thương hiệu, taxonomy, sản phẩm, biến thể, ảnh, giá, khuyến mãi, tồn kho, wishlist, review | `catalog`, `category`, `brand`, `product`, `collection`, `promotion`, `combo`, `inventory`, `wishlist`, `review` | Bảng catalog, product, promotion, inventory, wishlist và review | REST cho truy vấn/giữ tồn; phát `ProductChanged`, `InventoryChanged` |
| `sales-service` | Giỏ hàng, checkout, đơn hàng, COD, VietQR/SePay, đối soát, giao hàng, trả hàng, hoàn tiền, thông báo giao dịch | `cart`, `checkout`, `order`, `payment`, `shipping`, `returns`, `notification`, phần báo cáo bán hàng | `carts`, `orders`, `payments`, `bank_transactions`, `returns`, `refunds`, notification tài chính | REST; nhận catalog/inventory API; phát `OrderCreated`, `PaymentCompleted`, `OrderCancelled`, `RefundCompleted` |
| `search-service` | Tìm kiếm thuộc tính, Semantic Search, Image Search, indexer, embedding, đánh giá retrieval | `visual-search-service`, product hybrid search/indexer liên quan | Schema `ai_search`; embedding, tài liệu tìm kiếm, indexing job, usage/evaluation | Nhận catalog event qua RabbitMQ; REST search |
| `chatbot-service` | Hội thoại, phân tích ý định, RAG, tư vấn sản phẩm | `chatbot-service` | Schema `chatbot`; session, message, retrieval trace | Gọi Search/Catalog API; nhận catalog event nếu cần cache |
| `recommendation-service` | Gợi ý theo nội dung, luật kết hợp và lịch sử tương tác | `recommendation`, `interaction` và worker/model liên quan | Schema `recommendation`; interaction, rule, model, recommendation log | Nhận interaction/order/catalog event; REST recommendation |

## 5. Phân vùng dữ liệu trên Supabase

### 5.1 Mô hình triển khai

Sử dụng **một Supabase project** để giảm chi phí và công sức vận hành, nhưng tạo schema và database role riêng:

| Schema | Service sở hữu | Quyền ghi |
|---|---|---|
| `identity` | `identity-service` | Chỉ `identity_app` |
| `catalog` | `catalog-service` | Chỉ `catalog_app` |
| `sales` | `sales-service` | Chỉ `sales_app` |
| `ai_search` | `search-service` | Chỉ `search_app` |
| `chatbot` | `chatbot-service` | Chỉ `chatbot_app` |
| `recommendation` | `recommendation-service` | Chỉ `recommendation_app` |

Mỗi service có:

- Một database role riêng, mật khẩu riêng và connection pool riêng.
- Một chuỗi Flyway/Alembic migration riêng.
- Quyền `USAGE` trên schema cần đọc và quyền ghi chỉ trên schema sở hữu.
- Pool khởi điểm 2-4 connection khi cùng dùng Supabase; chỉ tăng sau khi đo thực tế.

### 5.2 Bảng dữ liệu dự kiến

| Schema | Nhóm bảng |
|---|---|
| `identity` | `users`, `user_identities`, `addresses`, `refresh_tokens`, `password_reset_tokens` |
| `catalog` | taxonomy, `categories`, `brands`, `products`, `product_variants`, `product_images`, dataset, promotion, coupon, combo, inventory, wishlist, review |
| `sales` | `carts`, `cart_items`, `orders`, `order_items`, `order_status_history`, `payments`, `payment_events`, `bank_transactions`, reconciliation, returns, refunds, shipment, notification tài chính |
| `ai_search` | `embedding_models`, search document/facet, text/image embedding, processed event, indexing job, usage/evaluation |
| `chatbot` | `chat_sessions`, `chat_messages`, `retrieval_logs` |
| `recommendation` | `user_interactions`, `association_rules`, `recommendation_models`, `recommendation_logs` |

### 5.3 Nguyên tắc sở hữu

- Một bảng chỉ có một service sở hữu.
- Service khác không dùng repository/JPA entity để sửa bảng đó.
- Không tạo khóa ngoại mới xuyên ranh giới service.
- ID từ service khác được lưu như external reference và kiểm tra qua API/event.
- Không cho frontend sử dụng Supabase Data API để ghi trực tiếp dữ liệu nghiệp vụ.
- Secret Supabase, SePay, JWT và model provider không được dùng chung nếu không cần thiết.
- Dữ liệu nguồn nằm ở service nghiệp vụ; dữ liệu embedding, cache và read model là dữ liệu dẫn xuất, có thể tái tạo.

## 6. API và event tối thiểu

### 6.1 REST API giữa các service

| Consumer | Provider | API cần có | Mục đích |
|---|---|---|---|
| Gateway/frontends | Identity | `/api/v1/auth/*`, `/api/v1/users/me` | Xác thực và hồ sơ |
| Sales | Catalog | `GET /internal/v1/variants/{id}/sale-snapshot` | Lấy tên, SKU, giá và trạng thái bán |
| Sales | Catalog | `POST /internal/v1/inventory/reservations` | Giữ tồn khi checkout |
| Sales | Catalog | `POST /internal/v1/inventory/reservations/{id}/confirm` | Xác nhận trừ tồn |
| Sales | Catalog | `DELETE /internal/v1/inventory/reservations/{id}` | Nhả tồn khi hủy/hết hạn |
| Search | Catalog | `GET /internal/v1/products/{id}/index-document` | Backfill/rebuild index |
| Chatbot | Search | `POST /internal/v1/search` | RAG retrieval |
| Chatbot | Catalog | `GET /internal/v1/products/{id}` | Xác nhận dữ liệu sản phẩm hiện tại |
| Frontends | Recommendation | `/api/v1/recommendations/*` | Lấy gợi ý và ghi nhận impression/click |

API nội bộ phải có token/service identity, timeout, giới hạn retry và correlation ID.

### 6.2 Event qua RabbitMQ

| Event | Producer | Consumer | Dữ liệu tối thiểu |
|---|---|---|---|
| `catalog.product.changed.v1` | Catalog | Search, Chatbot, Recommendation | `eventId`, `productId`, `changeType`, `occurredAt`, `version` |
| `catalog.inventory.changed.v1` | Catalog | Sales, Recommendation | `variantId`, `availableQuantity`, `version` |
| `sales.order.created.v1` | Sales | Recommendation, reporting read model | `orderId`, `userId`, item snapshot, amount, time |
| `sales.order.cancelled.v1` | Sales | Catalog, Recommendation | `orderId`, reservation/reference, reason |
| `sales.payment.completed.v1` | Sales | Recommendation, reporting, notification worker | `paymentId`, `orderId`, amount, provider, paidAt |
| `sales.refund.completed.v1` | Sales | Reporting | `refundId`, `orderId`, `amount`, `completedAt` |
| `interaction.recorded.v1` | Gateway/Catalog/Sales | Recommendation | actor/session, product, event type, time |

Yêu cầu cho mọi consumer:

- Idempotency theo `eventId`.
- Có bảng `processed_events` hoặc inbox tương đương.
- Retry có giới hạn và dead-letter queue.
- Schema event có version và contract test.
- Producer ghi event bằng transactional outbox trong cùng transaction với dữ liệu nghiệp vụ.

## 7. Kế hoạch thực hiện theo giai đoạn

| Giai đoạn | Công việc chính | Sản phẩm bàn giao | Tiêu chí hoàn thành | Rollback |
|---|---|---|---|---|
| 0. Chốt baseline | Đóng băng contract hiện tại; kiểm kê module, bảng, API, job và secret; đo test/build/smoke baseline | Sơ đồ phụ thuộc, API inventory, database ownership matrix, bộ smoke test | Backend và AI test hiện tại đạt; có backup Supabase kiểm tra restore | Không thay đổi runtime |
| 1. Cứng hóa modular boundary | Cấm import repository/entity xuyên bounded context; thêm facade/port; chuẩn hóa correlation ID và error contract | Boundary test, package rule, internal interfaces | Mỗi nhóm module chỉ giao tiếp qua interface được duyệt | Gỡ boundary rule, giữ code cũ |
| 2. Chuẩn hóa event platform | Mở rộng outbox hiện có; định nghĩa JSON Schema; cấu hình retry/DLQ; thêm consumer idempotency | Event contracts v1, outbox relay, inbox/processed events, dashboard queue | Contract test và duplicate-event test đạt | Tắt publisher/consumer bằng feature flag |
| 3. Hoàn thiện `search-service` | Gom Semantic Search + Image Search; chuyển `ai_retrieval` thành `ai_search` hoặc giữ alias tương thích; chỉ nhận dữ liệu catalog qua event/API | Search API, indexer, backfill, evaluation report | Search không ghi bảng catalog; Recall/MRR/NDCG đạt gate đã duyệt | Route traffic về search cũ; rebuild index từ catalog |
| 4. Hoàn thiện `chatbot-service` | Tách session/message; dùng Search API cho RAG; bỏ truy cập ghi vào bảng thương mại | Chatbot API, schema riêng, RAG trace | Chatbot chạy độc lập; catalog thay đổi được phản ánh qua retrieval | Route về chatbot cũ; giữ session export |
| 5. Tách `recommendation-service` | Chuyển interaction/rule/log; nhận catalog/order/interaction event; cung cấp API gợi ý | Service, schema, model job, evaluation | Không còn repository recommendation trong core; Precision@K/Recall@K đạt gate | Feature flag dùng recommendation cũ/fallback phổ biến |
| 6. Tách `catalog-service` | Di chuyển product/taxonomy/promotion/inventory/wishlist/review; cung cấp sale snapshot và inventory reservation API | Spring Boot service riêng, schema `catalog`, migration và API | Sales không truy cập trực tiếp bảng catalog; concurrency test tồn kho đạt | Dual-read có kiểm soát; route API về core; không dual-write tùy ý |
| 7. Tách `identity-service` | Di chuyển auth/user/address/token; phát user event; các service nhận identity qua JWT claim/reference | Identity service, schema `identity`, key rotation/runbook | Login, refresh, Google login, role check và migration token đạt | Gateway route về auth cũ trong cửa sổ chuyển đổi |
| 8. Đổi core thành `sales-service` | Xóa module đã tách; chuyển sales tables sang schema; giữ SePay ingress, order/payment/refund transaction | Sales service độc lập, schema `sales` | E2E đặt hàng COD/VietQR, webhook trùng, late payment, refund đều đạt | Route về core release trước; restore database theo runbook |
| 9. Gateway và vận hành | Đặt một public entrypoint; rate limit; health/readiness; metrics/log/tracing; deployment manifests | Gateway, dashboards, runbook, SLO | Không public internal API; trace xuyên service; diễn tập lỗi thành công | Route trực tiếp service release ổn định trước đó |
| 10. Dọn shared database | Thu hồi quyền chéo; bỏ FK xuyên service; bỏ bảng/view tạm; chốt ownership | GRANT cuối cùng, migration cleanup, audit report | Mỗi role chỉ ghi schema sở hữu; permission test đạt | Khôi phục grant tạm thời bằng migration có thời hạn |

## 8. Backlog triển khai chi tiết

### 8.1 Công việc chung

- [x] Lập danh sách endpoint hiện tại và service đích.
- [x] Lập dependency graph giữa các module Spring Boot.
- [x] Định nghĩa chuẩn response/error hiện tại; pagination và authentication nội bộ tiếp tục hoàn thiện theo service ở phase tách tương ứng.
- [x] Thêm `traceId`/`correlationId` từ HTTP header đến log, error envelope và catalog event.
- [ ] Chuẩn hóa health, readiness và metrics cho mọi service.
- [x] Thêm contract test cho event Catalog/RabbitMQ; REST contract tiếp tục bổ sung theo service được tách.
- [x] Có feature flag độc lập cho outbox relay và hai indexing consumer; traffic API sẽ chuyển theo từng phase service.
- [ ] Tách cấu hình/secret theo service.
- [ ] Thiết lập CI build/test/image độc lập.
- [x] Ghi runbook rollback/replay và xử lý DLQ cho event platform; runbook deploy/backup riêng tiếp tục theo từng service.

### 8.2 Supabase

- [x] Backup database và kiểm tra restore trước migration đầu tiên ([evidence](microservice-phase-0-backup-restore.md)).
- [ ] Tạo các schema `identity`, `catalog`, `sales`, `ai_search`, `chatbot`, `recommendation`.
- [ ] Tạo role ứng dụng và role migration riêng cho từng service.
- [ ] Thu hồi `CREATE` không cần thiết trên `public`.
- [ ] Định nghĩa search path tường minh cho từng datasource.
- [ ] Đặt pool khởi điểm 2-4 connection/service và theo dõi connection usage.
- [ ] Viết permission test: role không được ghi ngoài schema sở hữu.
- [ ] Không sửa migration V1-V13 đã áp dụng; migration chuyển đổi phải là version mới.
- [ ] Không di chuyển bảng bằng Table Editor; mọi thay đổi phải qua migration có review.

### 8.3 Sales và SePay

- [ ] Giữ nguyên exact-byte HMAC verification và timestamp tolerance của webhook.
- [ ] Giữ unique identity của giao dịch SePay và idempotency webhook/reconciliation.
- [ ] Giữ khóa order/payment trong cùng transaction của `sales-service`.
- [ ] Chỉ trả HTTP thành công sau khi transaction ingestion đã commit.
- [ ] Tách public webhook route khỏi internal API và giới hạn đúng method/path.
- [ ] Thêm E2E cho thanh toán đúng, trùng, sai tiền, hết hạn, tài khoản sai và hoàn tiền.

### 8.4 AI

- [x] Catalog event là nguồn cập nhật index chính; backfill API chỉ dùng cho rebuild.
- [x] Version hóa embedding model và document schema.
- [x] Đảm bảo có thể xóa và tái tạo toàn bộ embedding/read model.
- [ ] Tách DEV/TEST evaluation dataset khỏi dữ liệu demo trình bày.
- [ ] Lưu kết quả Precision@K, Recall@K, MRR và NDCG@K theo model/version.
- [x] Không gửi secret, dữ liệu thanh toán hoặc PII không cần thiết sang model provider.

## 9. Chiến lược di chuyển dữ liệu

Không thực hiện cut-over bằng cách đổi tên/di chuyển tất cả bảng cùng lúc. Với mỗi service:

1. Tạo schema, role và bảng đích bằng migration mới.
2. Backfill dữ liệu có checksum/count đối chiếu.
3. Chạy shadow read hoặc so sánh response giữa nguồn cũ và mới.
4. Bật event đồng bộ phần thay đổi trong thời gian chuyển tiếp.
5. Chuyển read traffic bằng feature flag.
6. Chuyển write authority về service mới tại một thời điểm được kiểm soát.
7. Theo dõi lỗi, độ trễ và sai lệch dữ liệu.
8. Thu hồi quyền ghi nguồn cũ.
9. Chỉ xóa cấu trúc cũ sau một khoảng ổn định và sau backup/restore drill.

Không duy trì dual-write từ application trong thời gian dài. Nếu cần đồng bộ tạm thời, phải có một nguồn ghi chuẩn và cơ chế idempotent có thời hạn.

## 10. Chiến lược repository và triển khai

Giai đoạn chuyển đổi tiếp tục dùng monorepo để giảm chi phí quản lý:

```text
services/
  identity-service/
  catalog-service/
  sales-service/
  search-service/
  chatbot-service/
  recommendation-service/
frontends/
  storefront/
  admin/
contracts/
  rest/
  events/
infra/
  rabbitmq/
  gateway/
  local-compose/
```

Mỗi service phải có Dockerfile, `.env.example`, migration, unit/integration test và README riêng. Không bắt buộc tách thành nhiều Git repository; độc lập triển khai quan trọng hơn độc lập repository.

## 11. Kiểm thử bắt buộc

| Nhóm kiểm thử | Nội dung |
|---|---|
| Unit | Quy tắc nghiệp vụ trong từng service |
| Architecture | Không import repository/entity xuyên service/module |
| Contract | REST/OpenAPI và JSON Schema event tương thích |
| Integration | PostgreSQL/Testcontainers, RabbitMQ thật hoặc container, Redis khi cần |
| Concurrency | Double checkout, giữ tồn, webhook trùng, reconciliation đồng thời |
| Security | JWT, service token, role/GRANT Supabase, webhook signature, secret redaction |
| Migration | Fresh database, upgrade từ V13, checksum/count và rollback rehearsal |
| E2E | Đăng nhập -> tìm sản phẩm -> giỏ hàng -> đặt hàng -> COD/VietQR -> quản trị đơn -> trả/hoàn |
| AI evaluation | Precision@K, Recall@K, MRR, NDCG@K theo tập DEV/TEST khóa phiên bản |
| Resilience | Timeout, service down, message duplicate/out-of-order, DLQ và recovery |

## 12. Tiêu chí một service được xem là đã tách xong

Một service chỉ được đánh dấu hoàn thành khi đáp ứng tất cả điều kiện:

- Build và triển khai độc lập.
- Có API/event contract được version hóa.
- Sở hữu schema và migration riêng.
- Không ghi trực tiếp dữ liệu của service khác.
- Không import source code, JPA entity hoặc repository của service khác.
- Có unit, integration, contract và smoke test.
- Có health/readiness, structured log, metrics và correlation ID.
- Có timeout, retry có giới hạn và idempotency phù hợp.
- Có runbook deploy, rollback và xử lý lỗi.
- Có bằng chứng Supabase role không thể ghi ngoài schema sở hữu.

## 13. Rủi ro và kiểm soát

| Rủi ro | Mức độ | Kiểm soát |
|---|---|---|
| Hết connection Supabase khi tăng số service | Cao | Pool nhỏ, Session Pooler, metric connection, local/Testcontainers cho dev/test |
| Transaction Order-Payment-Inventory bị phân tán | Cao | Giữ Payment trong Sales; dùng reservation API và Saga chỉ cho Inventory |
| Mất hoặc xử lý trùng event | Cao | Transactional outbox, inbox/processed event, idempotency, DLQ |
| Dữ liệu lệch trong cut-over | Cao | Backfill checksum, shadow read, một write authority, feature flag |
| Tăng độ trễ do gọi nhiều API | Trung bình | Sale snapshot, timeout, cache có TTL, tránh chatty API |
| Microservice chỉ là nhiều app cùng sửa một DB | Cao | Schema/role ownership, permission test, cấm repository chéo |
| Scope đồ án quá lớn | Cao | Thứ tự ưu tiên: Search -> Chatbot -> Recommendation -> Catalog -> Identity -> Sales cleanup |
| Debug khó hơn | Trung bình | Correlation ID, structured log, metrics và distributed tracing |
| Lộ PII/financial payload | Cao | Least privilege, redaction, secret separation, audit log |

## 14. Mốc ưu tiên đề xuất

| Ưu tiên | Kết quả cần đạt |
|---|---|
| P0 | Baseline, ownership matrix, contract, outbox/inbox, backup/restore và permission model |
| P1 | Search, Chatbot và Recommendation chạy độc lập, nhận dữ liệu bằng API/event |
| P2 | Catalog/Inventory được tách; Sales không còn truy cập bảng catalog trực tiếp |
| P3 | Identity được tách; JWT/service identity ổn định |
| P4 | Core còn lại trở thành Sales service; dọn quyền và FK xuyên service |
| P5 | Gateway, observability, resilience drill và tài liệu nghiệm thu hoàn chỉnh |

## 15. Kết luận kiến trúc

Kiến trúc đích của dự án gồm `identity-service`, `catalog-service`, `sales-service`, `search-service`, `chatbot-service` và `recommendation-service`. Hệ thống dùng một Supabase PostgreSQL vật lý trong phạm vi đồ án, nhưng tách schema, migration và database role để bảo đảm quyền sở hữu dữ liệu. Các service giao tiếp đồng bộ qua REST khi cần phản hồi tức thời và bất đồng bộ qua RabbitMQ cho đồng bộ dữ liệu dẫn xuất, AI indexing, recommendation và read model.

Việc chuyển đổi phải thực hiện tuần tự. Trong mọi giai đoạn, luồng đặt hàng và thanh toán hiện tại phải tiếp tục hoạt động; không đánh đổi tính đúng đắn tài chính để đạt hình thức microservice.
