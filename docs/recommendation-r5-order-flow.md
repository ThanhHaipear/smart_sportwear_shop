# R5: vì sao đơn hàng chưa vào `order_baskets`

> Thuộc review **R5** của PR #1. Điều tra ngày 23/11/2026 bởi Quyền.
> Supabase chỉ được **đọc** (`set transaction read only`, chỉ lấy số đếm và trạng thái). Mọi thử nghiệm có ghi dữ liệu chạy trên container tạm.
> **Cần phối hợp:** người phụ trách Sales (mục 3.1, 3.3) và người phụ trách hạ tầng/RabbitMQ (mục 3.2).

## 1. Hiện trạng (đọc từ Supabase, 23/11)

| Nguồn | Số liệu |
|---|---|
| `sales.orders` | 4 đơn: 12/10 (CONFIRMED), 12/10 (CANCELLED), 08/11 (CANCELLED), 14/11 (PENDING_CONFIRMATION) |
| `sales.integration_outbox` | **1 dòng** `sales.order.created.v1`, của đơn 14/11, trạng thái `PUBLISHED` lúc 03:29:43 UTC, 1 sản phẩm |
| `recommendation.processed_events` | Không có dòng nào của consumer `recommendation-events`: worker **chưa từng xử lý** một sự kiện Catalog/Sales nào |
| `recommendation.order_baskets` | 0 |
| `recommendation.catalog_products` | 36 (nạp bằng script backfill ngày 16/11, không qua sự kiện) |
| `association_rules` / `recommendation_models` | 0 / 0 |
| RabbitMQ local hiện tại | Mọi hàng đợi `recommendation.events*` (kể cả DLQ) đều rỗng; binding `business.events → recommendation.events (order.created)` đúng |

## 2. Nguyên nhân

**(a) Ba đơn đầu không bao giờ có sự kiện.** Đơn 12/10 và 08/11 được tạo trước khi Sales có outbox (outbox chỉ có 1 dòng, của đơn 14/11). Muốn có chúng trong `order_baskets` phải backfill (mục 3.3).

**(b) Sự kiện của đơn 14/11 bị mất ở RabbitMQ, nhưng Sales vẫn ghi `PUBLISHED`.**
- Volume `smart_sportwear_shop_rabbitmq_data` chứa **hai node**: `rabbit@4e005ee6b22b` (container cũ) và `rabbit@e0b43e8c21d0` (container tạo ngày 16/11).
- Mình chép volume sang một volume tạm rồi khởi động node cũ để xem. Node cũ **không có exchange `business.events`** và không có hàng đợi `recommendation.events`; nó chỉ có cấu hình của Search. Nó đã khởi động từ trước khi `definitions.json` thêm phần Recommendation (10/11), và không được khởi động lại sau đó.
- Ngày 14/11, Sales publish vào exchange không tồn tại. `SalesOutboxRelay` gọi `rabbit.convertAndSend(...)` rồi ghi `PUBLISHED` ngay, **không chờ publisher confirm** và không dùng mandatory/returns (khác với `CatalogOutboxRelay`), nên lỗi không quay về Sales: outbox ghi `PUBLISHED` nhưng tin đã mất (`sales-service/.../events/SalesOutboxRelay.java`).

**(c) Tạo lại container RabbitMQ thì mất hàng đợi cũ.** Service `rabbitmq` trong `docker-compose.yml` không đặt `hostname`, nên mỗi lần tạo lại container lại thành một node mới, không thấy dữ liệu của node trước. Node cũ hiện vẫn giữ **244 tin trong `product-search.indexing` và 325 tin trong `visual-search.indexing`** mà node hiện tại không thấy. Việc này liên quan tới phần Search (Hải).

**(d) Ngay cả khi tin tới nơi**, đơn 14/11 chỉ có 1 sản phẩm. Luật kết hợp cần giỏ có ≥ 2 sản phẩm, nên đơn này chỉ làm tăng `purchase_count`.

## 3. Đề xuất

### 3.1. Sales: chỉ ghi `PUBLISHED` khi RabbitMQ xác nhận (phối hợp Sales)

- Sales đã bật `publisher-confirm-type: correlated` (`sales-service/src/main/resources/application.yml:24`) nhưng relay **không chờ** confirm. Cần thêm `publisher-returns: true` (và `template.mandatory: true` để tin không định tuyến được bị trả về).
- Làm giống `CatalogOutboxRelay`, vốn đã đúng: gửi kèm `CorrelationData`, chờ `correlation.getFuture().get(5s)`, chỉ ghi `PUBLISHED` khi `isAck()` và `getReturned() == null`. Nack, return hoặc hết thời gian chờ thì dùng nhánh `RETRY`/`FAILED` đã có.

### 3.2. Hạ tầng: cố định tên node RabbitMQ (phối hợp người phụ trách hạ tầng và Hải)

- Thêm `hostname: rabbitmq` cho service `rabbitmq` trong `docker-compose.yml`, để tạo lại container vẫn dùng chung một node.
- Lưu ý khi đổi: lần đầu đổi sẽ thành node mới (thêm một thư mục node nữa). Trước khi đổi, cần xử lý các tin còn tồn ở node hiện tại, và quyết định có cứu 569 tin Search ở node cũ hay chỉ reindex lại.
- Mình **không** tự sửa điểm này vì nó ảnh hưởng hàng đợi Search.

### 3.3. Backfill đơn cũ (phối hợp Sales)

| Mục | Đề xuất |
|---|---|
| Nguồn | `sales.orders` + `sales.order_items.variant_ref` (chỉ Sales đọc được schema `sales`) |
| Phạm vi | Đơn không có dòng `sales.order.created.v1` thành công trong outbox: hiện là 3 đơn 12/10, 12/10, 08/11, cộng đơn 14/11 bị mất. Cần chốt có bỏ đơn `CANCELLED` hay không (hiện tại `PURCHASE` tính lúc đặt đơn, xem Mission Giai đoạn 2) |
| Cách chạy | Sales chèn lại dòng outbox `sales.order.created.v1` (eventId mới, payload như `createOrder`) cho các đơn trong phạm vi; relay publish như bình thường |
| An toàn khi chạy lại | Worker idempotent theo `order_id`: `order_baskets` có khóa `order_id`, nên đơn gửi lại (dù khác eventId) không tạo giỏ trùng và không cộng `purchase_count` lần hai. Đã kiểm thử (mục 4, kiểm tra 3–4) |
| Điều kiện trước | 3.1 đã sửa, hoặc ít nhất kiểm tra exchange/binding trên RabbitMQ đang chạy, kẻo lại mất tin |

## 4. Kiểm chứng trên môi trường cô lập

`recommendation-service/scripts/verify_order_flow.py` khởi động Postgres tạm (V1–V3), RabbitMQ nạp đúng `rabbitmq/definitions.json` của repo, Catalog giả và **worker thật**. Script publish tin giống hệt `SalesOutboxRelay` (exchange `business.events`, routing key `order.created`, payload như `createOrder`, `items` là variant ID). Kết quả **7/7 đạt** (`evidence/recommendation/r5-order-flow-20261123.json`):

1. Tin `order.created` được định tuyến tới `recommendation.events` (publish với mandatory + confirm).
2. Giỏ lưu **product ID** đổi từ variant ID; `purchase_count` +1.
3. Phát lại cùng sự kiện (cùng eventId) không tạo giỏ hay số đếm trùng.
4. Cùng đơn nhưng eventId mới (Sales gửi lại) cũng không trùng.
5. Hai size của cùng sản phẩm gộp thành một product ID.
6. Sau 2 đơn, rebuild model khai phá được 6 luật; gợi ý cho giày trả tất bằng `ASSOCIATION_RULE`.
7. Không còn tin nào trong hàng đợi, retry hay DLQ.

Phạm vi: từ RabbitMQ trở đi. Đoạn Sales → RabbitMQ chưa kiểm thử tự động; lỗi của đoạn này đã được chỉ ra trong code (mục 2b) và đề xuất sửa (mục 3.1).

Ghi chú khi viết script: chạy `rabbitmqctl`/`rabbitmq-diagnostics` bằng `docker exec` với **root** trong lúc RabbitMQ đang khởi động sẽ tạo `.erlang.cookie` thuộc root, làm server lỗi `eacces`. Script chạy các lệnh này bằng `-u rabbitmq`.

## 5. Ảnh hưởng tới demo

- Hiện tại, trên dữ liệu live, luật kết hợp **chưa có dữ liệu** (0 giỏ). Gợi ý trên website đến từ content và popularity.
- Kết quả của luật kết hợp trong báo cáo là kết quả **offline trên `synthetic-v1`** (`docs/recommendation-results.md`), không phải từ dữ liệu live. Xem `docs/recommendation-demo.md` mục "Dữ liệu live và kết quả offline".
