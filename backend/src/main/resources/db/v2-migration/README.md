# V2 database migrations

Thư mục này là chuỗi migration chính thức của database V2. Chuỗi migration legacy đã được loại khỏi runtime và chỉ còn trong lịch sử Git.

## Phạm vi hiện có

- `V1`: 1A Identity, taxonomy, catalog, SKU, ảnh Cloudinary và catalog dataset.
- `V2`: seed taxonomy tối thiểu, không có `KIDS`; gồm `FOOTBALL`, `RUNNING`, năm loại sản phẩm và ba hệ size.
- `V3`: 1B cart, order, payment, return/refund, inventory, settings, audit, outbox và interaction.
- `V4`: seed hai thương hiệu kinh doanh ban đầu `NIKE` và `ADIDAS`; tiếp tục giữ `NO_BRAND` cho sản phẩm không thương hiệu.
- `V5`: bổ sung provenance ảnh `REFERENCE` cho dataset tham chiếu giáo dục phi thương mại; không đồng nhất với ảnh `OWNED` hoặc `LICENSED`.

Entity/service của 1A–1B đã được chuyển đổi và kiểm tra bằng Hibernate schema validation cùng integration test. `spring.flyway.locations` mặc định trỏ vào thư mục này. Mỗi migration phải được kiểm thử trên PostgreSQL local sạch trước khi triển khai một lần lên Supabase dùng chung; không chỉnh sửa migration đã áp dụng.

## Kiểm thử local

Tạo một PostgreSQL local trắng với tên bắt đầu bằng `smart_sportswear_v2_1a_`, sau đó chạy:

```powershell
.\mvnw.cmd `
  "-Dtest=V2OneAMigrationTest" `
  "-Dv2.1a.test.url=jdbc:postgresql://127.0.0.1:55432/smart_sportswear_v2_1a_test" `
  "-Dv2.1a.test.username=postgres" `
  test
```

Test có chốt an toàn: từ chối host không phải local và từ chối database không đúng prefix test. Test sẽ `clean` database đích trước khi migrate, kiểm tra ràng buộc nghiệp vụ và dùng Hibernate `validate` để đối chiếu entity 1A với schema thật.
