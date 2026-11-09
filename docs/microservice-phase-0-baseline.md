# Microservice migration - Phase 0 baseline

Ngày ghi nhận: 09/11/2026

Git baseline: `da5205f` (`main`)

Trạng thái: `DONE`

## 1. Mục đích

Phase 0 khóa hiện trạng trước khi thay đổi ranh giới runtime hoặc dữ liệu. Tài liệu này là mốc đối chiếu để phát hiện regression trong các phase tách service tiếp theo.

Không có bảng Supabase, API public hoặc luồng SePay nào bị thay đổi trong phase này.

## 2. Thành phần runtime hiện tại

| Thành phần | Công nghệ | Trạng thái kiến trúc | Service đích |
|---|---|---|---|
| `backend` | Spring Boot, JPA, Flyway | Modular monolith, 26 package nghiệp vụ nhưng một số package bị loại khỏi runtime; một datasource | Tách dần thành Identity, Catalog và Sales |
| `visual-search-service` | Python | Process độc lập, dùng schema AI/read model | `search-service` |
| `chatbot-service` | Python | Process độc lập; hiện chứa cả chatbot và product search/indexer | Tách Search concern khỏi `chatbot-service` |
| `frontend/storefront` | Next.js | Client public | Đi qua Gateway/BFF |
| `frontend/admin` | Next.js | Client quản trị | Đi qua Gateway/BFF |
| RabbitMQ | RabbitMQ 3.13 | Catalog event/outbox đã tồn tại | Event backbone dùng chung |
| Redis | Redis 7 | Cache và pub/sub tùy cấu hình | Hạ tầng dùng chung |
| Supabase | PostgreSQL + pgvector | Một database vật lý; `public` và `ai_retrieval` | Một project, schema/role theo service |

## 3. Kết quả build và test baseline

Chạy lại baseline ổn định bằng:

```powershell
.\scripts\verify-microservice-baseline.ps1
```

Thêm `-IncludeBackendIntegration` khi Docker hoạt động để chạy current-runtime integration gate. Gate loại các test mang tag `phase0-excluded`; các test này vẫn được giữ lại để audit module dormant/legacy và quyết định schema ở phase sau.

Chạy smoke baseline nghiệp vụ bằng:

```powershell
.\scripts\verify-microservice-smoke.ps1
```

| Hạng mục | Lệnh | Kết quả ngày 09/11/2026 | Trạng thái |
|---|---|---|---|
| Backend package | `backend\\mvnw.cmd -DskipTests package` | Build JAR thành công | PASS |
| Backend unit subset | 17 test class không cần container | 76 test, 0 failure/error | PASS |
| Backend integration audit | Testcontainers, loại ba migration rehearsal test, không loại tag dormant | 418 test; 47 failure, 23 error, 0 skipped; giảm từ baseline đầu tiên 160 failure/37 error | DORMANT/LEGACY DEBT RECORDED |
| Backend current-runtime integration gate | Testcontainers, loại ba migration rehearsal test và tag `phase0-excluded` | 351 test, 0 failure/error, 0 skipped | PASS |
| Cart integration debt slice | `backend\\mvnw.cmd -Dtest=CartIntegrationTest test` | 23 test, 0 failure/error; fixture tuân thủ publishability V2 | PASS |
| Order integration current-runtime slice | `OrderIntegrationTest` loại 3 ca Combo dormant | 23 ca runtime hiện hành đạt; 3 ca Combo trả 404 do module bị loại khỏi runtime | PASS WITH DOCUMENTED EXCLUSION |
| Inventory + Return/Refund current-runtime slice | `InventoryIntegrationTest,ReturnRefundIntegrationTest` | 31 test, 0 failure/error; fixture tuân thủ publishability V2 | PASS |
| Product Catalog current-runtime slice | `ProductCatalogIntegrationTest` | 15 test, 0 failure/error; duplicate SKU được ánh xạ ổn định thành 409 | PASS |
| Product Search current-runtime slice | `ProductSearchIntegrationTest` | 14 test, 0 failure/error | PASS |
| Admin Product current-runtime slice | `AdminProductListIntegrationTest` | 6/7 ca đạt; riêng filter `featured=true` chưa thể hoạt động vì `Product.featured` là `@Transient` và V1-V13 không có cột tương ứng | PASS WITH DOCUMENTED EXCLUSION |
| Sales Report current-runtime slice | `ReportIntegrationTest` | 9 test, 0 failure/error; COD delivered được đối chiếu theo payment semantics hiện hành | PASS |
| Notification Resend current-runtime slice | `NotificationResendIntegrationTest` | 8 test, 0 failure/error; fixture order/product tuân thủ publishability V2 | PASS |
| Product Image Upload current-runtime slice | `ProductImageUploadIntegrationTest` | 7 test, 0 failure/error; quản lý ảnh được kiểm tra khi product còn ở lifecycle `DRAFT` | PASS |
| Category current-runtime slice | `CategoryIntegrationTest` | 10 test, 0 failure/error; taxonomy hai tầng và giới hạn `code` hiện hành được phản ánh đúng trong fixture | PASS |
| Google Login current-runtime slice | `GoogleLoginIntegrationTest` | 9 test, 0 failure/error; mỗi fixture có Google `provider_subject` riêng theo identity schema 1A | PASS |
| Notification Template current-runtime slice | `NotificationTemplateIntegrationTest` | 8 test, 0 failure/error; product fixture tuân thủ lifecycle publishability V2 | PASS |
| Site Setting current-runtime slice | `SiteSettingIntegrationTest` | 7 test, 0 failure/error; key fixture tuân thủ constraint lowercase | PASS |
| Product Detail current-runtime slice | 2 ca không phụ thuộc Review trong `ProductDetailCompletionIntegrationTest` | 2 test, 0 failure/error; ca review summary được loại vì Review đang dormant và route trả 404 | PASS WITH DOCUMENTED EXCLUSION |
| Business smoke baseline | `scripts\verify-microservice-smoke.ps1` | 5 test đại diện: login, catalog public detail, tạo đơn COD, tạo/resume VietQR, webhook SePay đồng thời/idempotent | PASS |
| Visual Search | `python -m pytest -q` | 127 passed, 2 skipped | PASS |
| Chatbot/Product Search | `.venv\\Scripts\\python.exe -m pytest -q` | 242 passed | PASS |
| Storefront lint | `npm.cmd run lint` | Thành công | PASS |
| Storefront production build | `npm.cmd run build` | Thành công; cần network để tải Google Font lúc build | PASS |
| Admin lint | `npm.cmd run lint` | Thành công | PASS |
| Admin production build | `npm.cmd run build` | Thành công; cần network để tải Google Font lúc build | PASS |

Ghi chú:

- Chạy toàn bộ `mvnw test` trong sandbox thất bại do không truy cập được Docker daemon, không phải do assertion nghiệp vụ.
- Ba lớp `V2OneAMigrationTest`, `V2OneBMigrationTest`, `V2OneCMigrationTest` cố ý yêu cầu URL database local chuyên dụng. Chúng thuộc database rehearsal, không được trỏ vào Supabase chung.
- Chạy Chatbot bằng Python global thất bại vì thiếu dependency; baseline hợp lệ sử dụng `.venv` đã khóa của service.
- Khi chạy bằng Docker thật, integration suite khởi tạo thành công PostgreSQL/pgvector và áp dụng Flyway V1-V13, sau đó bộc lộ test debt có sẵn. Nhóm lỗi lớn nhất là fixture tạo product `ACTIVE` khi chưa có primary image hợp lệ; các test shipping/recommendation/seed còn kỳ vọng bean của package đang bị runtime loại khỏi component/persistence scan.
- Đã xử lý lát cắt Cart của test debt: test tạo product ở trạng thái `DRAFT`, tạo variant và primary image test-only có đủ metadata rồi mới chuyển sang `ACTIVE`. `CartIntegrationTest` hiện đạt 23/23; helper chỉ ghi vào PostgreSQL Testcontainers dùng một lần, không chạm Supabase.
- Đã áp dụng cùng fixture hợp lệ cho Order: 23 ca thuộc runtime hiện hành đạt. Ba ca Combo vẫn bị loại khỏi gate tạm thời vì `/api/v1/admin/combos` không tồn tại trong 128 route runtime; đây là quyết định dormant/legacy cần chốt, không phải lỗi dữ liệu Supabase.
- Đã ổn định thêm Inventory và Return/Refund (31/31), Product Catalog (15/15), Product Search (14/14), cùng 6/7 ca Admin Product. Fixture đều tạo `DRAFT`, variant bán được và primary image test-only trước khi kích hoạt; chỉ ghi vào PostgreSQL Testcontainers.
- Ca Admin Product lọc `featured=true` được theo dõi riêng: entity đánh dấu `featured` là `@Transient`, trong khi migration V1-V13 không có cột lưu trạng thái. Sửa đúng cần một quyết định schema/migration ở phase sau; Phase 0 không tự ý đổi Supabase.
- `ProductVariantService` đã flush lúc tạo variant để lỗi unique SKU được bắt trong transaction service và trả về business conflict 409, thay vì rơi ra 500 lúc commit.
- Lần chạy full suite gần nhất còn 47 failure và 23 error. Toàn bộ 23 error thuộc Shipping/Seed dormant. Failure được phân nhóm thành Collection (15), Review (11), Banner (7), Page (5), Product Detail phụ thuộc Review (3 tại thời điểm full run), Combo trong Order (3), cùng một ca ở Notification Template, Admin Product featured và Site Setting. Sau lần full này, Notification Template đạt 8/8, Site Setting đạt 7/7 và hai ca Product Detail không phụ thuộc Review đạt 2/2; ca Product Detail review summary còn lại được xác nhận trả 404 vì Review bị loại khỏi runtime.
- Smoke baseline chỉ dùng PostgreSQL/pgvector Testcontainers và cấu hình provider test; không gọi Supabase production, ngân hàng hoặc webhook SePay thật.
- Lát cắt Sales Report đạt 9/9 sau khi fixture tuân thủ publishability V2 và kỳ vọng `codDeliveredUnpaid` được đồng bộ với quy tắc: đơn COD chuyển `DELIVERED` đồng thời ghi nhận thu tiền (`PAID`).
- Lát cắt Notification Resend đạt 8/8 sau khi product fixture chỉ kích hoạt sau khi có variant và primary image test-only hợp lệ.
- Lát cắt Product Image Upload đạt 7/7 khi fixture giữ product ở `DRAFT` trong lúc quản lý ảnh, đúng lifecycle trước khi publish.
- Lát cắt Category đạt 10/10: slug ngẫu nhiên không còn làm `code` vượt `varchar(50)`, và test độ sâu kiểm tra trực tiếp root -> child -> từ chối grandchild theo taxonomy hai tầng của schema 1A.
- Lát cắt Google Login đạt 9/9 sau khi mỗi Google identity test dùng `provider_subject` riêng; điều này khớp nguồn định danh `(provider, provider_subject)` trong `user_identities`.
- Lát cắt Notification Template đạt 8/8 và Site Setting đạt 7/7 sau khi fixture tuân thủ lần lượt publishability V2 và constraint lowercase của `site_settings.setting_key`.
- Hai ca Product Detail thuần Catalog đạt 2/2. Ca review summary được theo dõi cùng module Review dormant vì `/api/v1/products/{id}/reviews` không tồn tại trong 128 route runtime hiện hành.
- Runtime hiện tại chủ động loại các package `banner`, `collection`, `combo`, `page`, `promotion`, `recommendation`, `review`, `shipping` và `infra.seed` khỏi component scan. Chúng phải được phân loại là dormant/legacy hoặc được kích hoạt bằng migration/schema đầy đủ trước khi tính vào service đích.
- Current-runtime gate dùng tag tổng `phase0-excluded`. Các lớp dormant có thêm tag `dormant`; riêng ba ca Combo trong Order và ca Product Detail phụ thuộc Review được gắn tag ở cấp phương thức. Ca Admin Product `featured=true` có tag `schema-decision`. Việc gắn tag không xóa test và không che lỗi của module đang hoạt động.

## 4. Module ownership baseline

| Bounded context đích | Module hiện tại | Ghi chú chuyển đổi |
|---|---|---|
| Identity | `auth`, `user`, `address` | Tách sau Catalog để giảm số consumer phải đổi cùng lúc |
| Catalog | `catalog`, `category`, `brand`, `product`, `collection`, `promotion`, `combo`, `inventory`, `wishlist`, `review`, `banner`, `page`, `setting` | Inventory ở cùng Catalog trong giai đoạn đầu |
| Sales | `cart`, `checkout`, `order`, `payment`, `shipping`, `returns`, `notification`, `audit`, `report` | Giữ Payment/SePay/Refund trong cùng transaction boundary |
| Search | Spring adapter `product.search`, `visual-search-service`, product search/indexer trong `chatbot-service` | Semantic + Image Search dùng chung read model/index lifecycle |
| Chatbot | Phần chat/RAG trong `chatbot-service` | Chỉ gọi Search/Catalog API, không sở hữu catalog |
| Recommendation | `recommendation`, `interaction` | Thay entity reference bằng ID/snapshot/event |

## 5. Phụ thuộc chéo giữa module Spring

Phân tích import tĩnh ghi nhận **69 cặp phụ thuộc chéo**. Các cặp lớn nhất:

| Nguồn | Đích | Số import | Hướng xử lý |
|---|---:|---:|---|
| Catalog module | Product | 23 | Gom cùng Catalog boundary trước khi tách process |
| Recommendation | Product | 17 | Thay bằng product snapshot/contract |
| Recommendation | Cart | 16 | Thay bằng interaction/cart context contract |
| Returns | Order | 14 | Giữ cùng Sales boundary |
| Inventory | Product | 14 | Giữ cùng Catalog boundary |
| Order | Payment | 14 | Giữ cùng Sales boundary |
| Auth | User | 13 | Giữ cùng Identity boundary |
| Product | Catalog module | 12 | Gom cùng Catalog boundary |
| Cart | Product | 12 | Thay bằng `SaleProductSnapshotPort` |
| Payment | Order | 11 | Giữ cùng Sales boundary |
| Order | Product | 9 | Thay entity bằng immutable order-item snapshot |
| Report | Order | 9 | Giai đoạn đầu giữ trong Sales; sau đó dùng read model |
| Collection | Product | 9 | Gom cùng Catalog boundary |
| Payment | Returns | 6 | Giữ cùng Sales boundary |
| Wishlist | Product | 6 | Gom cùng Catalog boundary |

Quy tắc cho Phase 1:

1. Không cố xóa mọi import chéo trong cùng bounded context.
2. Ưu tiên xóa import chéo **giữa** Identity, Catalog, Sales và Recommendation.
3. Không thay JPA relation bằng remote call trong entity lifecycle.
4. Tạo port/facade và DTO snapshot trước; sau đó mới tách process.

## 6. API inventory theo service đích

| Service đích | API family hiện tại | Quyết định |
|---|---|---|
| Identity | `/api/v1/auth`, `/api/v1/me`, `/api/v1/me/addresses`, `/api/v1/admin/users` | Giữ URL public qua Gateway; backend route đổi nội bộ |
| Catalog | `/api/v1/products`, `/api/v1/categories`, `/api/v1/brands`, `/api/v1/collections`, `/api/v1/promotions`, `/api/v1/wishlist`, `/api/v1/reviews`, `/api/v1/banners`, `/api/v1/pages` và các route admin tương ứng | Chuyển nguyên contract sang Catalog trước khi tối ưu API |
| Catalog inventory | `/api/v1/admin/inventory`, `/api/v1/inventory/stream` | Bổ sung internal reservation API trước khi Sales bị tách |
| Sales | `/api/v1/cart`, `/api/v1/checkout`, `/api/v1/orders`, `/api/v1/payments`, `/api/v1/webhooks/sepay`, `/api/v1/returns`, `/api/v1/admin/orders`, `/api/v1/admin/refunds`, `/api/v1/admin/shipments` | Giữ contract và transaction semantics |
| Notification | `/api/v1/notifications`, `/api/v1/admin/notifications`, `/api/v1/admin/notification-templates` | Giai đoạn đầu thuộc Sales |
| Reporting | `/api/v1/admin/reports` | Giai đoạn đầu thuộc Sales/read model |
| Search | `/api/v1/products/hybrid-search`, `/api/v1/products/search-by-image`, internal search endpoints | Public URL giữ qua Gateway; xử lý chuyển sang Search |
| Recommendation | `/api/v1/recommendations/*`, `/api/v1/products/{id}/frequently-bought-together`, admin recommendation endpoints | Chuyển sang Recommendation service |

## 7. Database ownership baseline

| Owner đích | Nguồn hiện tại | Bảng/nhóm dữ liệu chính |
|---|---|---|
| Identity | `public` | `users`, `user_identities`, `addresses`, `refresh_tokens`, `password_reset_tokens` |
| Catalog | `public` | taxonomy, category, brand, dataset, product, variant, image, promotion, inventory, wishlist, review và CMS catalog |
| Sales | `public` | cart, order, order history, payment, payment event, bank transaction/reconciliation, return, refund, shipment, notification, audit |
| Search | `ai_retrieval` | model registry, search documents/facets, text/image embedding, processed event, indexing job, usage event |
| Chatbot | Chưa có schema ownership hoàn chỉnh | Chat session/message và retrieval trace cần migration riêng |
| Recommendation | `public` và một phần `ai_retrieval` | interaction, association rule/log, recommendation model/request/item |

Hiện trạng cần xử lý:

- `public` đang chứa dữ liệu của nhiều owner.
- JPA relation và foreign key còn đi xuyên ranh giới đích.
- Các Python service đang dùng chung connection information từ backend launcher.
- Role riêng và permission test cho từng service chưa tồn tại.

## 8. Contract và event baseline

| Thành phần | Hiện trạng | Việc kế tiếp |
|---|---|---|
| Catalog event | Có `contracts/catalog-event-v1.schema.json` | Giữ backward compatibility; bổ sung contract test producer/consumer |
| Transactional outbox | Có `integration_outbox` và relay catalog | Tổng quát hóa thành event platform nhưng không đổi semantics hiện tại |
| Consumer deduplication | Search đã có `processed_events` | Chuẩn hóa inbox/idempotency cho service khác |
| Sales event | Chưa có contract chuẩn cho order/payment/refund | Thiết kế ở Phase 2, chưa phát production trong Phase 0/1 |
| DLQ/retry | RabbitMQ có cấu hình phục vụ visual search | Kiểm kê và chuẩn hóa tên exchange/queue/routing key |

## 9. Backup và rollback evidence

| Evidence | Phạm vi | Trạng thái |
|---|---|---|
| Backup/TOC/checksum/restore drill ngày 25/10/2026 | Flyway V8 và AI Retrieval | Có bằng chứng lịch sử |
| Migration rehearsal V1-V13 | PostgreSQL/pgvector 17 local chuyên dụng | 16 test, 0 failure/error; Flyway clean/migrate/validate/idempotency đạt | PASS |
| [Backup/restore hiện hành trước microservice migration](microservice-phase-0-backup-restore.md) | Supabase V13, schema `public` và `ai_retrieval` | PASS ngày 09/11/2026; archive mã hóa, SHA-256/TOC và restore PostgreSQL 17 đã kiểm tra |

Không được bắt đầu di chuyển bảng hoặc thay đổi GRANT trên Supabase cho đến khi backup V13 hiện hành được mã hóa, kiểm tra checksum/TOC và restore thử vào database tạm.

## 10. Phase 0 exit gate

- [x] Chốt Git baseline và kiến trúc runtime hiện tại.
- [x] Lập module ownership matrix.
- [x] Lập API family inventory.
- [x] Lập database ownership matrix.
- [x] Ghi nhận dependency graph và các coupling lớn nhất.
- [x] Backend unit, Python service, frontend lint/build đạt.
- [x] Backend current-runtime integration gate đạt 351/351 khi có Docker; không tính ba migration rehearsal test và 67 test `phase0-excluded` đã được phân loại rõ.
  - [x] Lát cắt Cart integration đạt 23/23 với fixture hợp lệ theo publishability V2.
  - [x] Lát cắt Order runtime hiện hành đạt 23/23; còn 3 ca Combo dormant được theo dõi riêng.
  - [x] Lát cắt Inventory + Return/Refund runtime hiện hành đạt 31/31.
  - [x] Lát cắt Product Catalog và Product Search runtime hiện hành đạt 29/29.
  - [x] Lát cắt Admin Product đạt 6/7; filter featured chờ quyết định migration/schema.
  - [x] Lát cắt Sales Report runtime hiện hành đạt 9/9.
  - [x] Lát cắt Notification Resend runtime hiện hành đạt 8/8.
  - [x] Lát cắt Product Image Upload runtime hiện hành đạt 7/7.
  - [x] Lát cắt Category runtime hiện hành đạt 10/10.
  - [x] Lát cắt Google Login runtime hiện hành đạt 9/9.
  - [x] Lát cắt Notification Template runtime hiện hành đạt 8/8.
  - [x] Lát cắt Site Setting runtime hiện hành đạt 7/7.
  - [x] Hai ca Product Detail không phụ thuộc Review đạt 2/2; ca review summary được theo dõi cùng Review dormant.
- [x] Ba migration test đạt trên PostgreSQL/pgvector 17 local chuyên dụng (16/16 test).
- [x] Tạo backup Supabase V13 hiện hành và restore drill thành công.
- [x] Chụp smoke baseline cho đăng nhập, catalog, COD và VietQR/SePay (5/5 test, có script tái lập).

Phase 0 đã đạt exit gate. Phase 1 có thể bắt đầu cứng hóa modular boundary; chưa tạo schema/role hoặc di chuyển bảng Supabase cho đến phase tương ứng trong kế hoạch.
