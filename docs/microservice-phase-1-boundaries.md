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

## Lát cắt 2 - Order sang Identity

Trước thay đổi, `OrderService` đọc trực tiếp `AddressRepository` và chuyển entity `Address` thành JSON snapshot. Điều này gắn transaction tạo đơn với persistence model của Identity.

Sau thay đổi:

- Identity công bố record bất biến `AddressSnapshot` qua `address.api`.
- `AddressOwnershipQuery.getOwnedAddress` trả snapshot chỉ sau khi xác nhận địa chỉ thuộc đúng người dùng.
- `OrderService` chỉ nhận contract API và tự lưu bản chụp vào `orders.address_snapshot_json` như trước.
- Architecture test khóa Order không được import `address.entity` hoặc `address.repository`.
- Không thay đổi bảng, migration hay dữ liệu Supabase.

## Bằng chứng kiểm thử

| Kiểm thử | Kết quả |
|---|---|
| Backend unit baseline có architecture gate | 79 test, 0 failure/error |
| `BoundedContextArchitectureTest` | 3 test, 0 failure/error |
| `OrderIntegrationTest` current-runtime slice | 24 test, 0 failure/error; gồm ca từ chối địa chỉ thuộc người dùng khác |

## Việc tiếp theo

1. Loại truy cập `AddressRepository` trực tiếp còn lại trong Shipping bằng Identity API/snapshot phù hợp.
2. Loại dần phụ thuộc `User` entity/repository trong Sales trước khi mở rộng rule sang toàn bộ Sales -> Identity.
3. Sau đó xử lý các chiều Sales -> Catalog và Recommendation -> Catalog/Sales theo mức coupling trong baseline.

## Exit gate Phase 1

- [x] Có architecture test chạy trong baseline.
- [ ] Không còn import repository/entity xuyên Identity, Catalog, Sales và Recommendation nếu chưa có ngoại lệ được duyệt.
- [ ] Các luồng login, catalog, checkout, COD, VietQR/SePay và refund vẫn đạt smoke/integration gate.
- [ ] Correlation ID và error contract nội bộ được chuẩn hóa.
