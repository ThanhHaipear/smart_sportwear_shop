# Microservice migration - Phase 1 boundary hardening

Ngày cập nhật: 09/11/2026

Trạng thái: `IN_PROGRESS`

## Mục tiêu

Phase 1 loại dần truy cập trực tiếp `entity` và `repository` xuyên bounded context, thay bằng API/port tường minh trước khi tách process hoặc dữ liệu vật lý.

Phase này không tạo schema/role, không di chuyển bảng và không thay đổi dữ liệu Supabase production.

## Boundary đang áp dụng

| Bounded context | Module |
|---|---|
| Identity | `address`, `auth`, `user` |
| Catalog | `catalog`, `category`, `brand`, `product`, `collection`, `promotion`, `combo`, `inventory`, `wishlist`, `review`, `banner`, `page`, `setting` |
| Sales | `cart`, `checkout`, `order`, `payment`, `shipping`, `returns`, `notification`, `audit`, `report` |
| Recommendation | `recommendation`, `interaction` |

## Lát cắt 1 - Checkout sang Identity

Trước thay đổi, `CheckoutPreviewService` import trực tiếp `AddressRepository` rồi đọc quan hệ JPA `Address.user` để kiểm tra quyền sở hữu. Điều này làm Sales phụ thuộc persistence nội bộ của Identity.

Sau thay đổi:

- Identity công bố `AddressOwnershipQuery` trong package `address.api`.
- `AddressService` triển khai contract và giữ toàn bộ truy cập `AddressRepository` bên trong Identity.
- Checkout chỉ gọi `requireOwnedAddress(userId, addressId)` và không biết entity/repository Identity.
- Architecture test khóa hai quy tắc đầu tiên:
  - Identity không import entity/repository của bounded context khác.
  - Checkout không import entity/repository Identity.
- `BoundedContextArchitectureTest` được đưa vào baseline verification script.

## Bằng chứng kiểm thử

| Kiểm thử | Kết quả |
|---|---|
| Backend unit baseline có architecture gate | 78 test, 0 failure/error |
| `BoundedContextArchitectureTest` | 2 test, 0 failure/error |
| `OrderIntegrationTest` current-runtime slice | 24 test, 0 failure/error; gồm ca từ chối địa chỉ thuộc người dùng khác |

## Việc tiếp theo

1. Loại truy cập `AddressRepository` trực tiếp còn lại trong `OrderService` và Shipping bằng Identity API/snapshot phù hợp.
2. Mở rộng architecture rule từ Checkout sang toàn bộ Sales -> Identity.
3. Sau đó xử lý các chiều Sales -> Catalog và Recommendation -> Catalog/Sales theo mức coupling trong baseline.

## Exit gate Phase 1

- [x] Có architecture test chạy trong baseline.
- [ ] Không còn import repository/entity xuyên Identity, Catalog, Sales và Recommendation nếu chưa có ngoại lệ được duyệt.
- [ ] Các luồng login, catalog, checkout, COD, VietQR/SePay và refund vẫn đạt smoke/integration gate.
- [ ] Correlation ID và error contract nội bộ được chuẩn hóa.
