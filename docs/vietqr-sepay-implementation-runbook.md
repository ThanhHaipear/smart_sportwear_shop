# Runbook triển khai VietQR + SePay local trong một ngày

**Mục tiêu:** hoàn thành luồng thanh toán chuyển khoản end-to-end trên môi trường local: tạo đơn `VIETQR` -> hiện QR -> nhận webhook SePay qua HTTPS tunnel -> đối chiếu -> cập nhật `PAID` đúng một lần -> có cơ chế hậu kiểm giao dịch bị sót.
**Thiết kế nguồn:** [vietqr-sepay-local-payment-design.md](vietqr-sepay-local-payment-design.md)
**Trạng thái ban đầu:** dự án đang chạy COD/VNPay; tài liệu này là checklist triển khai, không phải mô tả tính năng đã có.

Contract chính thức cần đối chiếu khi code: [Webhook SePay](https://docs.sepay.vn/tich-hop-webhooks.html), [HMAC-SHA256](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc), [API v2 transactions](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach) và [Test Mode](https://docs.sepay.vn/test-mode.html).

**Tiến độ 08/11/2026:** Phase 1–5 và luồng hoàn tiền VietQR thủ công đã hoàn thành: migration V9/V11, enum `VIETQR`/`BANK_TRANSFER`, properties fail-fast, API tạo/resume/reload payment VietQR, financial policy/expiry, webhook SePay HMAC-SHA256, matching idempotent, storefront VietQR và yêu cầu hoàn tiền có tài khoản thụ hưởng. Admin chỉ được hoàn tất refund sau khi nhập mã giao dịch thực tế. Storefront/admin lint + typecheck, backend compile và test-compile đều xanh; targeted integration test V11 chờ Docker/Testcontainers hoạt động lại. Feature flags đã bật trong môi trường local để kiểm thử; reconciliation runtime và admin exception queue chưa triển khai.

## 0. Chốt phạm vi để hoàn thành trong ngày

### P0 — bắt buộc hoàn thành

- Migration thêm `VIETQR`, `BANK_TRANSFER` và `bank_transactions`.
- API tạo/resume payment VietQR.
- Storefront hiển thị QR, nội dung, số tiền, countdown và polling trạng thái.
- Webhook SePay HMAC-SHA256: xác minh chữ ký/timestamp, chống trùng, lưu giao dịch, matching tự động.
- Xử lý đúng tiền, sai tiền, không có mã, giao dịch âm và tiền đến muộn.
- Expiry nhả tồn cho đơn VietQR.
- Reconciliation service có thể gọi thủ công và có scheduler bật/tắt bằng env.
- Integration tests cho happy path, signature sai, duplicate, sai tiền và late payment.
- Demo local qua tunnel và một giao dịch test/giá trị nhỏ.

### P1 — làm sau khi P0 xanh nếu còn thời gian

- Trang admin exception queue hoàn chỉnh.
- Dashboard reconciliation run.
- UI manual match/ignore.
- Email/SSE riêng cho thanh toán thành công.
- Tự động tạo toàn bộ refund workflow cho giao dịch bank transfer đến muộn.

Nếu không đủ thời gian, P0 vẫn phải lưu exception trong `bank_transactions`; có thể kiểm tra chúng bằng SQL/API admin tối giản. Không bỏ bank ledger để rút ngắn công việc.

## 1. Điều kiện bên ngoài — kiểm tra trước khi code

Đánh dấu trước khi bắt đầu:

- [ ] Có tài khoản SePay.
- [ ] SePay đã liên kết tài khoản ngân hàng nhận tiền.
- [ ] Đã bật SePay Test Mode và tạo tài khoản ngân hàng test.
- [ ] Có quyền tạo webhook HMAC-SHA256 và đã lưu secret key ngay lúc tạo.
- [ ] Có SePay API token Test Mode để gọi API v2 sandbox; token Test Mode không dùng được cho Live.
- [ ] Biết bank BIN, số tài khoản và tên chủ tài khoản.
- [ ] Có Ngrok hoặc Cloudflare Tunnel.
- [ ] Backend local chạy ở `http://localhost:8082` theo cấu hình repo.
- [ ] Storefront chạy ở `http://localhost:3000`.

Nếu SePay/KYC/liên kết ngân hàng Live chưa sẵn sàng, vẫn triển khai end-to-end bằng Test Mode, tài khoản test và giao dịch mô phỏng. Chỉ kiểm tra chuyển khoản thật sau khi tài khoản Live đã liên kết thành công.

## 2. Nhánh và kiểm tra ban đầu

Tạo nhánh riêng trước khi sửa:

```powershell
git switch -c feature/vietqr-sepay-payment
git status -sb
```

Chạy baseline:

```powershell
Set-Location backend
.\mvnw.cmd -q compile test-compile
Set-Location ..\frontend\storefront
npm run typecheck
Set-Location ..\..
```

Không tiếp tục nếu baseline đang lỗi do thay đổi không liên quan; ghi lại lỗi trước khi triển khai để phân biệt regression.

## 3. Cấu hình môi trường

Thêm vào `backend/.env`; không commit giá trị thật:

```dotenv
VIETQR_ENABLED=false
VIETQR_BANK_BIN=
VIETQR_ACCOUNT_NUMBER=
VIETQR_ACCOUNT_NAME=
VIETQR_PAYMENT_EXPIRY_MINUTES=30

SEPAY_WEBHOOK_ENABLED=false
SEPAY_WEBHOOK_SECRET=
SEPAY_WEBHOOK_TIMESTAMP_TOLERANCE_SECONDS=300
SEPAY_API_TOKEN=
SEPAY_API_BASE_URL=https://userapi-sandbox.sepay.vn/v2
SEPAY_RECONCILIATION_ENABLED=false
SEPAY_RECONCILIATION_DELAY_MS=300000
```

Thêm placeholder an toàn vào `backend/.env.example` nếu file này tồn tại:

```dotenv
VIETQR_ENABLED=false
VIETQR_BANK_BIN=
VIETQR_ACCOUNT_NUMBER=
VIETQR_ACCOUNT_NAME=
VIETQR_PAYMENT_EXPIRY_MINUTES=30
SEPAY_WEBHOOK_ENABLED=false
SEPAY_WEBHOOK_SECRET=
SEPAY_WEBHOOK_TIMESTAMP_TOLERANCE_SECONDS=300
SEPAY_API_TOKEN=
SEPAY_API_BASE_URL=https://userapi-sandbox.sepay.vn/v2
SEPAY_RECONCILIATION_ENABLED=false
SEPAY_RECONCILIATION_DELAY_MS=300000
```

Thêm vào `application.yml`:

```yaml
app:
  vietqr:
    enabled: ${VIETQR_ENABLED:false}
    bank-bin: ${VIETQR_BANK_BIN:}
    account-number: ${VIETQR_ACCOUNT_NUMBER:}
    account-name: ${VIETQR_ACCOUNT_NAME:}
    payment-expiry-minutes: ${VIETQR_PAYMENT_EXPIRY_MINUTES:30}
  sepay:
    webhook-enabled: ${SEPAY_WEBHOOK_ENABLED:false}
    webhook-secret: ${SEPAY_WEBHOOK_SECRET:}
    webhook-timestamp-tolerance-seconds: ${SEPAY_WEBHOOK_TIMESTAMP_TOLERANCE_SECONDS:300}
    api-token: ${SEPAY_API_TOKEN:}
    api-base-url: ${SEPAY_API_BASE_URL:https://userapi-sandbox.sepay.vn/v2}
    reconciliation-enabled: ${SEPAY_RECONCILIATION_ENABLED:false}
    reconciliation-delay-ms: ${SEPAY_RECONCILIATION_DELAY_MS:300000}
```

Tạo:

- `config/AppVietQrProperties.java`
- `config/AppSePayProperties.java`

Ứng dụng đã dùng `@ConfigurationPropertiesScan`, không cần đăng ký từng record thủ công. Properties phải fail-fast khi feature được bật nhưng thiếu bank config/secret cần thiết; khi feature tắt, ứng dụng vẫn phải start.

## 4. Migration V9

Tạo:

```text
backend/src/main/resources/db/v2-migration/V9__add_vietqr_bank_reconciliation.sql
```

Migration phải thực hiện theo thứ tự:

1. Drop và tạo lại `chk_orders_payment_method` để thêm `VIETQR`.
2. Drop và tạo lại `chk_payments_provider` để thêm `BANK_TRANSFER`.
3. Drop và tạo lại `chk_payment_events_provider` để thêm `BANK_TRANSFER`.
4. Tạo `bank_transactions`.
5. Tạo index/unique constraints.
6. Tạo `bank_reconciliation_runs` nếu triển khai scheduler ngay trong P0.

Khung SQL cần đạt:

```sql
alter table orders drop constraint chk_orders_payment_method;
alter table orders add constraint chk_orders_payment_method
    check (payment_method in ('COD', 'VNPAY', 'VIETQR'));

alter table payments drop constraint chk_payments_provider;
alter table payments add constraint chk_payments_provider
    check (provider in ('COD', 'VNPAY', 'BANK_TRANSFER'));

alter table payment_events drop constraint chk_payment_events_provider;
alter table payment_events add constraint chk_payment_events_provider
    check (provider in ('COD', 'VNPAY', 'BANK_TRANSFER'));

create table bank_transactions (
    id uuid primary key default gen_random_uuid(),
    source varchar(20) not null,
    external_transaction_id varchar(100),
    external_transaction_uuid uuid,
    bank_reference varchar(150),
    account_number varchar(50) not null,
    amount numeric(14,2) not null,
    description text,
    transaction_time timestamptz not null,
    running_balance numeric(18,2),
    payment_id uuid,
    match_status varchar(30) not null,
    match_reason varchar(255),
    raw_payload_redacted jsonb,
    received_at timestamptz not null default now(),
    reconciled_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_bank_transactions_payment
        foreign key (payment_id) references payments(id) on delete restrict,
    constraint chk_bank_transactions_source check (source in ('SEPAY')),
    constraint chk_bank_transactions_external_identity check (
        external_transaction_id is not null or external_transaction_uuid is not null
    ),
    constraint chk_bank_transactions_match_status check (match_status in (
        'RECEIVED', 'MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH',
        'AMBIGUOUS', 'LATE_PAYMENT', 'IGNORED_OUTGOING',
        'MANUAL_REVIEW', 'MANUALLY_MATCHED'
    )),
    constraint chk_bank_transactions_payload check (
        raw_payload_redacted is null or jsonb_typeof(raw_payload_redacted) = 'object'
    )
);

create unique index uq_bank_transactions_source_external_id
    on bank_transactions(source, external_transaction_id)
    where external_transaction_id is not null;
create unique index uq_bank_transactions_source_external_uuid
    on bank_transactions(source, external_transaction_uuid)
    where external_transaction_uuid is not null;
create index idx_bank_transactions_match_received
    on bank_transactions(match_status, received_at desc);
create index idx_bank_transactions_payment_received
    on bank_transactions(payment_id, received_at desc)
    where payment_id is not null;
```

Không thêm `DUPLICATE` vào table: event trùng bị unique constraint chặn và trả idempotent response, không tạo dòng thứ hai. SePay webhook dùng ID số nhưng API v2 dùng UUID; migration phải lưu được cả hai định danh.

Thêm migration test xác nhận:

- constraint nhận `VIETQR`/`BANK_TRANSFER`;
- unique source/webhook ID và source/API UUID chặn trùng;
- ít nhất một external ID là bắt buộc;
- payment FK nullable hoạt động;
- status ngoài allow-list bị chặn.

## 5. Backend domain và repository

### 5.1 Sửa enum hiện có

`PaymentMethod.java`:

```java
COD,
VNPAY,
VIETQR
```

`PaymentProvider.java`:

```java
COD,
VNPAY,
BANK_TRANSFER
```

Không xóa VNPay ở bước này.

### 5.2 Tạo bank transaction domain

Tạo package:

```text
modules/payment/banktransfer/
```

Tối thiểu gồm:

```text
entity/BankTransaction.java
entity/BankTransactionSource.java
entity/BankTransactionMatchStatus.java
repository/BankTransactionRepository.java
service/BankTransactionIngestionService.java
service/BankTransferMatchingService.java
```

Repository cần:

```java
Optional<BankTransaction> findBySourceAndExternalTransactionId(...);
Optional<BankTransaction> findBySourceAndExternalTransactionUuid(...);
List<BankTransaction> findCrossChannelMergeCandidates(...);
```

Không dùng `exists()` rồi `insert()` làm lớp chống trùng cuối cùng. Repository phải bắt unique violation và đọc lại record thắng; lookup ứng viên hợp nhất chéo kênh phải khóa các row ứng viên trước khi gắn ID còn thiếu.

Thêm pessimistic-lock query cho bank transaction nếu manual matching và webhook có thể xử lý cùng lúc.

`PaymentRepository` cần lookup theo provider/ref và locked variant:

```java
Optional<Payment> findByProviderAndTransactionRef(...);
Optional<Payment> findByProviderAndTransactionRefForUpdate(...);
```

## 6. API tạo VietQR payment

Tạo:

```text
modules/payment/dto/CreateVietQrPaymentRequest.java
modules/payment/dto/CreateVietQrPaymentResponse.java
modules/payment/service/VietQrPaymentService.java
```

Có thể thêm endpoint vào `PaymentController`, nhưng nên dùng đường dẫn rõ ràng:

```http
POST /api/v1/payments/vietqr
```

Thuật toán service:

1. Kiểm tra `VIETQR_ENABLED`.
2. `findByIdForUpdate(orderId)`.
3. Không tiết lộ order của user khác: trả 404.
4. Bắt buộc `paymentMethod == VIETQR`.
5. Order không `CANCELLED`; payment status chưa `PAID`.
6. Tính hard deadline từ `order.createdAt` và expiry config.
7. Nếu payment `BANK_TRANSFER/PENDING` mới nhất còn hạn: trả lại payment đó.
8. Nếu attempt hết hạn: chuyển attempt cũ `CANCELLED`.
9. Tạo payment mới:
   - provider `BANK_TRANSFER`;
   - amount lấy từ `order.totalAmount`;
   - status `PENDING`;
   - `transactionRef` dạng `DH` + 10 ký tự base32 uppercase;
   - `expiresAt` không vượt hard deadline.
10. Đặt `order.paymentStatus=PENDING`.
11. Save trong cùng transaction.

Không nhận amount, bank account hoặc transfer content từ request body.

Response:

```java
record CreateVietQrPaymentResponse(
    UUID paymentId,
    UUID orderId,
    String transactionRef,
    BigDecimal amount,
    String currency,
    String bankBin,
    String accountNumber,
    String accountName,
    Instant expiresAt
) {}
```

Reference generator phải retry khi gặp unique violation với giới hạn hữu hạn; không dùng timestamp đơn thuần.

## 7. Financial policy và expiry

Sửa `OrderFinancialPolicy`:

- Online prepaid methods `VNPAY` và `VIETQR` phải `PAID` trước khi đi tới `CONFIRMED`, `PACKING`, `SHIPPING`, `DELIVERED`.
- COD giữ hành vi hiện tại.

Đổi `PendingPaymentExpiryService.expirePendingVnpayOrders()` thành tên tổng quát, ví dụ:

```java
expirePendingOnlinePaymentOrders()
```

Query repository nên nhận collection payment methods hoặc chạy hai method rõ ràng. Với VietQR hết hạn:

- khóa order;
- recheck trạng thái;
- nhả reservation đúng một lần;
- order -> `CANCELLED`;
- order payment status -> `CANCELLED`;
- pending payment -> `CANCELLED`;
- internal note không hard-code VNPay.

## 8. Webhook SePay

### 8.1 DTO

Tạo:

```text
modules/payment/sepay/dto/SePayWebhookRequest.java
modules/payment/sepay/controller/SePayWebhookController.java
modules/payment/sepay/service/SePayWebhookSignatureService.java
```

Các field cần hỗ trợ theo payload webhook SePay:

```text
id
gateway
transactionDate
accountNumber
subAccount
code
content
transferType
description
transferAmount
accumulated
referenceCode
```

DTO phải giới hạn độ dài hợp lý, chỉ chấp nhận `transferType` thuộc `in`/`out`, dùng kiểu số không mất chính xác cho tiền và parse `transactionDate` theo định dạng SePay công bố. `id` là idempotency key đầu vào.

### 8.2 Nhận raw body

Controller nên nhận `byte[]` để không làm thay đổi payload trước khi verify:

```java
@PostMapping("/api/v1/webhooks/sepay")
public ResponseEntity<Map<String, Object>> webhook(
        @RequestHeader("X-SePay-Signature") String signature,
        @RequestHeader("X-SePay-Timestamp") String timestamp,
        @RequestBody byte[] rawBody) {
    // 1. verify timestamp + rawBody
    // 2. ObjectMapper.readValue(rawBody, DTO)
    // 3. validate + ingest
}
```

Không parse DTO rồi serialize lại để tính chữ ký.

### 8.3 Xác minh chữ ký

- Chữ ký có dạng `X-SePay-Signature: sha256=<hex-hash>`.
- Tạo message bằng bytes ASCII/UTF-8 của timestamp, một byte dấu chấm `.` rồi nối nguyên vẹn raw body bytes; tính HMAC-SHA256 bằng secret từ backend config. Không decode rồi encode lại raw body.
- Từ chối timestamp lệch quá `SEPAY_WEBHOOK_TIMESTAMP_TOLERANCE_SECONDS` (mặc định 300 giây) để chống replay.
- So sánh digest constant-time bằng `MessageDigest.isEqual` hoặc primitive tương đương.
- Thiếu/sai signature: trả 401/403, không insert DB.
- Không log header chữ ký, secret hoặc raw body.

### 8.4 Spring Security

Public chính xác endpoint:

```text
/api/v1/webhooks/sepay
```

Không public wildcard `/api/v1/webhooks/**` nếu không cần. Authentication của endpoint này là HMAC-SHA256, không phải JWT.

## 9. Ingestion và matching engine

`BankTransactionIngestionService` là entry point duy nhất cho cả webhook và reconciliation.

### 9.1 Ingestion

1. Với webhook, map `id` số của SePay sang `external_transaction_id`; với API v2, map UUID `id` sang `external_transaction_uuid`.
2. Lookup bằng đúng loại ID; nếu đã tồn tại, trả `alreadyProcessed=true`.
3. Nếu record đến từ kênh còn lại, chỉ hợp nhất và gắn ID còn thiếu khi `account_number + bank_reference + amount + transaction_time` khớp đúng một record. Không có hoặc nhiều ứng viên thì đưa `MANUAL_REVIEW`, không tự áp dụng side effect lần hai.
4. Insert `bank_transactions(RECEIVED)` và dựa vào partial unique index để xử lý race condition.
5. Payload redacted chỉ giữ allow-list cần audit.
6. Gọi matching engine trong cùng transaction hoặc ngay sau insert bằng transaction service rõ ràng.

Không dùng pattern `exists()` rồi `insert()` làm bảo vệ duy nhất vì có race condition; unique constraint mới là lớp cuối.

### 9.2 Matching

Thứ tự kiểm tra:

1. `transferType == "in"` và `transferAmount > 0`; ngược lại `IGNORED_OUTGOING`.
2. `accountNumber` đúng tài khoản cấu hình; ngược lại `MANUAL_REVIEW`.
3. Ưu tiên `code`; đồng thời chuẩn hóa `content` và `description` rồi trích token `DH[A-Z0-9]{10}`. Nếu `code` và token trong nội dung mâu thuẫn, chuyển `AMBIGUOUS`.
4. Không token -> `UNMATCHED`.
5. Nhiều token khác nhau -> `AMBIGUOUS`.
6. Lookup `BANK_TRANSFER + transactionRef`.
7. Khóa order trước, rồi khóa payment và bank transaction.
8. Recheck payment/order sau lock.
9. Sai amount -> `AMOUNT_MISMATCH`.
10. Order/payment đã hết hạn hoặc order cancelled -> `LATE_PAYMENT`.
11. Order đã paid -> `MANUAL_REVIEW/DUPLICATE_COLLECTION`.
12. Khớp đầy đủ:
    - payment `PAID`, `paidAt=now`;
    - gateway transaction no = `referenceCode` nếu có;
    - bank code = `gateway` nếu có;
    - order payment status `PAID`;
    - bank transaction `MATCHED`, link payment, `reconciledAt=now`;
    - payment event `BANK_TRANSFER/BANK_CREDIT_MATCHED` với event key SHA-256 từ source + SePay ID.

Order status vẫn `PENDING_CONFIRMATION`; nhân viên xác nhận sau khi đã `PAID`.

### 9.3 Response webhook

- Commit thành công, kể cả unmatched/sai tiền/duplicate: HTTP 201, `{"success":true}`. Dùng 201 để tương thích cả webhook mới và một số cấu hình SePay cũ.
- Signature/DTO sai: 4xx.
- DB/transient failure làm transaction rollback: 5xx để SePay retry.
- Không gọi SePay API hoặc dịch vụ chậm trong request webhook.

## 10. Reconciliation

Tạo:

```text
modules/payment/sepay/client/SePayClient.java
modules/payment/sepay/service/SePayReconciliationService.java
modules/payment/sepay/service/SePayReconciliationScheduler.java
```

API:

```http
GET {SEPAY_API_BASE_URL}/transactions
Authorization: Bearer <SEPAY_API_TOKEN>
```

MVP trong ngày:

- Base URL production là `https://userapi.sepay.vn/v2`; Test Mode dùng `https://userapi-sandbox.sepay.vn/v2` và token riêng.
- Endpoint thực tế là `GET {SEPAY_API_BASE_URL}/transactions`.
- Lần đầu query lại 24 giờ gần nhất; sau đó dùng `since_id` làm checkpoint.
- Luôn gửi `transaction_date_sort=asc`, `per_page=100` và page qua toàn bộ kết quả trước khi cập nhật checkpoint để không bỏ sót record.
- Chuẩn hóa response API v2: `id -> external_transaction_uuid`, `transaction_date -> transactionTime`, `account_number -> accountNumber`, `transfer_type -> transferType`, `amount_in/amount_out -> amount`, `transaction_content -> content`, `reference_number -> referenceCode`, `bank_brand_name -> gateway`.
- Không giả định webhook ID số bằng API v2 UUID; dùng quy tắc hợp nhất chéo kênh trong mục 9.1.
- đưa từng record qua cùng ingestion service;
- unique SePay ID chống trùng;
- scheduler mỗi 5 phút, mặc định tắt;
- thêm endpoint admin `POST /api/v1/admin/reconciliation/sepay/run` để chạy thủ công;
- không gọi sync liên tục từ frontend.

Sau khi webhook real chạy ổn mới đặt:

```dotenv
SEPAY_RECONCILIATION_ENABLED=true
```

## 11. Storefront

### 11.1 Dependencies

Từ `frontend/storefront`:

```powershell
npm install vietqr@^1.2.0
```

Nếu package không tương thích browser bundle, gọi nó trong Next server route/component và trả Data URL/QR payload cho client; không chuyển bank secret nào xuống browser. Bank account/amount/reference vốn phải hiện cho khách, còn SePay key tuyệt đối chỉ ở backend.

### 11.2 Sửa type/API

Các file tối thiểu:

```text
src/lib/endpoints.ts
src/modules/checkout/api.ts
src/modules/checkout/components/CheckoutPageClient.tsx
src/modules/payment/api.ts
src/modules/account/types.ts
src/modules/account/components/OrderDetailPageClient.tsx
```

Thêm:

```ts
type PaymentMethod = "COD" | "VIETQR";
```

Endpoint:

```ts
payments: {
  createVietQr: "/api/v1/payments/vietqr",
  byOrder: ...
}
```

Ẩn VNPay khỏi checkout nhưng chưa xóa API/type lịch sử nếu order cũ vẫn cần hiển thị.

### 11.3 Trang thanh toán

Tạo route:

```text
src/app/payment/vietqr/[paymentId]/page.tsx
src/modules/payment/VietQrPaymentClient.tsx
```

Trang phải có:

- QR offline;
- số tiền format `vi-VN`;
- bank/account/name;
- transaction reference;
- copy buttons;
- countdown theo `expiresAt` server trả về;
- polling 2–3 giây;
- dừng poll khi `PAID`, `CANCELLED`, `FAILED`;
- link về chi tiết đơn.

Không dùng query string/browser state làm nguồn chuẩn cho amount hoặc trạng thái. Nếu reload trang, frontend phải lấy lại payment từ backend.

Sau khi tạo order:

```text
create order(VIETQR)
-> create/resume VietQR payment
-> route /payment/vietqr/{paymentId}
```

Nếu tạo payment thất bại sau khi order đã tạo, chuyển về chi tiết order và cho phép “Thanh toán VietQR” để resume; không tạo order thứ hai.

## 12. Tests cần viết trước khi chuyển tiền thật

Tạo tối thiểu:

```text
backend/src/test/java/.../modules/payment/VietQrPaymentIntegrationTest.java
backend/src/test/java/.../modules/payment/SePayWebhookIntegrationTest.java
backend/src/test/java/.../modules/payment/SePayReconciliationIntegrationTest.java
```

Danh sách test P0:

- [x] Customer tạo payment VietQR cho order của mình.
- [x] Double-click trả lại cùng pending payment.
- [x] User khác không thấy order.
- [x] Amount payment luôn lấy từ order.
- [x] Webhook signature sai không insert bank transaction.
- [x] Webhook đúng, đúng account/ref/amount -> payment/order `PAID`.
- [x] Cùng SePay ID gửi hai lần -> chỉ một bank transaction và một transition.
- [x] Hai webhook đồng thời -> không xác nhận hai lần.
- [x] Sai amount -> `AMOUNT_MISMATCH`, payment vẫn pending.
- [x] Không ref -> `UNMATCHED`.
- [x] Amount âm -> `IGNORED_OUTGOING`.
- [x] Order expired/cancelled -> `LATE_PAYMENT`, không hồi sinh order.
- [ ] Reconciliation ingest lại event webhook -> idempotent.
- [x] Online financial policy chặn confirm VietQR chưa paid.
- [ ] Expiry nhả reservation đúng một lần.

Chạy targeted tests:

```powershell
Set-Location backend
.\mvnw.cmd -q "-Dtest=VietQrPaymentIntegrationTest,SePayWebhookIntegrationTest,SePayReconciliationIntegrationTest" test
```

Sau đó chạy toàn bộ backend:

```powershell
.\mvnw.cmd -q test
```

Storefront:

```powershell
Set-Location ..\frontend\storefront
npm run typecheck
npm run lint
npm run build
```

## 13. Chạy local end-to-end

Từ repo root, mở các PowerShell riêng:

```powershell
docker compose up -d redis rabbitmq
```

```powershell
.\start-backend.ps1
```

```powershell
.\start-storefront.ps1
```

Kiểm tra:

```text
Backend health: http://localhost:8082/actuator/health
Storefront:     http://localhost:3000
```

Lần đầu, cài SDK ngrok local vào thư mục đã được Git ignore:

```powershell
npm install --prefix .tools\ngrok-sdk @ngrok/ngrok@1.7.0
```

Điền `NGROK_AUTHTOKEN` và, sau lần chạy đầu, ghim dev domain được cấp vào
`NGROK_DOMAIN` trong `backend/.env`. Sau đó mở tunnel bằng launcher của repo:

```powershell
.\start-sepay-tunnel.cmd
```

Launcher tạo proxy `127.0.0.1:18083` chỉ cho phép đúng
`POST /api/v1/webhooks/sepay`; không tunnel trực tiếp port `8082` và không public
actuator hoặc các API khác.

Webhook URL:

```text
https://<ngrok-host>/api/v1/webhooks/sepay
```

Trên SePay:

1. Bật **Test Mode** và chọn đúng tài khoản ngân hàng test.
2. Vào **Tích hợp -> Webhooks -> Thêm webhook**.
3. Chọn sự kiện **Tất cả** (tiền vào và tiền ra), `Content-Type: application/json` và nhập URL tunnel.
4. Bật tự động gửi lại khi server trả non-2xx.
5. Chọn xác thực **HMAC-SHA256**, tạo secret và lưu ngay vào `SEPAY_WEBHOOK_SECRET` vì SePay chỉ hiển thị đầy đủ một lần.
6. Tắt **Chỉ gửi khi có mã thanh toán** để cùng webhook nhận được cả mã thanh toán `DH...` và mã hoàn `RFD...`; backend tự lọc và lưu trạng thái an toàn. Không cần đổi HMAC secret hiện tại.
7. Restart backend sau khi đổi `.env`.
8. Bấm **Gửi thử** và kiểm tra endpoint trả HTTP 201 cùng `{"success":true}`.
9. Mô phỏng một giao dịch trong Test Mode; xác nhận test event chỉ match đúng payment test.

Dev domain miễn phí gắn với tài khoản ngrok là cố định. Giữ `NGROK_DOMAIN` để
launcher yêu cầu lại đúng hostname sau mỗi lần restart; chỉ cập nhật SePay nếu
chủ động đổi domain hoặc tài khoản ngrok.

## 14. Kịch bản nghiệm thu trong ngày

### Kịch bản A — happy path

1. Login customer.
2. Thêm sản phẩm vào cart.
3. Checkout bằng VietQR.
4. Trang hiển thị QR, đúng amount và mã `DH...`.
5. Dùng app ngân hàng quét/chuyển số tiền nhỏ.
6. SePay gửi webhook qua tunnel.
7. Storefront chuyển từ `PENDING` sang `PAID`.
8. Order vẫn `PENDING_CONFIRMATION` và admin có thể confirm.
9. DB có đúng một `bank_transactions/MATCHED`.

### Kịch bản B — duplicate

Replay cùng SePay event. Kết quả:

- response success;
- không thêm bank transaction;
- không đổi payment lần hai;
- không cộng report/inventory lần hai.

### Kịch bản C — sai tiền

Gửi fixture có đúng mã nhưng amount khác:

- bank transaction `AMOUNT_MISMATCH`;
- payment/order không `PAID`;
- transaction xuất hiện trong exception query.

### Kịch bản D — webhook bị bỏ sót

1. Tạm tắt webhook hoặc dùng một giao dịch chỉ tồn tại trên SePay API.
2. Chạy admin reconciliation endpoint.
3. Giao dịch được ingest và match qua cùng pipeline.

### Kịch bản E — tiền đến muộn

1. Cho order hết hạn/cancel.
2. Gửi fixture đúng mã/amount.
3. Order không hồi sinh.
4. Giao dịch thành `LATE_PAYMENT`/manual review.

## 15. Quy tắc cutover khỏi VNPay

Trong ngày triển khai:

- Không xóa bảng/enum/migration VNPay.
- Không xóa refund VNPay cũ.
- Ẩn radio VNPay khỏi checkout.
- Chặn tạo session VNPay mới bằng feature flag hoặc không gọi từ UI.
- Giữ callback VNPay hoạt động cho payment cũ đang pending cho tới khi hết cửa sổ thanh toán.

Chỉ dọn code VNPay ở một thay đổi riêng sau khi xác nhận:

- không còn payment VNPay pending;
- không còn refund VNPay cần query/submit;
- dữ liệu lịch sử vẫn đọc được hoặc đã có migration phù hợp.

## 16. Checklist trước khi commit

- [ ] `git diff --check` không lỗi.
- [ ] Không có secret trong `git diff`.
- [ ] `.env` không bị track.
- [ ] Migration chạy trên PostgreSQL test sạch.
- [ ] Backend compile và targeted tests xanh.
- [ ] Toàn bộ backend tests xanh hoặc mọi lỗi cũ được ghi rõ.
- [ ] Storefront typecheck/lint/build xanh.
- [ ] Webhook signature sai bị từ chối.
- [ ] Duplicate event idempotent.
- [ ] QR reload vẫn lấy lại trạng thái từ backend.
- [ ] Reconciliation dùng cùng ingestion service.
- [ ] Demo happy path và ít nhất một exception path thành công.

Kiểm tra secret trước commit:

```powershell
git status --short
git diff --check
git diff -- . ':!backend/.env' ':!frontend/storefront/.env.local'
```

## 17. Definition of Done cho MVP hôm nay

MVP hoàn tất khi đồng thời đạt:

1. Checkout tạo được order/payment VietQR mà không dùng VNPay/payOS.
2. QR quét được và chứa đúng tài khoản, amount, reference do backend phát hành.
3. Backend local nhận webhook SePay HMAC-SHA256 qua HTTPS tunnel.
4. Chữ ký sai không thể tạo hoặc sửa dữ liệu tài chính.
5. Giao dịch đúng cập nhật payment/order `PAID` đúng một lần.
6. Sai tiền/không mã/tiền âm/tiền muộn được lưu với status rõ ràng, không tự `PAID`.
7. Job/API reconciliation phục hồi được event không đến qua webhook.
8. Expiry không làm mất tiền đến muộn và không lặp thao tác tồn kho.
9. Targeted backend tests và storefront checks đều xanh.
10. Có thể trình diễn toàn bộ luồng local từ checkout đến xác nhận thanh toán.

Nếu thiếu một trong các mục 3–7, tính năng mới chỉ là QR chuyển khoản hoặc POC webhook, chưa phải bài toán FinTech có đối soát tự động hoàn chỉnh.
