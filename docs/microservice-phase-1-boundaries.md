# Microservice migration - Phase 1 boundary hardening

Ngày cập nhật: 09/11/2026

Trạng thái: `DONE`

## Mục tiêu và phạm vi

Phase 1 làm cứng ranh giới giữa bốn bounded context hiện có trước khi tách process hoặc dữ liệu vật lý:

| Bounded context | Module |
|---|---|
| Identity | `address`, `auth`, `user` |
| Catalog | `banner`, `brand`, `catalog`, `category`, `collection`, `combo`, `inventory`, `page`, `product`, `promotion`, `review`, `setting`, `wishlist` |
| Sales | `audit`, `cart`, `checkout`, `notification`, `order`, `payment`, `report`, `returns`, `shipping` |
| Recommendation | `interaction`, `recommendation` |

Phase này không tạo schema/role, không di chuyển bảng và không thay đổi dữ liệu Supabase production.

## Kết quả boundary

### Identity API

- `AddressOwnershipQuery` và `AddressSnapshot` thay thế việc Checkout, Order và Shipping đọc trực tiếp `AddressRepository`/`Address`.
- `UserReferenceQuery` thay thế việc Cart, Order, Wishlist và Notification đọc trực tiếp `UserRepository`.
- Persistence của Identity chỉ còn được truy cập bên trong Identity.

### Catalog API

- `ProductReferenceQuery` cung cấp các phép đọc Product/Variant/Image đã được duyệt cho Sales và Recommendation.
- `ProductReferenceService` giữ các repository Catalog ở phía provider.
- `SalesProductUsage` cho Catalog kiểm tra lịch sử đơn và xóa cart item qua API của Sales, thay cho import repository Sales.

### Sales API

- `CartReferenceQuery` cung cấp cart và cart item cho Recommendation.
- `SalesProductUsageService` là adapter sở hữu truy cập `CartItemRepository` và `OrderItemRepository`.
- Shipping vẫn là module dormant; Phase 1 không tự ý kích hoạt component hoặc persistence scan của Shipping.

### Recommendation

- `RecommendationService` và `RecommendationLogService` chỉ dùng `ProductReferenceQuery` và `CartReferenceQuery`.
- Không còn import repository Catalog/Sales trực tiếp.

## Architecture gate

`BoundedContextArchitectureTest` chạy trong baseline và áp dụng cho toàn bộ Identity, Catalog, Sales và Recommendation:

- Import repository xuyên bounded context: **0**.
- Các dependency entity/JPA chuyển tiếp còn lại: **84**, được duyệt và khóa bằng snapshot chính xác.
- SHA-256 snapshot: `76ddb12967a3bb0e05f8fc3a3f52c70223505c333aaf1c84c4496ca414964d2a`.
- Mọi dependency entity mới, bị xóa hoặc thay đổi đều làm architecture test thất bại và buộc phải review.

Phân bố 84 ngoại lệ chuyển tiếp:

| Hướng dependency | Số lượng |
|---|---:|
| Sales -> Catalog | 20 |
| Recommendation -> Catalog | 17 |
| Sales -> Identity | 16 |
| Catalog -> Sales | 12 |
| Catalog -> Identity | 8 |
| Recommendation -> Sales | 7 |
| Recommendation -> Identity | 4 |

Các ngoại lệ này là quan hệ JPA cũ đã biết, không phải quyền tạo dependency mới. Chúng sẽ được loại bỏ khi tách schema/process ở các phase sau.

## Correlation ID và error contract

- Mọi HTTP request đi qua `CorrelationIdFilter`.
- Header chuẩn là `X-Correlation-ID`; giá trị hợp lệ từ client được giữ lại, nếu thiếu hoặc không an toàn thì tạo UUID.
- Cùng một ID được trả trong response header, đặt vào MDC `traceId`, xuất hiện trong log và được `CatalogOutboxService` đưa vào event payload.
- Error envelope chuẩn có trường `traceId` để đối chiếu log.
- Success envelope giữ `traceId = null`; correlation vẫn có trong header. Cách này giữ tương thích response và bảo đảm hai response forgot-password vẫn giống hệt nhau để chống dò email.
- CORS expose `X-Correlation-ID` cho storefront/admin.

## Bằng chứng kiểm thử

| Gate | Kết quả |
|---|---|
| Backend unit baseline | 84 test, 0 failure/error |
| `BoundedContextArchitectureTest` | 6 test, 0 failure/error |
| Backend current-runtime integration | 361 test, 0 failure/error |
| Phase smoke: login, catalog, COD, VietQR, SePay concurrent delivery | 5 test, 0 failure/error |
| Visual Search | 127 passed, 2 skipped |
| Chatbot và Product Search | 242 passed |
| Storefront/Admin lint | Đạt |
| Storefront/Admin production build | Đạt |

Integration gate đã bao phủ thêm error/correlation contract, checkout/order, payment và return/refund. Toàn bộ database integration sử dụng PostgreSQL Testcontainers tạm thời, không ghi vào Supabase dùng chung.

## Exit gate Phase 1

- [x] Architecture test chạy trong baseline.
- [x] Không còn import repository xuyên bounded context.
- [x] Entity/JPA exception cũ có danh sách duyệt và snapshot chống phát sinh âm thầm.
- [x] Login, catalog, checkout, COD, VietQR/SePay và refund đạt smoke/integration gate.
- [x] Correlation ID, log context, event trace và error contract đã chuẩn hóa.
- [x] Không thay đổi schema hoặc dữ liệu Supabase production.

Phase 1 hoàn tất. Công việc tiếp theo là Phase 2: chuẩn hóa event platform, contract v1, retry/DLQ và consumer idempotency.
