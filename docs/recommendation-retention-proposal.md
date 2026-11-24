# Đề xuất: giữ dữ liệu và ẩn danh interaction của Recommendation

> V4 và lệnh bảo trì đã có trong repo. Triển khai Supabase và lịch chạy vẫn cần phê duyệt theo O03. Identity chưa phát sự kiện xóa tài khoản, nên hiện dùng lệnh vận hành cho yêu cầu đã được xác minh.
> **Người soạn:** Quyền. **Cần phối hợp:** người phụ trách DB (trigger, role, job), người phụ trách Identity (sự kiện xóa tài khoản).
> **Điều kiện bật:** giữ `INTERACTION_REAL_ENABLED=false` đến khi migration, lịch retention hằng ngày và quy trình xóa liên kết tài khoản đã được xác minh. `INTERACTION_DEMO_ENABLED` cũng mặc định tắt và chỉ dùng trong môi trường demo.

## Vận hành V4

1. Người được phân công áp dụng V3 rồi V4 bằng migration role theo O03. Kiểm thử bằng PostgreSQL cô lập trước khi áp dụng lên Supabase.
2. Cấp `RECOMMENDATION_MAINTENANCE_DATABASE_URL` cho tiến trình bảo trì bằng credential có thể `SET ROLE recommendation_owner`. Runtime `recommendation_app` chỉ phục vụ ứng dụng. Lệnh bảo trì trả số dòng theo bảng.
3. Chạy thử `python scripts/retention.py --expire` từ `recommendation-service`. Lệnh mặc định rollback. Đặt lịch hằng ngày với `--expire --execute`. Khi kết quả chạm giới hạn batch, chạy lại đến khi lượng xử lý thấp hơn giới hạn.
4. Với yêu cầu xóa tài khoản đã xác minh, chạy `python scripts/retention.py --erase-account <UUID>` để kiểm tra số dòng, rồi chạy lại với `--execute`. Hoàn tất trong bảy ngày. Lệnh xử lý các hàng liên kết trực tiếp với tài khoản và giữ giỏ đã gỡ định danh để tính luật kết hợp.
5. V4 giữ `processed_events` để replay không tạo bản ghi trùng. Nếu cần quay lại code cũ, giữ schema đã áp dụng. Dữ liệu hết hạn đã xóa cần restore point nếu phải khôi phục.

## 1. Yêu cầu từ O01 (`docs/database-implementation-plan.md`)

| Dữ liệu | Quy định O01 |
|---|---|
| Interaction có định danh hoặc giả danh | Giữ **180 ngày**, sau đó xóa. Chỉ số tổng hợp không nhận diện giữ 12 tháng |
| Recommendation request/item | Giữ **30 ngày** |
| Log kỹ thuật | Giữ 90 ngày |
| Yêu cầu xóa tài khoản | Gỡ liên kết hoặc ẩn danh interaction **trong tối đa 7 ngày** |
| Metadata/log | Không lưu raw query, secret, token, PII |
| Job retention | Chạy hằng ngày theo batch, idempotent, audit chỉ ghi số lượng |

## 2. Schema `recommendation` trước V4

| Bảng | Cột định danh | Mốc thời gian | Ràng buộc liên quan |
|---|---|---|---|
| `user_interactions` | `user_ref`, `anonymous_id` | `occurred_at` | Trigger **append-only** chặn mọi UPDATE/DELETE. Đã có cột `anonymized_at` nhưng không thể cập nhật. CHECK bắt buộc có ít nhất một trong `user_ref`, `anonymous_id` |
| `recommendation_logs` (bảng cũ) | `user_ref`, `anonymous_id` | `occurred_at` | Append-only như trên |
| `order_baskets` | `user_ref` | `occurred_at` | Không có trigger |
| `recommendation_requests` → `recommendation_items` | `user_ref` | `served_at` | `items` xóa theo `on delete cascade` |
| `processed_events` | — | `processed_at` | Không có trigger |

Metadata từ Storefront dùng danh sách trường cho phép. Model được đối chiếu qua `request_id` và bảng `recommendation_requests`; giá trị `modelVersion` từ client cũ được bỏ trước khi ghi.

Phần còn thiếu:
1. Không xóa được interaction quá 180 ngày, vì trigger append-only chặn.
2. Không ẩn danh được khi có yêu cầu xóa tài khoản, cũng vì trigger.
3. Recommendation không biết khi nào tài khoản bị xóa: Identity chỉ phát `identity.user.created.v1` và `identity.user.updated.v1`.

## 3. Cơ chế V4 và phần tích hợp Identity dự kiến

### 3.1. Thay trigger append-only bằng trigger "chỉ cho phép retention và ẩn danh" (migration V4)

Giữ tinh thần append-only (không sửa nội dung hành vi), nhưng cho phép đúng hai thao tác:

| Thao tác | Điều kiện được phép |
|---|---|
| `DELETE` | `old.occurred_at < now() - interval '180 days'` |
| `UPDATE` | Chỉ đổi `user_ref`, `anonymous_id`, `anonymized_at`; `old.anonymized_at is null` và `new.anonymized_at is not null` (ẩn danh một lần, không đảo ngược) |
| Mọi thay đổi khác | Vẫn bị từ chối như hiện nay |

Áp dụng tương tự cho `recommendation_logs`. Hai bảng này cũng có thể gộp hoặc bỏ, vì storefront hiện ghi vào `user_interactions`.

### 3.2. Cách ẩn danh

- Đặt `user_ref = null` và `anonymous_id = 'erased:' || <UUID ngẫu nhiên, một giá trị cho mỗi tài khoản bị xóa>`, cùng `anonymized_at = now()`.
- Một giá trị ngẫu nhiên cho mỗi tài khoản vẫn thỏa CHECK định danh, và vẫn giữ được việc gom hành vi theo "một người" cho đánh giá. Giá trị này không lưu ở đâu khác, nên không thể nối ngược về tài khoản.
- `order_baskets.user_ref` và `recommendation_requests.user_ref`: đặt `null`. Giỏ mới chỉ ghi product ID và thời điểm. Request log chỉ ghi liên kết tài khoản khi `REAL` được bật.

### 3.3. Job retention hằng ngày

| Bước | Bảng | Thao tác |
|---|---|---|
| 1 | `user_interactions`, `recommendation_logs` | Xóa dòng có `occurred_at` quá 180 ngày, theo lô 5.000 dòng |
| 2 | `order_baskets` | Đặt `user_ref = null` khi quá 180 ngày. Giữ `product_ids`, vì không nhận diện được ai và vẫn cần cho luật kết hợp |
| 3 | `recommendation_requests` (cascade `items`) | Xóa khi `served_at` quá 30 ngày |

- Chạy được nhiều lần (idempotent). Chỉ ghi log số dòng đã xử lý, không ghi ID.
- Chạy bằng `scripts/retention.py`, mặc định rollback, thêm `--execute` mới commit. Các phép bảo trì dành cho `recommendation_owner`.
- **Ảnh hưởng đến đánh giá:** không có. Dữ liệu synthetic nằm ở file, không nằm trong DB. Model chỉ giữ checksum giỏ train, không giữ ID người dùng.

### 3.4. Nhận yêu cầu xóa tài khoản

Lựa chọn A (khuyến nghị): Identity phát sự kiện mới `identity.user.anonymized.v1 { userId }` khi tài khoản bị xóa hoặc ẩn danh (Identity đã có cột `users.anonymized_at`). Sự kiện này được bind vào `recommendation.events`, worker nhận và ẩn danh theo 3.2. Cách này đáp ứng hạn 7 ngày tự động, và tái dùng được cho Chatbot/Search.

Quy trình hiện tại dùng `scripts/retention.py --erase-account <UUID>` do người được phân công chạy sau khi xác minh yêu cầu và thu hồi quyền đăng nhập của tài khoản. Lệnh chỉ trả số dòng xử lý.

### 3.5. Kiểm thử cần có trước khi bật `REAL`

1. Trên PostgreSQL cô lập (không dùng Supabase):
   - DELETE dòng 181 ngày tuổi thành công; dòng 179 ngày bị từ chối.
   - UPDATE `event_type`/`product_id` bị từ chối.
   - Ẩn danh lần hai bị từ chối.
2. Job retention chạy hai lần liên tiếp cho cùng kết quả.
3. Ẩn danh một user:
   - không còn dòng nào chứa `user_ref` đó trong 4 bảng;
   - số dòng interaction không đổi;
   - hành vi của user vẫn gom chung dưới một `anonymous_id` mới.
4. Bằng chứng (lệnh, output) đính kèm PR theo O03.

## 4. Câu hỏi cần chốt

1. Người phụ trách DB đồng ý thay trigger theo 3.1, hay muốn cơ chế khác (ví dụ hàm `SECURITY DEFINER` do một role riêng gọi)?
2. Khi nào Identity phát sự kiện xóa tài khoản để thay quy trình vận hành thủ công?
3. Ai chạy V4 trên Supabase, và ai được giao vận hành job retention (O03: chỉ một người)?
4. Có giữ hay bỏ bảng `recommendation_logs` (bảng cũ, không còn được ghi)?
