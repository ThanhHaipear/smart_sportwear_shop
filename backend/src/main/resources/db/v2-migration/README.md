# V2 database migrations

Thư mục này là baseline mới, tách biệt hoàn toàn với `db/migration` của hệ thống legacy.

## Phạm vi hiện có

- `V1`: 1A Identity, taxonomy, catalog, SKU, ảnh Cloudinary và catalog dataset.
- `V2`: seed taxonomy tối thiểu, không có `KIDS`; gồm `FOOTBALL`, `RUNNING`, năm loại sản phẩm và ba hệ size.

Entity/service của 1A đã được chuyển đổi và kiểm tra bằng Hibernate schema validation. Không đổi `spring.flyway.locations` của ứng dụng sang thư mục này cho đến khi 1B cũng được chuyển đổi và migration của đợt cần chạy đã được duyệt, vì các luồng Commerce hiện vẫn dùng schema legacy. Không chạy thử trực tiếp trên Supabase dùng chung.

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
