# Thiết kế thanh toán VietQR + SePay trên môi trường local

**Trạng thái:** IN PROGRESS — Phase 1 schema/config đã triển khai và kiểm thử local
**Ngày chốt bản nháp:** 07/11/2026
**Phạm vi:** thay luồng tạo thanh toán online mới bằng VietQR + SePay; giữ dữ liệu và mã VNPay cũ trong giai đoạn chuyển tiếp.

Runbook triển khai theo thứ tự trong ngày: [vietqr-sepay-implementation-runbook.md](vietqr-sepay-implementation-runbook.md).

Tài liệu SePay dùng để chốt contract: [Webhook](https://docs.sepay.vn/tich-hop-webhooks.html), [xác thực HMAC-SHA256](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc), [API v2 danh sách giao dịch](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach) và [Test Mode](https://docs.sepay.vn/test-mode.html). Bảng giá có thể thay đổi; kiểm tra lại [bảng giá SePay](https://sepay.vn/bang-gia.html) trước go-live và đặt cảnh báo khi gần giới hạn gói.

## 1. Mục tiêu và quyết định kiến trúc

Mục tiêu của tính năng là tự giải bài toán xác nhận chuyển khoản và đối soát:

1. Backend phát hành một yêu cầu thanh toán có số tiền, hạn thanh toán và mã tham chiếu duy nhất.
2. Storefront tạo VietQR offline từ dữ liệu do backend phát hành.
3. SePay gửi biến động số dư qua webhook JSON vào backend local thông qua HTTPS tunnel.
4. Backend lưu mọi giao dịch ngân hàng hợp lệ trước khi ghép với payment.
5. Matching engine tự đối chiếu tài khoản nhận, mã tham chiếu, số tiền và trạng thái payment.
6. Reconciliation job định kỳ gọi API SePay để phục hồi webhook bị sót.
7. Giao dịch không khớp, sai tiền hoặc đến muộn được giữ lại cho admin xử lý; không bị bỏ qua và không tự ghi doanh thu.

### 1.1 Quyết định đã chọn

- Phương thức checkout mới: `VIETQR`.
- Payment provider nội bộ: `BANK_TRANSFER`; `SEPAY` là nguồn dữ liệu giao dịch, không phải phương thức thanh toán.
- Dùng webhook SePay với HMAC-SHA256; kiểm tra `X-SePay-Signature` và `X-SePay-Timestamp` trên raw request body, từ chối timestamp lệch quá 5 phút.
- Dùng SePay Test Mode trong giai đoạn phát triển; token, tài khoản và giao dịch Test Mode tách biệt hoàn toàn với Live.
- Tạo VietQR offline; không tích hợp mới endpoint VietQR `/v2/generate` đã được thông báo dừng từ 01/01/2027.
- Không dùng payOS trong luồng này. payOS là một phương án thay thế hoàn chỉnh cho SePay, không chạy đồng thời cho cùng payment.
- Không xóa enum, migration, payment hoặc refund VNPay cũ ở đợt đầu. Chỉ ngừng cho checkout mới chọn VNPay sau khi VietQR đạt tiêu chí go-live.
- Return URL hoặc thao tác từ trình duyệt không được quyền đánh dấu `PAID`.

### 1.2 Ngoài phạm vi đợt đầu

- Tự động chuyển tiền hoàn qua API ngân hàng.
- Thẻ quốc tế, ví điện tử hoặc trả góp.
- Virtual Account riêng cho từng đơn.
- Tự động khôi phục đơn đã hủy khi tiền đến muộn.
- Coi SePay là sổ cái tài chính duy nhất. PostgreSQL của Commerce vẫn là nguồn chuẩn cho payment/order; SePay chỉ là nguồn sự kiện ngân hàng để đối soát.

## 2. Bất biến tài chính

Các quy tắc sau phải được bảo vệ ở cả service, transaction và database constraint khi phù hợp:

1. Một ID webhook hoặc UUID API v2 của SePay chỉ được nhập vào hệ thống một lần; cùng một giao dịch xuất hiện qua webhook và API reconciliation vẫn chỉ tạo một bank transaction.
2. Một bank transaction chỉ được match tự động với tối đa một payment.
3. Một payment chỉ chuyển `PENDING -> PAID` đúng một lần.
4. Chỉ giao dịch tiền vào (`amount > 0`) mới có thể thanh toán đơn.
5. Chỉ match khi đúng tài khoản nhận, đúng mã tham chiếu và đúng số tiền VND.
6. Callback/reconciliation không tự chuyển `order_status` sang `CONFIRMED`; chúng chỉ cập nhật vòng đời tiền.
7. Payment thành công sau khi order đã `CANCELLED` không hồi sinh order.
8. Giao dịch thiếu/thừa tiền, không có mã hoặc có nhiều mã không được tự đánh dấu `PAID`.
9. Webhook lặp, reconciliation lặp và hai tiến trình chạy đồng thời không được tạo side effect lần hai.
10. Không log hoặc lưu API token, webhook secret, chữ ký, Authorization header hay raw payload chưa redacted.

## 3. Kiến trúc runtime

```text
Customer
  -> Order API
  -> VietQrPaymentService
  -> Payment(PENDING, transaction_ref=DH...)
  -> Storefront renders VietQR offline
  -> Customer transfers through banking app
  -> Bank -> SePay
  -> HTTPS tunnel -> SePayWebhookController
  -> signature verification
  -> BankTransactionIngestionService
  -> bank_transactions(RECEIVED)
  -> BankTransferMatchingService
       -> MATCHED -> Payment/Order PAID
       -> exception status -> Admin reconciliation queue

SePayReconciliationScheduler
  -> SePay Transaction API
  -> the same BankTransactionIngestionService
  -> the same BankTransferMatchingService
```

Webhook và scheduled reconciliation phải đi qua cùng một ingestion/matching pipeline. Không viết hai bộ quy tắc nghiệp vụ.

## 4. Mô hình dữ liệu

Triển khai bằng migration mới `V9__add_vietqr_bank_reconciliation.sql`; không sửa migration `V3` đã chạy.

### 4.1 Mở rộng enum/constraint hiện có

- `orders.payment_method`: thêm `VIETQR`, giữ `COD`, `VNPAY`.
- `payments.provider`: thêm `BANK_TRANSFER`, giữ `COD`, `VNPAY`.
- `payment_events.provider`: thêm `BANK_TRANSFER`, giữ `COD`, `VNPAY`.
- Partial unique hiện có `(order_id, provider) WHERE status='PENDING'` tiếp tục bảo đảm mỗi order chỉ có một payment bank transfer đang hoạt động.

### 4.2 Bảng `bank_transactions`

| Cột | Kiểu đề xuất | Quy tắc |
|---|---|---|
| `id` | `uuid` | PK |
| `source` | `varchar(20)` | `SEPAY` |
| `external_transaction_id` | `varchar(100)` | ID số trong webhook SePay, nullable khi record đến từ API v2 trước |
| `external_transaction_uuid` | `uuid` | UUID trong SePay API v2, nullable khi record đến từ webhook trước |
| `bank_reference` | `varchar(150)` | mã giao dịch phía ngân hàng, nullable |
| `account_number` | `varchar(50)` | tài khoản nhận; không trả ra API customer |
| `amount` | `numeric(14,2)` | chuẩn hóa tiền vào dương, tiền ra âm; chỉ `transfer_type=in` và amount dương được match |
| `description` | `text` | nội dung đã nhận từ ngân hàng |
| `transaction_time` | `timestamptz` | thời điểm ngân hàng/SePay cung cấp |
| `running_balance` | `numeric(18,2)` | nullable |
| `payment_id` | `uuid` | FK nullable đến `payments` |
| `match_status` | `varchar(30)` | trạng thái đối soát |
| `match_reason` | `varchar(255)` | lý do ngắn, không chứa secret |
| `raw_payload_redacted` | `jsonb` | allow-list trường cần audit |
| `received_at` | `timestamptz` | thời điểm backend nhận |
| `reconciled_at` | `timestamptz` | nullable |
| `created_at`, `updated_at` | `timestamptz` | audit |

Ràng buộc/index:

- Partial UQ `(source, external_transaction_id) WHERE external_transaction_id IS NOT NULL` — idempotency webhook.
- Partial UQ `(source, external_transaction_uuid) WHERE external_transaction_uuid IS NOT NULL` — idempotency API v2.
- CHECK yêu cầu ít nhất một trong hai ID khác null.
- Index `(match_status, received_at)` — admin queue.
- Index `(payment_id, received_at)` khi `payment_id IS NOT NULL`.
- CHECK `source IN ('SEPAY')` ở phiên bản đầu.
- CHECK `match_status` thuộc tập được định nghĩa.
- Không xóa hoặc sửa payload nguồn sau khi insert; chỉ cập nhật kết quả match và liên kết payment.

SePay hiện dùng hai loại định danh: webhook gửi `id` dạng integer, còn API v2 trả `id` dạng UUID. Khi cùng một giao dịch đến qua hai kênh, ingestion phải hợp nhất record dựa trên ID đã biết; nếu chưa có liên kết ID, chỉ gắn ID còn thiếu khi bộ khóa `account_number + bank_reference + amount + transaction_time` khớp đúng một record. Không có hoặc có nhiều ứng viên thì không tự hợp nhất: insert/giữ record ở `MANUAL_REVIEW` để tránh đánh dấu thanh toán hai lần.

`match_status`:

- `RECEIVED`: đã xác thực và lưu, chưa match.
- `MATCHED`: khớp chính xác và đã áp dụng payment.
- `DUPLICATE`: chỉ dùng ở metric/response; không cần insert dòng thứ hai.
- `UNMATCHED`: không tìm thấy mã payment.
- `AMOUNT_MISMATCH`: đúng mã nhưng sai tiền.
- `AMBIGUOUS`: có nhiều mã ứng viên hoặc dữ liệu không đủ chắc chắn.
- `LATE_PAYMENT`: payment/order đã hết hạn hoặc order đã hủy.
- `IGNORED_OUTGOING`: số tiền không dương.
- `MANUAL_REVIEW`: cần quyết định của admin.
- `MANUALLY_MATCHED`: admin đã gán có audit.

### 4.3 Bảng `bank_reconciliation_runs`

| Cột | Ý nghĩa |
|---|---|
| `id` | PK |
| `source` | `SEPAY` |
| `started_at`, `finished_at` | cửa sổ chạy |
| `status` | `RUNNING`, `SUCCEEDED`, `FAILED`, `PARTIAL` |
| `from_time`, `to_time` | cửa sổ truy vấn |
| `records_fetched` | số bản ghi lấy từ API |
| `records_inserted` | số giao dịch mới |
| `records_matched` | số giao dịch match |
| `error_code`, `error_message` | thông tin đã redacted |
| `cursor_id` | UUID API v2 cuối cùng đã xử lý thành công, nullable |

Chỉ cho phép một reconciliation job SePay chạy tại một thời điểm bằng PostgreSQL advisory lock hoặc một cơ chế distributed lock tương đương.

## 5. Định danh payment và nội dung VietQR

### 5.1 Payment reference

Định dạng đề xuất:

```text
DH + 10 ký tự Crockford Base32
Ví dụ: DH7K3M92P4Q8Z
```

Yêu cầu:

- Chỉ `[A-Z0-9]`, không dấu, không khoảng trắng.
- Độ dài cố định, đủ ngắn để nằm trong nội dung chuyển khoản.
- Sinh bằng `SecureRandom`.
- Lưu tại `payments.transaction_ref` và được bảo vệ bởi UQ `(provider, transaction_ref)`.
- Không dùng UUID order trực tiếp và không dùng riêng `order_code` nếu order code có thể dự đoán/nhập nhầm.

### 5.2 Chuẩn hóa và trích mã

Matching engine tạo `normalized_description` trong bộ nhớ:

1. Unicode normalize.
2. Chuyển uppercase.
3. Thay nhóm ký tự không phải chữ/số bằng một khoảng trắng.
4. Tìm token khớp chính xác pattern `DH[A-Z0-9]{10}`.

Không dùng `description.contains(ref)` không biên token. Nếu tìm thấy 0 token: `UNMATCHED`; nhiều token khác nhau: `AMBIGUOUS`.

### 5.3 Dữ liệu dùng tạo QR

Backend trả đúng dữ liệu đã chốt trên payment:

- `paymentId`
- `transactionRef`
- `amount`
- `bankBin`
- `accountNumber`
- `accountName`
- `expiresAt`

Storefront chỉ render QR từ response này. Không nhận `amount`, tài khoản hoặc nội dung chuyển khoản do browser tự đề xuất.

## 6. API contract

### 6.1 Tạo/resume phiên VietQR

```http
POST /api/v1/payments/vietqr
Authorization: Bearer <customer JWT>
Content-Type: application/json

{"orderId":"uuid"}
```

Response `201` khi tạo mới, `200` khi resume phiên còn hạn:

```json
{
  "data": {
    "paymentId": "uuid",
    "orderId": "uuid",
    "transactionRef": "DH7K3M92P4Q8Z",
    "amount": 599000,
    "currency": "VND",
    "bankBin": "970415",
    "accountNumber": "123456789",
    "accountName": "NGUYEN VAN A",
    "expiresAt": "2026-11-07T09:30:00Z"
  }
}
```

Quy tắc:

- Khóa order trước khi đọc/tạo payment.
- Chỉ owner được tạo/resume.
- Order phải có `payment_method=VIETQR`, chưa hủy và chưa `PAID`.
- Nếu payment `PENDING` mới nhất còn hạn, trả lại chính payment đó.
- Nếu hết hạn, đóng payment cũ rồi tạo attempt mới nếu order vẫn còn trong hard payment window.

### 6.2 Đọc trạng thái payment

Tái sử dụng API payment theo order hiện có hoặc bổ sung:

```http
GET /api/v1/payments/{paymentId}/status
```

Response customer không trả raw bank transaction, số dư hoặc thông tin người chuyển.

### 6.3 Webhook SePay

```http
POST /api/v1/webhooks/sepay
X-SePay-Signature: sha256=<hex-hash>
X-SePay-Timestamp: <unix-seconds>
Content-Type: application/json
```

Response thành công, kể cả event đã xử lý:

```json
{"success":true}
```

Yêu cầu:

- Endpoint public nhưng xác thực bằng HMAC-SHA256.
- Chuỗi ký là `{timestamp}.{raw_body}`; xác minh trên raw body trước khi bind DTO nghiệp vụ.
- Từ chối timestamp lệch quá 300 giây để chống replay; đồng hồ máy chủ phải được đồng bộ.
- Constant-time compare.
- Validate DTO sau khi chữ ký hợp lệ.
- Hoàn thành dưới giới hạn timeout của SePay; không gọi API SePay ngược lại trong request webhook.
- Event trùng trả `201`/`success=true` và không lặp side effect. Dùng `201` để tương thích cả cấu hình webhook mới và một số webhook SePay cũ.
- Lỗi hạ tầng/transient khiến transaction không commit phải trả non-2xx để SePay retry.
- Payload hợp lệ nhưng không match vẫn trả thành công sau khi đã lưu `bank_transactions`.

### 6.4 Admin reconciliation

```http
GET  /api/v1/admin/bank-transactions
GET  /api/v1/admin/bank-transactions/{id}
POST /api/v1/admin/bank-transactions/{id}/match
POST /api/v1/admin/bank-transactions/{id}/ignore
POST /api/v1/admin/reconciliation/sepay/run
GET  /api/v1/admin/reconciliation/runs
```

Manual match yêu cầu:

- role `ADMIN` hoặc role tài chính được chốt sau;
- payment đích chưa được thu hoặc phải đi qua quy trình xử lý thu trùng;
- số tiền sai không được tự override nếu không có lý do;
- ghi audit before/after, actor và reason;
- khóa order -> payment -> bank transaction theo thứ tự cố định.

## 7. State machine

### 7.1 Luồng chuẩn

```text
Order payment_status: UNPAID -> PENDING -> PAID
Payment status:       PENDING -> PAID
Bank transaction:     RECEIVED -> MATCHED
```

Payment callback không tự đổi `order_status`. Quy tắc financial policy phải mở rộng từ `VNPAY` sang mọi online prepaid method, ít nhất `VNPAY` và `VIETQR`: chỉ được confirm/pack/ship/deliver khi `payment_status=PAID`.

### 7.2 Hết hạn

```text
Order:  PENDING_CONFIRMATION -> CANCELLED
Payment: PENDING -> CANCELLED
Inventory: release reservation exactly once
```

Expiry service hiện gắn với VNPay phải được tổng quát hóa cho `VIETQR`.

### 7.3 Tiền đến muộn

- Lưu bank transaction là `LATE_PAYMENT`.
- Có thể ghi nhận payment thực tế là `PAID` nếu chính sách kế toán muốn phản ánh tiền đã thu, nhưng order giữ `CANCELLED`.
- Tạo refund/manual-review work item đúng một lần.
- Đợt đầu hoàn tiền bank transfer là `MANUAL`, có mã biên nhận và audit; không gọi VNPay refund API.

Quyết định cuối về việc payment muộn chuyển `PAID` hay giữ payment `CANCELLED` phải được chốt trước migration. Khuyến nghị: phản ánh tiền đã thực thu bằng một payment/collection record `PAID`, giữ order `CANCELLED`, sau đó tạo refund liability; không che giấu dòng tiền thực tế.

### 7.4 Thu trùng

Nếu một order đã `PAID` nhưng nhận thêm một bank transaction có cùng mã:

- không ghi đè payment đầu;
- đánh dấu giao dịch mới `MANUAL_REVIEW` với reason `DUPLICATE_COLLECTION`;
- cash report ghi tiền thực nhận;
- sales report không cộng order lần hai;
- tạo nghĩa vụ hoàn tiền nếu admin xác nhận.

## 8. Matching transaction và lock order

Thứ tự khóa chuẩn:

```text
order -> payment -> bank_transaction
```

Pipeline:

1. Verify signature ngoài transaction nghiệp vụ.
2. Bắt đầu transaction DB.
3. Insert `bank_transactions` dựa trên partial UQ của webhook ID/API UUID; hợp nhất chéo kênh theo quy tắc ở mục 4.2.
4. Nếu conflict, trả kết quả idempotent.
5. Loại giao dịch không dương.
6. Whitelist tài khoản nhận đã cấu hình.
7. Trích reference.
8. Lookup payment rồi khóa owning order trước, sau đó khóa payment và bank transaction.
9. Recheck toàn bộ trạng thái sau khi đã có lock.
10. Match chính xác amount bằng `BigDecimal.compareTo`, currency VND.
11. Cập nhật payment, order payment status, bank transaction và payment event trong cùng transaction.
12. Commit rồi phát realtime notification nếu cần.

Không giữ transaction DB mở trong lúc gọi HTTP tới SePay.

## 9. Reconciliation job

### 9.1 Chiến lược local

- Mặc định tắt khi chưa có API token.
- Khi bật, chạy mỗi 5 phút để phù hợp các gói có rate limit thấp.
- Dùng SePay API v2 `GET /transactions` với Bearer API token.
- Lần đầu lấy lại cửa sổ 24 giờ gần nhất. Các lần sau dùng `since_id`, `transaction_date_sort=asc`, `per_page=100` và đọc hết mọi trang trước khi lưu checkpoint cuối.
- Duyệt trang theo thứ tự tăng dần và ingest từng record qua cùng pipeline webhook.
- Hai partial UQ theo webhook ID/API UUID cùng quy tắc hợp nhất chéo kênh bảo đảm cửa sổ chồng lấn không tạo side effect trùng.
- Ghi `bank_reconciliation_runs` và metric tổng hợp; không log response chứa PII đầy đủ.

### 9.2 Failure handling

- `401/403`: dừng run, cảnh báo cấu hình API token; không retry dồn dập.
- `429`: tôn trọng backoff; không vượt rate limit gói.
- `5xx`/timeout: exponential backoff có jitter.
- Parse lỗi từng record: run `PARTIAL`, lưu lỗi đã redacted và tiếp tục record an toàn khác.
- Một run thất bại không thay đổi payment nếu bank transaction chưa được commit đầy đủ.

## 10. Storefront local

Checkout đổi type thành `COD | VIETQR`; VNPay bị ẩn sau feature flag khi cutover.

Trang `/payment/vietqr/{paymentId}` hiển thị:

- QR render offline;
- số tiền và nội dung chuyển khoản có nút copy;
- ngân hàng, số tài khoản, tên chủ tài khoản;
- countdown tới `expiresAt`;
- trạng thái `PENDING`, `PAID`, `CANCELLED`;
- cảnh báo bắt buộc chuyển đúng số tiền và nội dung.

Trang poll backend mỗi 2–3 giây hoặc dùng realtime channel hiện có. Browser không được gọi trực tiếp SePay và không giữ SePay secret/API token.

Nút “Tôi đã thanh toán”, nếu có, chỉ yêu cầu backend trigger một reconciliation có rate limit; nó không được tự cập nhật trạng thái.

## 11. Chạy local qua HTTPS tunnel

```text
Storefront: http://localhost:3000
Backend:    http://localhost:8082
Tunnel:     https://<public-host> -> localhost:8082
Webhook:    https://<public-host>/api/v1/webhooks/sepay
```

Quy trình:

1. Chạy database, backend và storefront.
2. Mở tunnel cho port 8082 bằng Ngrok hoặc Cloudflare Tunnel.
3. Cập nhật URL webhook trên SePay mỗi khi URL tunnel thay đổi.
4. Dùng “Gọi thử” trên SePay và xác nhận signature verification hoạt động.
5. Chỉ sau đó mới chuyển khoản thật với số tiền nhỏ.

Không expose PostgreSQL, admin UI hoặc actuator nhạy cảm qua tunnel. Chỉ public backend HTTP cần thiết; các endpoint khác vẫn được bảo vệ bằng Spring Security.

## 12. Security và dữ liệu nhạy cảm

- Secret chỉ ở backend environment/secret store.
- Không đặt SePay key hoặc webhook secret trong `NEXT_PUBLIC_*`.
- Signature compare constant-time.
- DTO validation giới hạn độ dài description/reference/account fields.
- Raw body có giới hạn kích thước.
- Rate limit webhook theo defense-in-depth, nhưng không dựa duy nhất vào IP allowlist.
- Payload lưu bằng allow-list; account người chuyển chỉ lưu khi thật sự cần và phải che ở admin list.
- Không dùng dịch vụ QR bên thứ ba như `qrcode.show` trong runtime; render nội bộ.
- Không log QR payload nếu trong đó có số tài khoản/nội dung thanh toán.
- Rotate webhook secret có cửa sổ chấp nhận secret cũ/mới nếu SePay còn event retry; quy trình rotate phải được vận hành có kiểm soát.

## 13. Refund và báo cáo

- Bank transfer refund đợt đầu dùng `RefundProvider.MANUAL`.
- Admin nhập mã giao dịch/biên nhận sau khi thực sự hoàn tiền.
- Tổng refund `PENDING/PROCESSING/COMPLETED` không vượt số tiền đã thu.
- `gross_collected` lấy mọi payment thực thu, kể cả thu trùng/thu muộn.
- `sales` chỉ ghi order một lần khi đủ điều kiện.
- Giao dịch `UNMATCHED`, `AMOUNT_MISMATCH`, `LATE_PAYMENT` và thu trùng xuất hiện trong báo cáo đối soát, không được tính là doanh thu bán hàng.

## 14. Test matrix bắt buộc

### 14.1 Tạo payment

- owner tạo VietQR payment hợp lệ;
- non-owner nhận 404;
- COD/VNPay order không được tạo VietQR payment;
- double click trả lại payment đang chờ;
- hai request đồng thời chỉ tạo một payment `PENDING`;
- order đã paid/cancelled/hết hard window bị từ chối.

### 14.2 Webhook và security

- thiếu/sai signature không ghi DB;
- signature đúng nhưng DTO sai bị từ chối an toàn;
- event ID lặp không tạo side effect;
- hai webhook cùng event chạy đồng thời chỉ một event thắng;
- không có secret/signature trong log hoặc payload lưu.

### 14.3 Matching

- đúng reference + amount + account -> `PAID`;
- đúng reference, sai amount -> `AMOUNT_MISMATCH`;
- sai account -> `MANUAL_REVIEW`;
- không reference -> `UNMATCHED`;
- nhiều reference -> `AMBIGUOUS`;
- amount âm/0 -> `IGNORED_OUTGOING`;
- order cancelled -> `LATE_PAYMENT` và refund liability đúng một lần;
- order đã paid nhận lần hai -> duplicate collection, sales không cộng hai lần.

### 14.4 Reconciliation

- record webhook bị bỏ sót được API run phục hồi;
- record đã ingest không bị insert lại;
- hai scheduler instance không chạy cùng lúc;
- pagination/cửa sổ thời gian chồng lấn không bỏ sót và không nhân đôi;
- 401, 429, timeout và partial parse có trạng thái run đúng.

### 14.5 Inventory/refund

- hết hạn nhả reservation đúng một lần;
- callback đồng thời với expiry được tuần tự hóa;
- tiền đến sau expiry không hồi sinh order;
- manual refund không vượt tiền thực thu.

## 15. Feature flags và cutover

Biến cấu hình đề xuất:

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

Thứ tự bật:

1. Deploy schema/code với `VIETQR_ENABLED=false`.
2. Chạy integration test và webhook giả lập.
3. Cấu hình tài khoản + tunnel + SePay test webhook.
4. Bật VietQR cho tài khoản/admin test nội bộ.
5. Khi chuyển Live, tạo token/secret Live riêng và đổi `SEPAY_API_BASE_URL=https://userapi.sepay.vn/v2`; không tái sử dụng credential Test Mode.
6. Test chuyển khoản thật số tiền nhỏ.
7. Bật reconciliation.
8. Ẩn VNPay khỏi checkout.
9. Theo dõi exception queue trước khi coi cutover hoàn tất.

Nếu rollback, tắt tạo payment VietQR mới nhưng vẫn giữ webhook/reconciliation chạy để xử lý các payment đã phát hành và tiền đang trên đường đến.

## 16. Phân rã triển khai

1. **Database:** migration V9, entity/repository, migration tests.
2. **Payment issuance:** properties, VietQR service/controller/DTO, expiry và financial policy.
3. **Webhook ingestion:** raw-body signature verification, bank ledger, idempotency.
4. **Matching:** rules, locks, late/duplicate payment handling, realtime notification.
5. **Storefront:** checkout, trang QR, polling/countdown.
6. **Reconciliation:** SePay client, scheduler lock, run history/backoff.
7. **Admin:** exception queue, manual match/ignore, audit.
8. **Refund/report:** manual bank refund và reconciliation reporting.
9. **Cutover:** feature flags, test tunnel, real low-value payment, hide VNPay.

## 17. Definition of Done

Tính năng chỉ được coi là hoàn tất khi:

- tạo/resume VietQR payment an toàn và QR quét được;
- webhook SePay V2 sai chữ ký không thể sửa payment;
- giao dịch đúng được xác nhận đúng một lần;
- giao dịch sai/thiếu/thừa/muộn vẫn được lưu và xuất hiện ở admin queue;
- reconciliation phục hồi được một giao dịch cố ý bỏ webhook;
- expiry/reconciliation/webhook cạnh tranh không lặp inventory, payment hoặc refund;
- demo local qua HTTPS tunnel chạy end-to-end;
- toàn bộ secret không xuất hiện trong Git, frontend bundle, log hay database payload.
