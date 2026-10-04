# Smart Sportswear Shop Backend

Backend Spring Boot cho sản phẩm web kinh doanh trang phục thể thao. Đã đi qua Phase 1 (auth, catalog, cart, order, payment, inventory, admin, report) và các phase mở rộng N1-N5: wishlist, product reviews, promotions/coupons, notification, product search/discovery, PDP completion, seed data và docs hoàn thiện hơn cho frontend.

## Stack

- Java 21
- Spring Boot 3.5.15
- Spring Security + JWT
- Spring Data JPA
- PostgreSQL
- Redis
- Flyway
- Springdoc OpenAPI
- Spring Boot Actuator
- Maven

## Module đã có

- `auth`: register, login, refresh, logout, forgot/reset password
- `user`: `/me`, admin user management
- `address`: địa chỉ giao hàng của user
- `category`, `brand`, `product`: catalog, tìm kiếm/lọc/sắp xếp, search suggestion, PDP (review summary + related products), upload ảnh sản phẩm thật qua Cloudinary
- `cart`: guest cart theo `session_id`, merge sau login/register
- `order`: checkout (có thể áp coupon), order history, customer cancel, admin status update
- `payment`: tạo session VNPay 2.1.0 (sandbox mặc định), callback/IPN có checksum, đối soát merchant và số tiền
- `inventory`: tồn kho hiện tại, import/export/adjust, transaction log
- `report`: overview, order report, product report, inventory report
- `wishlist`: danh sách yêu thích của user đã đăng nhập
- `review`: đánh giá sản phẩm (chỉ được đánh giá sản phẩm đã mua/đã giao), duyệt review qua admin
- `promotion`, `coupon`: khuyến mãi + áp mã giảm giá khi checkout
- `notification`: lịch sử email/notification (order created/cancelled/delivered, forgot password), admin xem toàn bộ, user xem của chính mình
- `seed`: dữ liệu demo để chạy local/demo nhanh (category/brand/product/variant/order/review/promotion/coupon)

## Search & Discovery (product listing)

`GET /api/v1/products` hỗ trợ:

- `q` (hoặc `keyword`): tìm theo tên/mô tả ngắn
- `categoryId`/`categorySlug`, `brandId`/`brandSlug`: lọc theo category/brand (id hoặc slug đều dùng được; slug không tồn tại trả về danh sách rỗng, không lỗi)
- `minPrice`, `maxPrice`: lọc theo khoảng giá (validate sạch nếu min > max hoặc giá âm)
- `sort`: `newest` (mặc định), `price_asc`, `price_desc`, `bestselling` (dựa trên số lượng đã bán thật từ `order_items`, loại trừ đơn `CANCELLED`)
- `page`, `limit`: phân trang chuẩn (1-indexed ở response `meta`)

`GET /api/v1/products/search-suggestions?q=...`: gợi ý nhanh cho thanh search (gọn nhẹ, không AI/fuzzy).

## Mail / SMTP

Mọi email (xác nhận đơn, hủy đơn, giao hàng thành công, quên mật khẩu) đi qua một abstraction duy nhất - `MailService` - nên business code (`NotificationService`, `PasswordResetService`) không biết và không cần biết email được gửi bằng cách nào. Chọn provider bằng một biến môi trường:

- `APP_MAIL_PROVIDER=logging` (mặc định) - `LoggingMailService`, chỉ log ra console, không gửi gì thật. An toàn cho local/dev/test, không cần SMTP server.
- `APP_MAIL_PROVIDER=smtp` - `SmtpMailService`, gửi email thật qua `JavaMailSender` (cấu hình chuẩn Spring Boot `spring.mail.*`).
- Giá trị khác/sai (`APP_MAIL_PROVIDER=typo`): không bean nào khớp `@ConditionalOnProperty`, app **không start được** - tránh tình trạng âm thầm rơi vào provider sai.

Khi dùng `smtp`, các biến sau là bắt buộc tối thiểu (app sẽ fail fast lúc start nếu thiếu, xem `SmtpMailService`):

- `MAIL_FROM`: địa chỉ gửi đi (không được để trống)
- `MAIL_HOST`: SMTP host (không được để trống)

Các biến SMTP còn lại (`MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS_ENABLE`) tùy theo provider thật mà bạn dùng.

### Cấu hình Gmail SMTP để test local

Gmail yêu cầu **App Password** (16 ký tự), không dùng được password đăng nhập thường, và phải bật 2-Step Verification trước. Tạo App Password tại: Google Account → Security → 2-Step Verification → App passwords.

```dotenv
APP_MAIL_PROVIDER=smtp
MAIL_FROM=your-gmail-address@gmail.com
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-gmail-address@gmail.com
MAIL_PASSWORD=your-16-char-app-password
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS_ENABLE=true
```

Lưu ý:

- Không commit App Password thật vào `.env` đã track git - `.env.example` chỉ chứa placeholder.
- `MAIL_USERNAME` Gmail thường phải trùng với `MAIL_FROM` (Gmail từ chối gửi với "From" khác tài khoản đã xác thực, trừ khi cấu hình alias riêng).
- Đổi lại `APP_MAIL_PROVIDER=logging` để quay về chế độ an toàn cho local dev khi không cần gửi mail thật.

## Product Images / Cloudinary

Ảnh sản phẩm đi qua một abstraction duy nhất - `ImageStorageService` - nên `ProductImageService` không biết và không cần biết file được lưu ở đâu. Chọn provider bằng một biến môi trường, cùng cơ chế với Mail/SMTP ở trên:

- `APP_STORAGE_PROVIDER=none` (mặc định) - `NoopImageStorageService`. App vẫn start bình thường, flow cũ "thêm ảnh bằng URL có sẵn" (`POST /api/v1/admin/products/{id}/images`) vẫn dùng được. Chỉ riêng endpoint upload mới (`POST .../images/upload`) sẽ trả lỗi rõ ràng (503) nếu gọi tới khi chưa cấu hình Cloudinary - không silent fail.
- `APP_STORAGE_PROVIDER=cloudinary` - `CloudinaryImageStorageService`, upload/xóa ảnh thật qua Cloudinary SDK chính thức cho Java.
- Giá trị khác/sai: không bean nào khớp, app **không start được** - cùng quy ước với `APP_MAIL_PROVIDER`.

Khi dùng `cloudinary`, 3 biến sau là bắt buộc (app fail fast lúc start nếu thiếu, xem `CloudinaryConfig`):

- `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`

### Lấy Cloudinary credentials

1. Tạo tài khoản free tại [cloudinary.com](https://cloudinary.com) (không cần thẻ).
2. Vào Dashboard → mục "Product Environment Credentials" - copy 3 giá trị `Cloud name`, `API Key`, `API Secret`.

### Cấu hình test local

```dotenv
APP_STORAGE_PROVIDER=cloudinary
CLOUDINARY_CLOUD_NAME=your-cloud-name
CLOUDINARY_API_KEY=your-api-key
CLOUDINARY_API_SECRET=your-api-secret
```

Test upload bằng Swagger UI (`/swagger-ui/index.html`) hoặc curl, sau khi đã có access token admin:

```bash
curl -X POST "http://localhost:8080/api/v1/admin/products/{productId}/images/upload" \
  -H "Authorization: Bearer <admin_access_token>" \
  -F "file=@/path/to/local-image.jpg" \
  -F "isPrimary=true"
```

Response trả `publicId`, `imageUrl` (secure_url từ Cloudinary), `width`, `height`. Ảnh upload vào folder `products` trên Cloudinary; xóa qua `DELETE /api/v1/admin/products/{productId}/images/{imageId}` sẽ xóa cả row trong DB và asset trên Cloudinary (best-effort - xem "Quyết định kỹ thuật" trong báo cáo phase này nếu cần chi tiết).

Lưu ý:

- Không commit `CLOUDINARY_API_SECRET` thật vào `.env` đã track git.
- Giới hạn file: tối đa 5MB, chỉ nhận `image/*` (kiểm tra ở `ProductImageService`, trả lỗi 422 rõ ràng, không phải 500).
- Đổi lại `APP_STORAGE_PROVIDER=none` để quay về chế độ an toàn khi không cần test upload thật.

## Yêu cầu môi trường

- Java 21
- Docker Desktop
- Maven Wrapper dùng sẵn trong repo, không cần cài Maven global

## Chạy local

Từ thư mục gốc repo:

```powershell
docker compose up -d
```

Postgres mặc định map ra host port `5434`, Redis ra `6379`.

Từ thư mục `backend/`:

```powershell
.\mvnw.cmd spring-boot:run
```

App mặc định dùng cổng `8080`; cấu hình local của repository dùng `http://localhost:8082` để tránh xung đột PostgreSQL Enterprise Manager.

## Biến môi trường chính

Xem file [.env.example](./.env.example). Các biến quan trọng nhất:

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`
- `CORS_ALLOWED_ORIGINS`
- `APP_MAIL_PROVIDER`, `MAIL_FROM`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS_ENABLE` (xem mục "Mail / SMTP")
- `APP_STORAGE_PROVIDER`, `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` (xem mục "Product Images / Cloudinary")
- `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`, `VNPAY_TRANSACTION_API_URL`, `PAYMENT_RETURN_URL`, `PAYMENT_CALLBACK_URL`
- `PAYMENT_CALLBACK_URL` phải là URL HTTPS public và được đăng ký làm IPN URL trên VNPay; localhost chỉ dùng cho luồng Return URL khi phát triển.
- `PASSWORD_RESET_URL`
- `APP_SEED_ENABLED`, `APP_SEED_DEMO_PASSWORD`

## Database
- Flyway tự chạy migration khi app start
- `spring.jpa.hibernate.ddl-auto=validate`
- Schema được quản lý bằng migration, không dùng auto-create/auto-update

Migration hiện có:

- `V1__init_schema.sql`
- `V2__product_images_primary_unique_index.sql`
- `V3__product_v2_commerce_extensions.sql` (schema cho promotions/coupons/wishlists/product_reviews và một số bảng vận hành chưa dùng tới như `email_logs`/`audit_logs`)
- `V4__product_reviews_unique_user_order_item.sql`
- `V5__notifications.sql`

## API docs và health check

Sau khi chạy app:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`
- Liveness: `http://localhost:8080/actuator/health/liveness`
- Readiness: `http://localhost:8080/actuator/health/readiness`

Quy ước auth:

- Các endpoint private dùng `Authorization: Bearer <access_token>`
- Một số endpoint public: auth register/login/refresh/forgot/reset, guest cart, payment callback, catalog GET

## Seed data demo

Seed bị tắt mặc định. Chỉ bật khi bạn muốn tạo dữ liệu demo:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--app.seed.enabled=true"
```

Tài khoản demo admin:

- Email: `admin@smartsportswear.local`
- Password: lấy từ `APP_SEED_DEMO_PASSWORD`, mặc định là `Password123`

## Test

Compile:

```powershell
.\mvnw.cmd -q compile test-compile
```

Chạy toàn bộ test:

```powershell
.\mvnw.cmd -q test
```

Lưu ý:

- Test đang dùng Postgres thật từ `docker compose`, không phải in-memory DB
- Cần `docker compose up -d postgres` trước khi chạy test
- Profile test đang trỏ tới host port `5434`

## Những gì đã production-oriented

- JWT stateless auth
- Refresh token rotation + revoke
- Flyway migration
- Integration test thật qua HTTP layer (MockMvc + Postgres thật, không mock DB)
- Check một số race condition quan trọng ở auth/cart/order/payment/inventory/coupon
- Health endpoints cho deploy/runtime check
- Notification có lịch sử gửi (bảng `notifications`), không rollback business action khi gửi mail fail
- Coupon/promotion áp dụng đúng rule (active/time range/usage limit/min order amount), không cho discount vượt tổng tiền đơn
- Mail provider chọn được giữa logging (dev/test an toàn) và SMTP thật (`APP_MAIL_PROVIDER=smtp`) chỉ bằng config, không sửa code; cấu hình SMTP thiếu/sai làm app fail fast lúc start, không silent fallback sai
- Ảnh sản phẩm upload thật qua Cloudinary (`APP_STORAGE_PROVIDER=cloudinary`), cùng cơ chế provider-selection-qua-config với mail; xóa ảnh dọn cả DB row và asset trên Cloudinary, không crash nếu asset đã không còn

## Những gì chưa production-ready hoàn toàn

- Mail SMTP thật đã wiring xong (`SmtpMailService` + `JavaMailSender`) nhưng chưa có retry/queue khi gửi fail - chỉ ghi log trạng thái `FAILED` để admin biết qua `/api/v1/admin/notifications`
- Mail hiện chỉ gửi plain text (`SimpleMailMessage`), chưa có HTML template
- Upload ảnh chưa có resize/optimize phía server (Cloudinary tự có transform-on-the-fly qua URL, nhưng chưa cấu hình preset nào cụ thể trong code)
- Chưa có CI/CD pipeline trong repo
- Chưa có metrics/exporter đầy đủ kiểu Prometheus
- Chưa có audit log/structured logging ở mức vận hành
- Search/filter sản phẩm dùng Postgres LIKE/Specification, chưa có search engine riêng (không nằm trong scope hiện tại)

## Ghi chú vận hành

- Đây là backend Phase 1 đủ tốt để nối frontend và demo nghiệp vụ chính
- Mail provider thật đã có (`APP_MAIL_PROVIDER=smtp`), file storage thật cho ảnh sản phẩm cũng đã có (`APP_STORAGE_PROVIDER=cloudinary`) - nếu chuẩn bị deploy thật, nên làm tiếp: CI/CD, monitoring/logging và secret management (đặc biệt là `MAIL_PASSWORD`/App Password và `CLOUDINARY_API_SECRET` không được commit)
