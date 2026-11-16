# Giải thích cách làm — Nhiệm vụ Recommendation (Quyền)

> Nhật ký giải thích từng nhiệm vụ đã hoàn thành, viết để **hiểu và bảo vệ** trước hội đồng.
> Mỗi mục trả lời: nhiệm vụ để làm gì, làm như thế nào, tại sao làm vậy, code (nếu có), xử lý dữ liệu (nếu có), công nghệ và vấn đề gặp phải (nếu có).
> Mục mới được thêm vào **cuối file**. Thứ tự các mục khớp với checklist trong `Mission.md`.

---

## ⚠️ Đọc trước: hệ thống đã chuyển sang microservice (cập nhật 16/11/2026)

Các mục nhật ký từ 16/10 đến 23/10/2026 bên dưới được viết khi dự án còn là **backend monolith Spring Boot `:8082`**. Từ đầu tháng 11, nhánh `main` đã tách thành sáu service và Gateway (nghiệm thu local Phase 12 ngày 11/11/2026). Nội dung nhật ký cũ **được giữ nguyên** để làm bằng chứng quá trình làm việc, nhưng nhiều chi tiết kỹ thuật không còn đúng. Bảng sau cho biết thay đổi gì và đọc ở đâu.

| Nội dung trong nhật ký cũ | Hiện trạng | Đọc ở đâu |
|---|---|---|
| Backend `:8082`, `start-backend.ps1`, `backend/.env`, JDK `.tools/jdk-21` | Backend đã bị gỡ. Chạy toàn hệ thống bằng `.\scripts\run\start-all-local.cmd`; cấu hình chung trong `.env.phase10.local`. JDK vẫn cài bằng `.\scripts\setup\setup-local-tools.cmd` | `README.md`, `RUN_LOCAL.md` |
| Schema `public` (39 bảng) và `ai_retrieval`; migration Flyway V1–V7 trong `backend/.../db/v2-migration` | Đã xóa khỏi Supabase ngày 11/11/2026. Mỗi service có schema riêng: `identity`, `catalog`, `sales`, `ai_search`, `chatbot`, `recommendation` | `Database Structure.md` |
| Bản nháp `V10__create_1c_recommendation.sql`, câu hỏi "số version migration V8/V9/V10" | **Không còn cần.** Schema `recommendation` đã có trong `recommendation-service/migrations/` | `Mission.md` A.1 |
| "Đặt service recommendation trong `chatbot-service`, backend làm proxy" | Đã có service riêng `recommendation-service` (:8091) + worker; Gateway định tuyến thẳng | `WorkFlow.md` mục 11 |
| Schema `evaluation` dùng chung | Không tạo. Recommendation dùng `recommendation.evaluation_runs` + file manifest trong `evidence/` | `Mission.md` Giai đoạn 5, 9 |
| BGE-M3 (1024 chiều), `backfill-product-embeddings.ps1 -Verify`, `product_text_embeddings` | Embedding văn bản giờ là OpenAI `text-embedding-3-small` (1536 chiều) trong `ai_search.text_embeddings`, thuộc Search service. Ảnh vẫn SigLIP2 768 chiều | `Database Structure.md` mục 9 |
| `chatbot-service` chứa product search, indexer, embedding worker, cổng 8002 nhận gọi từ backend | Chatbot chỉ còn hội thoại; tìm kiếm và index chuyển sang `services/search-service` (:8090) | `WorkFlow.md` mục 8, 10 |
| Thanh toán VNPAY | COD + VietQR, xác nhận qua webhook SePay | `WorkFlow.md` mục 5 |
| Gộp giỏ khách vãng lai khi đăng nhập (`CartMergeService`) | Chưa có trong `sales-service` | `WorkFlow.md` mục 3 |
| MCP chỉ đọc qua `backend/.env` | File `backend/.env` không còn; nếu dùng lại phải trỏ tới DSN mới, ưu tiên role chỉ có quyền đọc | — |

Các mục về BCDK1 (22–23/10) không phụ thuộc kiến trúc, vẫn đúng. Riêng phần mô tả hệ thống trong BCDK1 cần cập nhật ở báo cáo định kỳ sau.

---

## [2026-10-16] Giai đoạn 0: Đọc tài liệu thiết kế và code recommendation cũ

**Phạm vi:** ba mục đọc trong checklist Giai đoạn 0.
- `plan.md` mục 8.3, 13.3, 21.9.
- `docs/database-design-v2.md` mục 5, 6, 8.
- Module `backend/.../modules/recommendation/`.

Phần chạy môi trường local **chưa làm**, xem mục "Vấn đề gặp phải" ở cuối.

### 1. Nhiệm vụ này để làm gì?

Trước khi viết bất kỳ migration hay thuật toán nào, cần biết ba điều:
- **Nhóm đã chốt gì**: bảng nào, cột nào, metric nào.
- **Cái gì đã có sẵn trong code**: tránh làm lại, tránh đặt tên lệch.
- **Chỗ nào tài liệu còn thiếu hoặc mâu thuẫn**: cần hỏi nhóm trước khi code.

Nếu bỏ qua bước này, rất dễ viết V10 sai so với thiết kế. Một ví dụ cụ thể: dùng tên event `PRODUCT_VIEW` trong khi DB chỉ chấp nhận `VIEW`.

### 2. Cách làm

1. Tìm vị trí các mục cần đọc bằng cách liệt kê heading (`grep "^#"`), sau đó chỉ đọc đúng các mục đó, không đọc tràn lan cả file 1600 dòng.
2. Đối chiếu tài liệu với **code thật**. Tài liệu có thể đã cũ, còn migration SQL mới là thứ chạy trong DB:
   - Đọc DDL của `user_interactions` trong `V3__create_1b_commerce_core.sql`.
   - Đọc cấu hình Flyway trong `backend/src/main/resources/application.yml`.
3. Đọc lướt module recommendation cũ để biết nó từng làm gì và có thể tái sử dụng phần nào.
4. Ghi lại những điểm lệch và những câu hỏi cần nhóm quyết định.

### 3. Kiến thức rút ra

**a) Ba mức thuật toán (`plan.md` mục 8.3)**

| Mức | Ý tưởng | Vai trò |
|---|---|---|
| Popularity | Gợi ý sản phẩm được tương tác nhiều nhất | **Baseline bắt buộc**: mọi thuật toán khác phải thắng nó mới có giá trị |
| Content-based | Gợi ý sản phẩm có nội dung giống, đo bằng cosine giữa embedding BGE-M3 | Giải quyết **cold-start** (sản phẩm hoặc khách mới chưa có lịch sử) |
| Association/behavior | "Mua A thường mua B" | Chỉ chọn làm cấu hình chính nếu thắng baseline trên temporal split |

Collaborative filtering phức tạp **không** làm, vì dữ liệu hành vi quá ít: 30 sản phẩm, chưa có người dùng thật.

**b) Đánh giá (`plan.md` mục 13.3 và `database-design-v2.md` mục 8)**

- **Temporal split** (chia theo thời gian): train bằng quá khứ, test bằng tương lai.
  - Không chia ngẫu nhiên, vì như vậy hành vi "tương lai" có thể lọt vào train. Hiện tượng này gọi là **data leakage** và làm metric đẹp giả.
- **Metric**: Precision@K, Recall@K, HitRate@K, NDCG@K, cộng thêm **Coverage** (tỉ lệ sản phẩm trong catalog từng được gợi ý).
  - Coverage giúp phát hiện mô hình chỉ gợi ý mãi vài sản phẩm hot.
  - Mission.md ban đầu chưa có Coverage, cần bổ sung.
- **Thang nhãn 0–3**:
  - Precision và Recall coi sản phẩm là liên quan khi `grade >= 2`; NDCG dùng nguyên thang.
  - Mapping dự kiến: `PURCHASE=3`, `ADD_TO_CART=2`, `VIEW=1`. Như vậy chỉ xem thì **không** tính là liên quan khi đo Precision/Recall, nhưng vẫn góp một ít điểm vào NDCG.
- **Hạn chế cần nêu khi bảo vệ**: nhãn suy ra từ hành vi quan sát được. Sản phẩm khách không click **không có nghĩa** là khách không thích, có thể khách chưa từng thấy nó.

**c) Bảng `user_interactions`: nguồn dữ liệu hành vi đã có ở V3**

- Cột chính: `event_id`, `occurred_at`, `user_id` / `anonymous_id`, `event_type`, `product_id`, `variant_id`, `request_id`, `source`, `data_source`, `metadata`.
- Các ràng buộc quan trọng:
  - Phải có ít nhất `user_id` hoặc `anonymous_id`.
  - `data_source` ∈ {`REAL`, `DEMO`, `SYNTHETIC`}, để báo cáo không trộn dữ liệu thật với dữ liệu giả lập.
  - Bảng **append-only**: chỉ thêm dòng, không sửa lịch sử.
- **Điểm lệch phát hiện được**: DB dùng `VIEW` và `WISHLIST`, còn `plan.md` ghi `PRODUCT_VIEW` và `ADD_TO_WISHLIST`. **Quy ước từ nay: code theo DB**, vì CHECK constraint sẽ từ chối tên khác.

**d) Bốn bảng V10 (`database-design-v2.md` mục 6)**

| Bảng | Để làm gì |
|---|---|
| `recommendation_models` | Mỗi thuật toán + cấu hình là một dòng (`algorithm`, `version`, `config`, mốc và checksum dữ liệu train). Nhờ đó có thể **tái lập** kết quả cũ. |
| `recommendation_requests` | Mỗi lần gợi ý: dùng model nào, ngữ cảnh gì, mất bao nhiêu ms |
| `recommendation_items` | Danh sách sản phẩm đã trả về: thứ hạng, điểm, lý do |
| `usage_events` | Đo chi phí và độ trễ AI (dùng chung với Dũng và Hải) |

- `product_id` trong schema `ai_retrieval` là **tham chiếu logic, không có FK** sang `public.products`. Đây là quy tắc "không FK xuyên schema", để mỗi phần (Commerce, AI) độc lập về dữ liệu.
- Thiết kế chưa liệt kê giá trị của `context_type`. Đề xuất: `PRODUCT_DETAIL`, `HOME`, `CART`.

**e) Module recommendation cũ (đang tắt)**

- Bị loại khỏi Spring component scan trong `EcommerceBackendApplication.java:16`, nên không chạy. **Không bật lại**, vì nó dùng bảng của schema cũ.
- Thuật toán cũ là **association rules** đào từ đơn hàng:
  - Ngưỡng: `support ≥ 0.01`, `confidence ≥ 0.10`, `lift ≥ 1.0`, tối thiểu 2 giao dịch.
  - *Support*: tỉ lệ đơn chứa cả A và B. *Confidence*: P(B | A). *Lift* > 1: A và B đi cùng nhau nhiều hơn mức ngẫu nhiên.
- **Có thể tái sử dụng**:
  - Quy tắc "sản phẩm còn bán được" = variant `ACTIVE` **và** `stock_quantity - reserved_quantity > 0` (`RecommendationService.java:567`). Đây chính là bộ lọc hết hàng/ngừng bán mà nhiệm vụ yêu cầu.
  - Fallback "cùng category và brand, thiếu thì bù sản phẩm active khác".

### 4. Các quyết định cần nhóm chốt (kèm đề xuất)

| Vấn đề | Lý do phải hỏi | Đề xuất |
|---|---|---|
| Số thứ tự migration | Flyway đang cấu hình `out-of-order: false` và `validate-on-migrate: true`. Nếu V10 lên DB trước, V8 và V9 tạo sau sẽ bị **từ chối**. | Không bật outOfOrder. Đánh số theo thứ tự merge: ai merge trước lấy số kế tiếp. |
| Ai tạo schema `evaluation` | Thiết kế xếp vào "migration 1D", file phân công chưa ghi người làm | Trước mắt lưu split/manifest ra file JSON có checksum trong `evidence/recommendation/`, import vào DB sau |
| Service đặt ở đâu | — | Module trong `chatbot-service` (Python), endpoint `POST /internal/v1/recommendations`, backend làm proxy |

### 5. Công nghệ liên quan

- **Flyway**: công cụ quản lý migration SQL theo số version (V1, V2…). Nó chạy lần lượt từng file và ghi lịch sử vào bảng `flyway_schema_history`.
- **pgvector**: extension PostgreSQL lưu vector embedding và tính cosine. Content-based sẽ dùng nó.
- **BGE-M3**: mô hình embedding văn bản, cho ra vector 1024 chiều đã chuẩn hóa L2. Embedding sản phẩm đã có sẵn trong `ai_retrieval.product_text_embeddings`.

### 6. Vấn đề gặp phải

Phần "chạy môi trường local" chưa làm được vì thiếu ba thứ:
- Docker daemon chưa chạy, nên chưa có Redis và RabbitMQ.
- Chưa có `backend/.env`. File này chứa thông tin kết nối DB, là secret nên phải lấy từ nhóm.
- Chưa có `chatbot-service/.venv` để chạy script kiểm tra embedding.

Sẽ ghi tiếp mục mới khi hoàn tất phần này.

---

## [2026-10-16] Giai đoạn 0: Cài môi trường Python cho `chatbot-service`

### 1. Nhiệm vụ này để làm gì?

Service recommendation sẽ là một module Python trong `chatbot-service`. Content-based cần đọc embedding BGE-M3, và script kiểm tra embedding (`backfill-product-embeddings.ps1 -Verify`) cũng chạy bằng Python. Vì vậy cần một môi trường Python có đủ thư viện, đúng phiên bản nhóm đã chốt, thì mới chạy được code, chạy test và kiểm tra dữ liệu.

### 2. Cách làm

1. Tạo **virtual environment** (venv): `python -m venv .venv` trong thư mục `chatbot-service`.
2. Cài thư viện theo ba file, đúng thứ tự trong README:
   - `requirements.txt`: thư viện chạy service (FastAPI, asyncpg, httpx, redis...).
   - `requirements-test.txt`: thư viện test (pytest...).
   - `requirements-embeddings.txt`: thư viện tính embedding (torch, transformers, sentence-transformers).
3. Kiểm tra lại:
   - Import thử các thư viện chính và in phiên bản.
   - Chạy toàn bộ test hiện có: `pytest`, kết quả **242 passed**.
   - Xác nhận `.venv` đã nằm trong `.gitignore` nên không bị commit lên Git.

### 3. Tại sao làm như vậy?

- **Tại sao dùng venv?** Mỗi dự án có bộ thư viện riêng trong thư mục `.venv`, không đụng tới Python chung của máy. Nhờ vậy dự án khác cần phiên bản khác cũng không bị xung đột.
- **Tại sao giữ đúng phiên bản trong `requirements-embeddings.txt`?** File ghi chú rõ: phiên bản thư viện là một phần của "danh tính" index. Đổi phiên bản transformers hoặc torch có thể làm vector lệch nhỏ so với vector đã lưu trong DB. Khi đó cosine giữa vector cũ và vector mới không còn so sánh công bằng, và kết quả mất tính **tái lập**.
- **Tại sao dùng torch bản CPU dù máy có GPU GTX 1650 Ti?**
  - Bản pin trên PyPI cho Windows mặc định là CPU (`2.7.1+cpu`).
  - Catalog chỉ có 30 sản phẩm, embedding chạy theo batch một lần, nên CPU đủ nhanh.
  - GPU chỉ có 4 GB VRAM, cần để dành cho Qwen (chatbot của Dũng). Thiết kế cũng ghi rõ các model không được giả định cùng nằm trên GPU.

### 4. Kết quả

| Thư viện | Phiên bản |
|---|---|
| Python | 3.11.7 |
| torch | 2.7.1+cpu |
| sentence-transformers | 5.1.1 |
| transformers | 4.57.1 |
| numpy | 2.2.6 |

Test hiện có của `chatbot-service`: **242/242 passed**.

### 5. Việc còn lại của Giai đoạn 0

- Mở Docker Desktop, rồi chạy `docker compose up -d redis rabbitmq`.
- Lấy `backend/.env` (thông tin kết nối DB) từ nhóm, rồi chạy `.\backfill-product-embeddings.ps1 -Verify` để xác nhận 30 sản phẩm có embedding `READY`. Nếu DB là Supabase dùng chung thì chỉ chạy `-Verify` hoặc `-CoverageOnly` (chỉ đọc), không chạy backfill ghi dữ liệu.

---

## [2026-10-16] Giai đoạn 0: Chạy Redis và RabbitMQ bằng Docker

### 1. Nhiệm vụ này để làm gì?

Backend và `chatbot-service` cần hai hạ tầng phụ trợ khi chạy local:
- **Redis**: kho dữ liệu key-value trong RAM. Dự án dùng cho cache, rate limit và context hội thoại ngắn hạn. Sau này có thể dùng để cache kết quả recommendation và chặn spam event `RECOMMENDATION_CLICK`.
- **RabbitMQ**: hàng đợi tin nhắn (message broker). Khi admin sửa sản phẩm, backend ghi event vào `integration_outbox` rồi gửi qua RabbitMQ. Các worker (product search indexer, visual search) nhận event đó để cập nhật lại index và embedding.

Thiếu hai dịch vụ này thì backend khởi động lỗi hoặc thiếu chức năng. Khi đó không chạy thử được luồng end-to-end.

### 2. Cách làm

1. Chạy `docker compose up -d redis rabbitmq`. Lệnh này đọc `docker-compose.yml` ở thư mục gốc và chỉ khởi động đúng hai service cần thiết, không bật cả cụm. `-d` nghĩa là chạy nền.
2. Docker tự tạo network `smart_sportwear_shop_default` và hai volume `redis_data`, `rabbitmq_data`. Volume giữ dữ liệu lại khi container tắt rồi bật lại.
3. Kiểm tra:
   - `docker compose ps` cho thấy cả hai ở trạng thái **healthy**, nhờ healthcheck khai báo trong compose.
   - `redis-cli ping` trả về `PONG`.
   - `rabbitmqctl list_queues` liệt kê đủ các queue, ví dụ `product-search.indexing` kèm queue retry (30s/5m/1h) và `dlq`. Các queue này được nạp tự động từ `rabbitmq/definitions.json`.

### 3. Tại sao dùng Docker?

- Không phải cài Redis/RabbitMQ trực tiếp lên Windows (Redis không hỗ trợ Windows chính thức).
- Cả nhóm dùng **cùng một phiên bản**, khai báo trong compose: `redis:7-alpine` và `rabbitmq:3.13-management-alpine`. Nhờ vậy tránh lỗi kiểu "máy tôi chạy được".
- Dễ dọn dẹp: `docker compose down` là tắt hết.

### 4. Kiến thức liên quan (dùng khi bảo vệ)

- **Retry queue và DLQ (dead-letter queue)**: worker xử lý event lỗi thì event được đẩy sang queue retry, thử lại sau 30 giây, rồi 5 phút, rồi 1 giờ. Vẫn lỗi thì chuyển vào DLQ để người kiểm tra. Cách này tránh mất event và tránh vòng lặp lỗi vô hạn.
- **Outbox pattern**: event được ghi vào bảng `integration_outbox` **trong cùng transaction** với thay đổi nghiệp vụ, sau đó mới publish lên RabbitMQ. Như vậy không xảy ra tình huống DB đã lưu mà event bị mất, hoặc ngược lại.

### 5. Vấn đề gặp phải

`rabbitmq-diagnostics check_port_connectivity` báo lỗi "'rabbit' app chưa chạy", vì lệnh được gọi khi RabbitMQ còn đang khởi động. Ngay sau đó `rabbitmqctl list_queues` đọc được đủ queue, chứng tỏ broker đã sẵn sàng. Đây là lỗi do gọi lệnh quá sớm, không phải lỗi cấu hình.

Ghi chú: user/password RabbitMQ local mặc định là `visual_search` / `change-me`, lấy từ biến môi trường trong compose. Giá trị này chỉ dùng cho local, không dùng cho môi trường thật.

---

## [2026-10-16] Giai đoạn 0: Kiểm tra embedding sản phẩm (`backfill-product-embeddings.ps1 -Verify`)

### 1. Nhiệm vụ này để làm gì?

Thuật toán **content-based** sẽ gợi ý dựa trên độ giống nhau (cosine) giữa vector embedding BGE-M3 của các sản phẩm. Muốn kết quả đúng thì phải chắc rằng:
- Sản phẩm nào cũng có vector. Thiếu vector thì sản phẩm đó không bao giờ được gợi ý.
- Vector được tạo bằng đúng model và phiên bản đã chốt.
- Vector đã chuẩn hóa L2 và không bị cũ so với mô tả sản phẩm hiện tại.

Nói cách khác, đây là bước **kiểm tra chất lượng dữ liệu đầu vào** trước khi xây thuật toán.

### 2. Cách làm

1. **Kiểm tra an toàn trước khi chạy.** `backend/.env` trỏ tới **Supabase dùng chung**, nên mình đọc code để chắc lệnh không ghi gì vào DB:
   - Trong `chatbot-service/scripts/backfill_product_embeddings_v2.py`, khi có `--verify` thì script gọi `coverage()` và `verify()` rồi `return` ngay, không đi vào nhánh tạo job hay ghi embedding.
   - Trong `chatbot-service/app/embeddings/worker.py`, hai hàm này chỉ chứa câu `SELECT`.
   - Kết luận: lệnh này **chỉ đọc**, an toàn với DB chung.
2. Chạy `.\backfill-product-embeddings.ps1 -Verify` từ thư mục gốc. Script PowerShell làm ba việc:
   - Đọc `DB_HOST`, `DB_USERNAME`… từ `backend/.env`.
   - Ghép thành chuỗi kết nối `DB_WRITE_URL`, có mã hóa URL cho mật khẩu.
   - Gọi script Python bằng `chatbot-service/.venv`.
3. Đọc kết quả JSON và đối chiếu với từng tiêu chí.

### 3. Kết quả và ý nghĩa từng chỉ số

**Độ phủ (coverage):**

| Chỉ số | Giá trị | Ý nghĩa |
|---|---|---|
| `text_total` / `text_ready` | 36 / 36 | 100% sản phẩm ACTIVE có vector văn bản BGE-M3 |
| `image_total` / `image_ready` | 81 / 81 | 100% ảnh có vector SigLIP2 (phần của Hải) |

**Model đang ACTIVE**, khớp với runtime đã khóa trong `database-design-v2.md`:
- `BAAI/bge-m3`, revision `5617a9f6…`, 1024 chiều, tiền xử lý `product-document-v1-max512`.
- `google/siglip2-base-patch16-224`, revision `75de2d55…`, 768 chiều.

**Các "guard" (cảnh báo lỗi), tất cả đều bằng 0:**

| Guard | Kiểm tra gì |
|---|---|
| `bad_text_l2`, `bad_image_l2` | Vector có độ dài khác 1 (chưa chuẩn hóa L2) |
| `stale_text_ready`, `stale_image_ready` | Vector đánh dấu READY nhưng hash mô tả/ảnh đã thay đổi, tức vector đã cũ |
| `failed_text`, `failed_image` | Sản phẩm tạo embedding bị lỗi |
| `open_jobs` | Job indexing đang chạy dở |
| `ann_indexes` | Số index gần đúng (HNSW/IVFFlat). Bằng 0 là đúng thiết kế: catalog nhỏ dùng cosine chính xác |

**Kiểm tra cosine:** `text_self_similarity = 1.0`. Lấy một vector so với chính nó cho cosine bằng 1, chứng tỏ hàm cosine trên pgvector hoạt động đúng.

### 4. Kiến thức liên quan (dùng khi bảo vệ)

- **Embedding**: biến mô tả sản phẩm thành một vector số. Hai sản phẩm có nội dung giống nhau thì vector nằm gần nhau.
- **Chuẩn hóa L2**: chia vector cho độ dài của nó để độ dài bằng 1. Khi đó cosine similarity chỉ còn là tích vô hướng, tính nhanh hơn và không bị ảnh hưởng bởi độ dài mô tả.
- **Tại sao cần `model_version` là revision cụ thể (commit hash)?** Tên model như `bge-m3` có thể được cập nhật trên HuggingFace. Ghi commit hash giúp người khác tạo lại **đúng y** các vector cũ, đúng nguyên tắc tái lập của nhóm.
- **Exact cosine và ANN**: với 36 sản phẩm, so sánh với tất cả (exact) mất chưa tới vài ms. Index gần đúng (ANN) chỉ cần khi catalog lên hàng nghìn sản phẩm trở lên, mà lại có thể bỏ sót kết quả.

### 5. Vấn đề và phát hiện

- **Số sản phẩm là 36, không phải 30** như `Mission.md` và tài liệu onboarding ghi. Catalog đã được bổ sung sau khi viết tài liệu. Khi sinh dữ liệu synthetic và tính Coverage phải dùng **số thực tế lấy từ DB** (hoặc manifest catalog), không hard-code 30.
- Không có lỗi khi chạy. Chưa có ai chạy backfill ghi dữ liệu, nên DB chung không bị thay đổi.

---

## [2026-10-16] Giai đoạn 0: Chạy backend Spring Boot ở local

### 1. Nhiệm vụ này để làm gì?

Backend (Java Spring Boot) là nơi:
- Có các điểm cần gắn ghi hành vi ở Giai đoạn 3: xem sản phẩm, thêm giỏ, đặt hàng.
- Sẽ có endpoint public `/api/v1/.../recommendations` gọi sang service AI ở Giai đoạn 9.

Cần chạy được backend ở local để thử luồng thật và kiểm tra code sẽ viết sau này.

### 2. Cách làm

1. **Kiểm tra an toàn trước.** Spring Boot thường tự chạy **Flyway migrate** khi khởi động, mà `backend/.env` trỏ tới **Supabase dùng chung**. Nếu Flyway chạy, migration local có thể bị đẩy lên DB của cả nhóm. Mình đọc `backend/.env` và thấy `SPRING_FLYWAY_ENABLED=false`, nên backend chỉ đọc và ghi dữ liệu như bình thường, **không đổi schema**.
2. **Cài JDK riêng của dự án.** `start-backend.ps1` bắt buộc dùng `.tools/jdk-21`, không dùng Java cài sẵn trên máy. Mình chạy `setup-local-tools.ps1` để tải **Eclipse Temurin JDK 21.0.12** từ Adoptium.
   - Lý do: cả nhóm dùng cùng một bản Java, tránh lỗi do lệch phiên bản.
3. Chạy `.\start-backend.ps1` ở chế độ nền. Script này:
   - Nạp biến môi trường từ `backend/.env`.
   - Đặt cổng `8082`.
   - Kiểm tra Redis đã chạy chưa (nên phải bật Redis trước).
   - Gọi `mvnw spring-boot:run`.
4. Kiểm tra kết quả:
   - Log có dòng `Started EcommerceBackendApplication in 15.9 seconds`.
   - `GET /actuator/health` trả về `{"status":"UP"}`.
   - `GET /api/v1/products?limit=1` trả về `total: 36`, khớp với số sản phẩm ở bước kiểm tra embedding.

### 3. Công nghệ liên quan

- **Spring Boot**: framework Java dựng REST API. Khi khởi động, nó tự tạo các bean (controller, service, repository), kết nối DB qua JPA/Hibernate và kết nối Redis.
- **Maven Wrapper (`mvnw`)**: tự tải đúng phiên bản Maven của dự án, không cần cài Maven lên máy.
- **Spring Actuator** (`/actuator/health`): endpoint báo trạng thái ứng dụng, dùng để kiểm tra nhanh "app có sống không".
- **Component scan filter**: trong `EcommerceBackendApplication.java`, các module cũ (`recommendation`, `wishlist`, `promotion`…) bị loại khỏi scan bằng regex. Vì vậy chúng có trong code nhưng **không chạy**. Đó là lý do module recommendation cũ đang "tắt".

### 4. Vấn đề gặp phải

- **Thiếu JDK dự án**: `.tools/jdk-21` chưa có. Đã giải quyết bằng `setup-local-tools.ps1`.
- **API trả `total: 0` ở lần gọi đầu**: lúc đầu tưởng backend không đọc được dữ liệu. Thực ra mình gọi `?size=1`, mà trong API này `size` là **bộ lọc kích cỡ quần áo** (S/M/L, 40/41…), không phải số bản ghi mỗi trang. Tham số phân trang đúng là `limit`. Gọi lại `?limit=1` thì ra `total: 36`.
  - Bài học: khi viết endpoint recommendation, đặt tên tham số rõ ràng (`limit` cho số lượng gợi ý), tránh trùng với tên bộ lọc thuộc tính.

### 5. Trạng thái

Backend đang chạy tại `http://localhost:8082`, dùng Supabase chung với Flyway tắt. Redis và RabbitMQ chạy bằng Docker.

---

## [2026-10-16] Giai đoạn 0: Chạy `chatbot-service` và storefront

### 1. Nhiệm vụ này để làm gì?

- **`chatbot-service`** (Python, FastAPI, cổng 8002) là nơi sẽ đặt service recommendation: module mới với endpoint `POST /internal/v1/recommendations`. Cần chạy được nó để biết cách service khởi động, cách kết nối DB và cách xác thực internal token. Endpoint recommendation sau này sẽ làm giống hệt.
- **Storefront** (Next.js, cổng 3000) là giao diện khách hàng. Sau này sẽ hiển thị block "Sản phẩm tương tự" ở trang `/sanpham/[slug]` và "Gợi ý cho bạn" ở trang chủ, đồng thời gửi event impression/click.

### 2. Cách làm: `chatbot-service`

1. **Tạo `chatbot-service/.env`**: máy chưa có file này. Mình viết một script Python nhỏ:
   - Copy `.env.example`.
   - Lấy thông tin DB từ `backend/.env` để ghép thành `DB_READ_URL`, dạng `postgresql+asyncpg://user:pass@host:port/db?sslmode=...`, có mã hóa URL cho user và mật khẩu.
   - Copy `JWT_ACCESS_SECRET` và `PRODUCT_SEARCH_INTERNAL_TOKEN` từ backend. Token này **bắt buộc giống nhau ở hai bên** thì backend mới gọi được service.
   - Script không in secret ra màn hình. File `.env` đã nằm trong `.gitignore`.
2. **Cấu hình an toàn với Supabase dùng chung:**
   - `DB_WRITE_URL` để trống: tắt lưu lịch sử chat vào DB, service chỉ đọc.
   - `PRODUCT_SEARCH_INDEXING_ENABLED=false` và `CATALOG_INDEX_BACKFILL_ON_STARTUP=false`: không cho worker indexing ghi lại read model lúc khởi động.
   - `ANTHROPIC_API_KEY` và `OPENAI_API_KEY` để trống: chưa cần LLM cho recommendation.
3. Chạy `.\start-chatbot-service.ps1`. Script này gọi `.venv\Scripts\python.exe run.py`, và `run.py` khởi động `uvicorn app.main:app`.
4. Kiểm tra:
   - `GET /health` trả về `{"status":"ok"}`.
   - Gọi thử `POST /internal/v1/product-search` có header `X-Internal-Token`: xác thực token thành công, request được xử lý.

### 3. Cách làm: storefront

1. `start-storefront.ps1` đòi Node riêng của dự án ở `.tools/node-v24.14.1-win-x64`, nhưng `setup-local-tools.ps1` **chỉ tải JDK, không tải Node**. Máy đã có Node **24.19** (cùng major 24), nên mình chạy trực tiếp bằng Node của máy, làm đúng các bước của script:
   - `npm ci`: cài đúng phiên bản trong `package-lock.json`.
   - `npm run dev`: chạy Next.js 16 với Turbopack.
2. Kiểm tra:
   - Trang chủ trả HTTP 200 và có tên sản phẩm thật, ví dụ "England 2026 Stadium Home Jersey".
   - Trang chi tiết `/sanpham/ao-dau-anh-2026-home-ib5290-100` trả HTTP 200.
   - Lần tải đầu mất khoảng 60 giây vì Next.js dev phải biên dịch trang lần đầu. Các lần sau nhanh hơn nhiều (khoảng 7 giây).

### 4. Công nghệ liên quan

- **FastAPI và uvicorn**: FastAPI là framework Python dựng API, tự kiểm tra dữ liệu đầu vào bằng Pydantic (sai kiểu thì trả lỗi 422). Uvicorn là web server chạy ứng dụng FastAPI theo kiểu bất đồng bộ (async).
- **pydantic-settings**: đọc cấu hình từ `.env` vào class `Settings` (`app/config/settings.py`), có kiểu dữ liệu và giá trị mặc định.
- **Internal token so sánh bằng `hmac.compare_digest`**: so sánh chuỗi với thời gian không đổi, chống tấn công đoán token dựa vào thời gian phản hồi (timing attack). Endpoint recommendation sẽ dùng y hệt cách này.
- **asyncpg**: driver PostgreSQL bất đồng bộ cho Python, tạo pool kết nối khi service khởi động.
- **Next.js (App Router) và Turbopack**: framework React, render trang ở server. Storefront lấy dữ liệu từ backend `:8082`, cấu hình trong `frontend/storefront/.env.local`.

### 5. Vấn đề gặp phải

| Vấn đề | Nguyên nhân | Xử lý |
|---|---|---|
| Thiếu `chatbot-service/.env` | Chưa từng cấu hình trên máy này | Sinh tự động từ `.env.example` và `backend/.env` |
| Thiếu Node dự án `.tools/node-v24.14.1` | `setup-local-tools.ps1` không tải Node | Dùng Node 24.19 của máy (cùng major). Nên báo nhóm bổ sung Node vào script setup |
| Gọi product search bằng chữ có dấu bị lỗi `error parsing the body` | Git Bash trên Windows gửi chữ tiếng Việt sai encoding | Thử lại bằng query không dấu. Đây là lỗi công cụ test, không phải lỗi service |
| **Product search trả 0 kết quả** (log ghi `keyword_branch_failed`) | `app/repositories/product_repository.py` vẫn query theo **schema cũ** (`p.sport_type`, `p.gender`, `pv.size`…). Schema V2 không còn các cột này | **Không sửa**: thuộc phần text search của Dũng. Ghi lại để báo nhóm |

**Bài học cho phần recommendation:** code Python mới của mình phải query theo **schema V2**:
- Đọc thuộc tính sản phẩm từ `ai_retrieval.product_search_documents` (`category_code`, `gender_code`, `sport_codes`…).
- Đọc vector từ `ai_retrieval.product_text_embeddings`.
- Kiểm tra tồn kho bằng `product_variants.status` và `stock_quantity - reserved_quantity`.
- Không tái sử dụng `product_repository.py` cũ.

### 6. Trạng thái môi trường local

| Thành phần | Địa chỉ | Trạng thái |
|---|---|---|
| Redis / RabbitMQ | 6379 / 5672 | Docker, healthy |
| Backend | http://localhost:8082 | UP, Flyway tắt |
| chatbot-service | http://localhost:8002 | UP, chỉ đọc DB |
| Storefront | http://localhost:3000 | UP |

---

## [2026-10-16] Kiến thức nền: Schema `evaluation` và nơi đặt service recommendation

> Mục này không phải một nhiệm vụ code, mà giải thích hai quyết định còn mở của Giai đoạn 0, để hiểu vì sao phải hỏi nhóm.

### 1. Schema `evaluation` là gì?

**Schema** trong PostgreSQL giống một **thư mục** chứa các bảng. DB của dự án chia thành nhiều schema theo mục đích:

| Schema | Chứa gì | Ví dụ bảng |
|---|---|---|
| `public` | Dữ liệu bán hàng | `products`, `orders`, `user_interactions` |
| `ai_retrieval` | Dữ liệu AI **khi chạy thật** | embedding, `recommendation_requests` (mỗi lần web gợi ý cho khách) |
| `assistant` | Lịch sử chatbot | `chat_sessions`, `chat_messages` |
| `evaluation` | Dữ liệu **chấm điểm AI cho luận văn** | `datasets`, `recommendation_splits`, `evaluation_runs`, `evaluation_metrics` |

**Mục đích của `evaluation`:** chứng minh kết quả đánh giá là thật và **tái lập được**. Ví dụ với recommendation:
- `recommendation_splits`: train bằng dữ liệu đến ngày X, test từ ngày Y đến ngày Z, file dữ liệu có checksum…
- `evaluation_runs`: lần chạy nào, thuật toán gì, commit git nào, cấu hình gì.
- `evaluation_metrics`: lần chạy đó đạt Precision@10, NDCG@10… bao nhiêu.

Khi hội đồng hỏi "con số này lấy ở đâu, chạy lại có ra giống không?", các bảng này là bằng chứng.

**Phân biệt với `ai_retrieval.recommendation_*`:**
- `ai_retrieval.recommendation_*` ghi lại **vận hành thật**: khách nào được gợi ý gì. Dữ liệu này tự xóa sau 30 ngày.
- `evaluation` ghi lại **thí nghiệm offline** dùng để viết báo cáo. Kết quả TEST không bao giờ bị xóa.

**Vì sao phải hỏi nhóm:** schema này dùng chung cho cả ba người (Dũng chấm text search, Hải chấm image search, Quyền chấm recommendation), nhưng file phân công chưa ghi ai viết migration tạo nó.

**Đề xuất:** trong lúc chưa có schema, lưu split, manifest và metric ra file JSON có checksum SHA-256 trong `evidence/recommendation/`, sau này import vào DB. Nhờ vậy tiến độ không bị chặn.

### 2. "Đặt service recommendation ở đâu" nghĩa là gì?

Hệ thống gồm nhiều chương trình chạy riêng, mỗi cái một cổng:

```
Storefront (Next.js, :3000)        ← khách nhìn thấy
      │
      ▼
Backend (Java Spring, :8082)       ← giỏ hàng, đơn hàng, sản phẩm
      │
      ▼
chatbot-service (Python, :8002)    ← AI: tìm kiếm, chatbot, embedding
```

Code tính gợi ý (popularity, cosine embedding…) phải nằm trong **một** chương trình nào đó:

| Lựa chọn | Ưu điểm | Nhược điểm |
|---|---|---|
| A. Java trong backend | Gần dữ liệu đơn hàng và tồn kho; module cũ cũng ở đây | Java tính vector và viết script đánh giá (numpy) bất tiện |
| **B. Python trong `chatbot-service`** (đề xuất) | Embedding, pgvector và numpy đều ở đây; script đánh giá cùng ngôn ngữ; đúng thiết kế trong `plan.md`; product search của Dũng cũng làm theo kiểu này | Backend phải gọi qua HTTP và cần fallback khi service AI lỗi |

**Luồng chạy với lựa chọn B:**
1. Khách mở trang sản phẩm.
2. Storefront gọi backend `GET /api/v1/products/{id}/recommendations`.
3. Backend gọi `chatbot-service` `POST /internal/v1/recommendations`, kèm header `X-Internal-Token`.
4. Python tính danh sách gợi ý và trả về.
5. Backend kiểm tra lại sản phẩm còn ACTIVE và còn hàng, rồi trả cho storefront.
6. Nếu service AI lỗi hoặc timeout, backend tự trả danh sách popularity (fallback). Web không bao giờ bị trống vì AI hỏng.

**Vì sao phải hỏi nhóm:** `chatbot-service` hiện do Dũng làm chính. Thêm module recommendation vào thì hai người cùng sửa một service, và backend cũng phải thêm phần gọi sang.

### 3. Câu hỏi gửi nhóm

1. Ai tạo migration cho schema `evaluation`? Đề xuất tạm lưu kết quả ra file JSON, khi có schema thì import.
2. Đặt recommendation thành module Python trong `chatbot-service` (`POST /internal/v1/recommendations`), backend làm proxy và fallback popularity. Dũng có đồng ý không?
3. Số version migration: không bật outOfOrder, ai merge trước lấy số tiếp theo.

---

## [2026-10-19] Giai đoạn 2 (bản nháp): Quét migration V1–V7, viết nháp V10 và cài MCP đọc DB

### 1. Nhiệm vụ này để làm gì?

- Viết **bản nháp** migration V10 gồm 4 bảng được giao: `recommendation_models`, `recommendation_requests`, `recommendation_items`, `usage_events`.
- Cài một **MCP server chỉ đọc** để Claude truy vấn trực tiếp DB Supabase dùng chung (xem dữ liệu, kiểm tra truy vấn) mà **không thể ghi**.

### 2. Cách làm

1. **Dùng sub-agent Explore** quét toàn bộ migration V1–V7, schema `ai_retrieval`, tài liệu thiết kế và code liên quan. Sub-agent đọc rất nhiều file nhưng chỉ gửi về bản tóm tắt, nên phiên làm việc chính không bị đầy ngữ cảnh.
2. Viết `backend/src/main/resources/db/v2-migration/V10__create_1c_recommendation.sql` theo đúng quy ước của V6 (xem mục 3).
3. Kiểm tra cú pháp bằng `pglast`, chính là parser SQL của PostgreSQL. Kết quả: 19 câu lệnh hợp lệ, thân hàm PL/pgSQL hợp lệ. **Chưa chạy trên PostgreSQL thật** vì Docker chưa bật.
4. Cài MCP `postgres-mcp` (Postgres MCP Pro) vào venv riêng `.tools/mcp-venv` và đăng ký với Claude Code ở **scope local** (chỉ máy này, không commit).

### 3. Quy ước rút ra từ V1–V7 (đã áp dụng cho V10)

| Quy ước | Ví dụ |
|---|---|
| Thư mục migration là `db/v2-migration`, không phải `db/migration` | `V6__create_1c_ai_retrieval_foundation.sql` |
| Khóa chính `uuid` + `gen_random_uuid()`, không dùng bigserial | `id uuid primary key default gen_random_uuid()` |
| Giá trị trạng thái dùng `varchar` + `CHECK`, **không** dùng ENUM | `check (status in ('DRAFT','ACTIVE','RETIRED'))` |
| Tên ràng buộc: `fk_`, `chk_`, `uq_`, `idx_`, `trg_` | `chk_recommendation_items_rank` |
| **Không có FK chéo schema**: `product_id` trong `ai_retrieval` chỉ là tham chiếu logic | `recommendation_items.product_id` không FK sang `public.products` |
| Checksum là `char(64)` + regex hex | `~ '^[0-9a-f]{64}$'` |
| JSON phải là object | `jsonb_typeof(config) = 'object'` |

### 4. Giải thích thiết kế từng bảng

**`recommendation_models`** là "sổ đăng ký mô hình". Mỗi thuật toán + cấu hình là một dòng.
- `UQ (algorithm, version)`: không thể có hai bản `POPULARITY v1`.
- `training_cutoff_at` và `training_event_checksum` **phải đi thành cặp**: mốc cắt dữ liệu train và checksum của chính tập dữ liệu đó.
- Thuật toán học từ hành vi (POPULARITY, ASSOCIATION, HYBRID) khi đã ACTIVE thì **bắt buộc** có checksum. CONTENT_BASED dạng item–item chỉ dùng embedding nên được miễn.
- Trigger `protect_recommendation_model_identity`: model đã rời DRAFT thì **không được sửa** config/checksum, muốn đổi phải tạo version mới. Đây là cách bảo đảm **tái lập kết quả** (C.9 trong Mission.md), học theo `protect_embedding_model_identity` của V6.
- Index unique một phần: mỗi thuật toán chỉ có **một** model ACTIVE.

**`recommendation_requests`** ghi lại mỗi lần web gợi ý cho khách.
- `context_type` ∈ `PRODUCT_DETAIL / HOME / CART`. Riêng `PRODUCT_DETAIL` bắt buộc có `source_product_id`, vì "sản phẩm tương tự" phải có sản phẩm gốc.
- `user_ref` là mã giả danh, không lưu email/tên (tránh PII).
- Index `(served_at)` phục vụ xóa dữ liệu cũ sau 30 ngày. Index `(model_id, served_at desc)` phục vụ thống kê theo model.

**`recommendation_items`** là danh sách sản phẩm được gợi ý trong một request.
- PK `(request_id, rank)`: mỗi vị trí chỉ có một sản phẩm. UQ `(request_id, product_id)`: một sản phẩm không xuất hiện hai lần.
- `ON DELETE CASCADE`: xóa request thì xóa luôn item.
- Index `(product_id)` giúp tính **Coverage** (bao nhiêu sản phẩm khác nhau từng được gợi ý).

**`usage_events`** là bảng **dùng chung** cho cả nhóm, đo chi phí và độ trễ AI.
- Thêm `model_name` (ngoài `model_id` trong tài liệu thiết kế), vì LLM gọi qua API (của Dũng) không có dòng trong bảng model nào.
- `operation` chỉ kiểm định dạng `UPPER_SNAKE`, không khóa danh sách, để Dũng/Hải tự thêm loại thao tác.
- `success = false` thì bắt buộc có `error_code`, và ngược lại.

### 5. MCP chỉ đọc: vì sao và an toàn thế nào

- **MCP** (Model Context Protocol) là chuẩn để gắn công cụ ngoài cho AI. Ở đây công cụ là "truy vấn PostgreSQL".
- DB là **Supabase dùng chung cả nhóm**, nên tuyệt đối không được ghi. Có **hai lớp** bảo vệ:
  1. Chế độ `--access-mode=restricted`: parser SQL chặn mọi lệnh không phải đọc. Đã tự kiểm tra 10 trường hợp: `INSERT`, `UPDATE`, `DELETE`, `DROP`, `CREATE`, `SET` và các mẹo như `commit; drop table` hay `select 1; delete` đều **bị chặn**, còn `SELECT` thì cho qua.
  2. Mỗi truy vấn chạy trong transaction `READ ONLY`.
- Mật khẩu **không** nằm trong cấu hình MCP. Script `.tools/mcp/postgres_readonly.py` đọc `backend/.env` (đã gitignore) lúc khởi động.

### 6. Vấn đề gặp phải

| Vấn đề | Cách xử lý |
|---|---|
| `pip install postgres-mcp` lỗi: cần `pglast==7.2.0` nhưng PyPI không còn bản này cho Windows | Cài `--no-deps`, rồi dùng `pglast 7.21` (cùng dòng 7.x) và đã kiểm tra parser chạy đúng |
| `mcp` 2.x đổi tên `FastMCP` nên server không chạy | Ghim `mcp<2` |
| Thêm `default_transaction_read_only=on` vào chuỗi kết nối nhưng pooler Supabase bỏ qua | Bỏ lớp này; vẫn còn 2 lớp có sẵn của postgres-mcp |
| Docker Desktop chưa bật nên không chạy được test migration trên PostgreSQL local | Tạm kiểm tra cú pháp bằng pglast; test thật để lần sau |

### 7. Phát hiện quan trọng

- Supabase hiện mới chạy tới **V7**. V8/V9 (của Dũng) **chưa có cả file**. Vì `out-of-order: false`, nếu V10 lên trước thì V8/V9 sẽ bị từ chối. **Phải chốt số version với nhóm trước khi merge.**
- V2 **không có bảng wishlist**. Hành vi wishlist chỉ được ghi dưới dạng event `WISHLIST` trong `user_interactions`.
- Storefront hiện **chưa gửi event nào** (VIEW, impression, click). Giai đoạn 3 phải làm từ đầu.

---

## 2026-10-22 — Chép quy định trình bày đồ án ra file Markdown

**Mục đích:** File gốc `3_Phuluc_Hinh thuc va quy cach trinh bay quyen DATN-QD449-09032026.doc` có định dạng Word cũ (.doc nhị phân), nên khó đọc lại nhanh. Mình chép nội dung ra `Quy định viết báo cáo.md` để cả nhóm và Claude tra cứu ngay khi viết báo cáo.

**Cách làm:**
1. Dùng `antiword` (có sẵn trong Git Bash) để chuyển file .doc sang text UTF-8.
2. Đọc toàn bộ text, rồi trình bày lại bằng Markdown theo đúng cấu trúc gốc: I. Bố cục, II. Trình bày, III. Nộp quyển, và 11 mẫu trang.
3. Thêm một checklist nhanh ở cuối file để tự kiểm tra trước khi nộp.

**Vì sao chọn Markdown:** File là text thuần nên đọc được trực tiếp, diff được trong git, và Claude đọc được mà không phải chuyển đổi lại.

**Lưu ý:**
- Logo trên trang bìa là ảnh nên không chép được. Khi cần thì xem bản .doc gốc.
- Bản gốc ghi "lấy từ thực nghiệp". Trong Mẫu 4, mình sửa thành "thực nghiệm" vì đó rõ ràng là lỗi chính tả.

---

## 2026-10-22 — Bổ sung lịch nộp báo cáo của Văn phòng Khoa

**Mục đích:** Lưu lịch nộp và quy tắc đặt tên file của Văn phòng Khoa vào cùng chỗ với quy định trình bày, để tra một nơi là đủ.

**Cách làm:** Thêm mục **IV. Lịch nộp file báo cáo** vào `Quy định viết báo cáo.md`, viết dạng bảng gồm 4 đợt: BCDK1, BCDK2, BCBV, BCCK + BCTT. Mỗi đợt ghi ngày nộp, nội dung, mẫu tên file và ví dụ. Lịch này cũng được lưu vào memory của Claude để khi lên kế hoạch thì đối chiếu với mốc gần nhất.

---

## 2026-10-22 — Soạn khung Báo cáo định kỳ 1 (BCDK1)

**Mục đích:** Có sẵn file Word đúng quy cách Phụ lục 3 để nhóm điền nội dung và nộp ngày 24–25/10/2026.

**Cách làm:**
1. Lấy thông tin từ các nguồn có sẵn:
   - Bìa: báo cáo thực tập (`deliverables/Bao_cao_TTTN_...FINAL.docx`) có GVHD, MSSV, lớp và logo.
   - Phân công: `Phân chia nhiệm vụ AI.docx`.
   - Tiến độ: `git log` từ tháng 10 (V2 database, catalog pilot, indexer, embedding), `Summary.md`, `Mission.md`.
   - Số liệu sơ bộ: `evidence/`.
2. Viết script Node.js dùng thư viện `docx` để tạo file theo đúng quy cách:
   - Giấy A4; lề trên, dưới, phải 2 cm; lề trái 3 cm; header/footer 1 cm.
   - Times New Roman 13, căn đều hai bên, giãn dòng 1,15.
   - Trang i, ii, iii cho phần đầu; trang 1, 2, 3 từ Mở đầu trở đi.
   - Header trái "Đồ án tốt nghiệp Đại học", phải ghi tên chương. Footer trái ghi tên sinh viên và lớp, phải ghi số trang.
3. Mỗi chương là một section riêng, để header bên phải hiện đúng tên chương đó.
4. Xuất PDF qua Word để xem lại từng trang trước khi giao.

**Quy ước trong file:** Chữ **tô vàng, nghiêng, trong ngoặc vuông** là chỗ nhóm cần bổ sung. Các phần của Dũng và Hải chỉ để khung.

**Lưu ý:**
- Benchmark ở Chương 5 đo trên kiến trúc cũ, nên trong file đã ghi rõ "kết quả sơ bộ, sẽ đo lại trên V2".
- Tài liệu tham khảo là danh mục đề xuất, cần kiểm tra lại trước khi nộp.

---

## 2026-10-22 — Viết đầy đủ nội dung Báo cáo định kỳ 1

**Mục đích:** Điền hết các chỗ tô vàng trong khung BCDK1, để có bản báo cáo hoàn chỉnh nộp ngày 24–25/10.

**Cách làm:**
1. Đọc code và tài liệu để viết đúng hiện trạng, không suy đoán:
   - Chatbot: đồ thị LangGraph trong `chatbot-service/app/graph/chat_graph.py` (12 node), 9 công cụ trong `app/tools/`, 6 tài liệu trong `knowledge/`.
   - Tìm kiếm văn bản: các module pipeline trong `app/retrieval/product/`.
   - Visual search: `visual-search-service/README.md` và `docs/visual-search-rollout.md`. Bản hiện tại vẫn dùng provider Voyage, đang chuyển sang SigLIP2 local.
   - API: các `@RequestMapping` của backend.
   - Số file test: backend 52, chatbot 28, visual 16.
2. Chèn 9 sơ đồ có sẵn trong `docs/overview/workflow` và `docs/overview/erd`: kiến trúc, ERD, đồng bộ catalog, tìm văn bản, tìm ảnh, chatbot, khuyến nghị.
3. Đặt chú thích bảng và hình bằng style riêng `BangHinh`, để Word tự tạo **Danh mục bảng, sơ đồ, hình** có số trang.
4. Dùng Word (qua COM) cập nhật mục lục và danh mục, lưu ra docx và PDF, rồi xem lại từng trang.

**Vấn đề và cách xử lý:**
- Bảng chữ viết tắt dài bị đẩy sang trang mới, để lại tiêu đề trơ trọi. Nguyên nhân: mọi dòng của bảng đều đặt `keepNext`. Sửa lại chỉ giữ liền khối với bảng ≤ 12 dòng.
- File `deliverables/BCDK1_Nhom.docx` đang mở trong Word nên không ghi đè được. Bản mới lưu thành `BCDK1_Nhom_hoan_chinh.docx`.

**Lưu ý khi bảo vệ:** Benchmark visual search dùng 100 ảnh biến đổi từ chính ảnh catalog. Kết quả này chỉ chứng minh độ bền khi ảnh bị biến đổi, không chứng minh độ chính xác trên ảnh thực tế. Điều này đã được ghi rõ trong báo cáo.

---

## 2026-10-23 — Rà soát BCDK1 theo quy định QĐ449 và báo cáo mẫu N21DCCN078

**Mục đích:** Kiểm tra `deliverables/BCDK1_Nhom_hoan_chinh.docx` trước khi nộp: bố cục, trang bìa, định dạng, và nội dung có đúng với những gì nhóm đã làm hay không.

**Cách làm:**
1. Trích nội dung và định dạng của docx bằng python-docx: lề, header/footer, kiểu đánh số trang của từng section, style, giãn dòng. Xuất PDF qua Word COM để xem bìa và mục lục như khi in.
2. Đọc báo cáo mẫu (PDF 170 trang) bằng PyMuPDF và render trang bìa.
3. Lấy chuẩn là `Quy định viết báo cáo.md` (QĐ449/QĐ-HV, 09/03/2026), không lấy báo cáo mẫu làm chuẩn. Lý do: báo cáo mẫu là khóa luận năm 2025, thiếu Lời cam đoan, Phiếu giao nhiệm vụ và Danh mục viết tắt, header ghi "Khóa luận", nên không theo quy định mới. Báo cáo mẫu chỉ dùng để tham khảo độ sâu nội dung của bản cuối.
4. Đối chiếu từng con số trong báo cáo với nguồn thật:
   - Supabase (MCP chỉ đọc): 36 sản phẩm ACTIVE, 5 danh mục (14/6/6/6/4), Nike + Adidas, 81 ảnh, 36 + 81 embedding READY, Flyway V1–V7 đã chạy, `user_interactions` có 0 dòng và có sẵn `RECOMMENDATION_CLICK`.
   - Code: port (start-*.ps1, .env), 12 node LangGraph, 9 tool, 6 tài liệu tri thức, provider anthropic/openai, các hàng đợi retry 30s/5m/1h, provider visual vẫn là voyage/fake, nội dung V10.
   - Evidence: số liệu benchmark trong `evidence/*.json`.

**Phát hiện chính:**
- "Recall@5" trong `benchmark_product_hybrid_search.py` tính bằng `bool(expected & returned)`, tức là **Hit Rate@5**, không phải Recall@5.
- "Cold latency" bỏ qua các mẫu 0 ms. Hybrid được 7 ms vì kết quả đã nằm trong cache, còn keyword-only không có cache nên đo được 1.722 ms. Vì vậy so sánh độ trễ giữa hai bên là không công bằng.
- Backend có 53 lớp test (gồm 3 test migration) + 1 lớp hỗ trợ. Báo cáo ghi 52 là chưa khớp.
- Các phần còn thiếu: mã nhóm trên bìa, phiếu giao nhiệm vụ, mục lục chưa có các mục phần đầu, công thức chưa đánh số, hình/bảng chưa ghi nguồn.

---

## 2026-10-23 — Sửa định dạng BCDK1 theo QĐ449 và hai dòng benchmark Bảng 5.2

**Mục đích:** Sửa các lỗi định dạng và lỗi số liệu benchmark đã tìm ra ở lần rà soát trước, ngay trong `deliverables/BCDK1_Nhom_hoan_chinh.docx`.

**Cách làm:**
1. Viết script python-docx sửa thẳng XML của docx. Nhờ vậy giữ nguyên mọi phần không liên quan (ảnh, bảng, header/footer). Bản gốc được sao lưu trước khi sửa.
2. Dùng Word (qua COM) dựng lại mục lục và danh mục, đánh lại số trang, lưu docx và xuất PDF để kiểm tra từng trang.

**Đã sửa:**
- **Mục lục:** gắn outline level 1 cho LỜI CẢM ƠN, LỜI CAM ĐOAN, MỤC LỤC, hai DANH MỤC và BẢNG PHÂN CÔNG. Thêm switch `\u` vào field TOC để Word lấy cả các đoạn có outline level, không chỉ Heading.
- **Phiếu giao nhiệm vụ:** chuyển section break ra sau trang phiếu. Trang phiếu nằm chung section với bìa nên không có số trang và header; Lời cảm ơn trở thành trang i, đúng Mẫu 5. Header phần đầu bỏ chữ "Phần mở đầu".
- **Công thức (2.1)–(2.8):** dùng tab giữa và tab phải (vị trí 9071 twip = bề rộng vùng chữ) nên số nằm sát lề phải. Mục 2.7 đổi từ gạch đầu dòng thành 6 công thức đánh số, kèm đoạn "trong đó…".
- **Nguồn hình/bảng:** thêm dòng "(Nguồn: …)" in nghiêng, cỡ 11, dưới 21 chú thích.
- **Danh mục bảng/sơ đồ/hình:** tạo 2 style mới SoDo và Hinh (kế thừa BangHinh). Chia thành 3 field TOC theo thứ tự Bảng → Sơ đồ → Hình như Mẫu 7. "Bảng 0.1" và "Bảng KL.1" bỏ số vì không thuộc chương nào.
- **Viết tắt:** giải nghĩa ở lần xuất hiện đầu tiên (AI, COD, DEV/TEST, HR@K, MRR, NDCG, BGE-M3, CLIP, SigLIP, RAG, ANN, LLM, API, SKU, CRUD, p95, JWT, PII, SSRF, B2C, UUID, HNSW, IVFFlat, RRF, FAQ, REST, SSE, DLQ, ERD, UQ, PK, MCP, ASGI). Thêm 11 dòng vào bảng viết tắt và giữ thứ tự A→Z.
- **Giãn đoạn gạch đầu dòng:** từ 2 pt lên 3 pt theo quy định.
- **Bảng 5.2:** đổi "Recall@5" thành "Hit Rate@5" và bỏ dòng độ trễ. Thêm ghi chú giải thích: script chỉ kiểm tra có giao với tập đáp án; lần đo cũ hybrid đọc cache còn keyword thì không.

**Vấn đề và cách xử lý:**
- "Hiệu năng:" và "Bảo mật:" là run in đậm riêng nên không thay được cả câu. Xử lý bằng cách chỉ thay phần chữ thường phía sau.
- COM không có thuộc tính để bật outline level cho TOC, nên sửa trực tiếp mã field thành `TOC \o "1-3" \h \z \u`.
- Công thức viết kiểu LaTeX (`Σ_{q∈Q}`) khó đọc trong Word. Đổi sang dạng "Σ …, với q chạy trên Q".

**Chưa sửa (ngoài phạm vi yêu cầu):** mã nhóm trên bìa, phiếu có chữ ký, "52 lớp test" (thực tế là 53), ghi model Voyage cho Bảng 5.3, bổ sung tài liệu tham khảo.

---

## 2026-10-23 — BCDK1: sửa số lớp test, ghi model cho Bảng 5.3, bổ sung tài liệu tham khảo

**Mục đích:** Làm nốt ba việc còn lại sau lần rà soát.

**Cách làm và căn cứ:**
1. **Số lớp test.** Đếm file `*Test.java` và `*Tests.java` trong `backend/src/test` được 53 lớp, trong đó có 3 test migration (V2OneA/B/C). Dòng "Backend" trong Bảng 5.1 sửa thành "50 lớp test unit và integration (ngoài 3 lớp test migration ở dòng trên)". Cách ghi này không đếm trùng 3 test migration đã nêu ở dòng trên.
2. **Bảng 5.3.** File kết quả `phase10-benchmark-report.json` không ghi tên model. Ngày chạy benchmark (01/08/2026, commit 2950034), `visual-search-service` chỉ có provider `fake` hoặc `voyage`, với model mặc định `voyage-multimodal-3.5`. p95 ≈ 3 giây cũng phù hợp với việc gọi API bên ngoài, nên đây là kết quả của Voyage. Đã ghi tên model vào tiêu đề bảng và vào câu "Lưu ý" để hội đồng không nhầm với SigLIP2.
3. **Tài liệu tham khảo.** Thêm 3 tài liệu gốc cho những thứ báo cáo có dùng:
   - Cormack và cộng sự (SIGIR 2009) cho RRF.
   - Radford và cộng sự (ICML 2021) cho CLIP.
   - Yang và cộng sự (2024), Qwen2.5 Technical Report.

   Danh sách vẫn xếp theo abc tên tác giả. Vì danh mục đánh số tự động, số của tài liệu cũ bị đổi, nên script đánh lại mọi trích dẫn trong thân bài theo bảng ánh xạ cũ → mới: 2→3, 3→4, 4→5, 5→7, 6→8. Trích dẫn mới được chèn bằng ký hiệu tạm ⟦n⟧ rồi mới đổi thành [n], để không bị ánh xạ nhầm lần nữa.

**Vấn đề:** Khi chèn đoạn code bằng heredoc, chuỗi `r"[\1]"` bị biến thành ký tự điều khiển 0x01, làm lxml báo lỗi. Đã thay bằng hàm lambda để không phụ thuộc vào cách escape.

---

## 2026-10-23 — BCDK1: danh mục bảng/sơ đồ/hình theo thứ tự xuất hiện

**Mục đích:** Theo yêu cầu, danh mục bảng/sơ đồ/hình xếp theo đúng thứ tự xuất hiện trong bài, và chỉ liệt kê những gì đã có thật trong kỳ báo cáo này.

**Cách làm:**
- Gộp ba field TOC (Bảng / Sơ đồ / Hình) thành một field duy nhất: `TOC \h \t "BangHinh,1,SoDo,1,Hinh,1"`. Khi một field TOC lấy nhiều style, Word xếp các mục theo vị trí trong văn bản, nên danh mục tự đúng thứ tự trang.
- Hình 3.3 (ERD dự kiến) và Sơ đồ 3.6 (luồng khuyến nghị dự kiến) chuyển sang style riêng `ChuThichKhongDanhMuc`. Style này có định dạng giống hệt chú thích thường nhưng không nằm trong field TOC. Kết quả: hai hình vẫn ở trong bài, chỉ không xuất hiện trong danh mục.

**Vấn đề:** Khi viết code qua heredoc, `\t` trong chuỗi Python thường bị hiểu thành ký tự tab, làm hỏng mã field. Đã sửa bằng raw string, ghi qua file vá riêng.

**Lưu ý:** Danh mục giờ nhảy từ Hình 3.2 sang các mục khác, không có Hình 3.3 và Sơ đồ 3.6. Ở kỳ báo cáo 2, khi phần khuyến nghị đã làm xong, đổi chú thích hai hình này về style SoDo/Hinh và bỏ chữ "dự kiến".

---

## 2026-10-23 — Kiểm tra độ chính xác các công thức (2.1)–(2.8) trong BCDK1

**Mục đích:** Xác nhận các công thức trong báo cáo đúng về toán học và khớp với code đang chạy.

**Cách làm:** Đối chiếu từng công thức với định nghĩa chuẩn và với nơi chúng được dùng:
- `chatbot-service/app/retrieval/product/fusion/rrf_fusion.py` và `settings.py` (RRF).
- `visual-search-service/app/services/benchmark.py` (các độ đo của Bảng 5.3).
- Hai bộ dữ liệu benchmark (số sản phẩm đúng của mỗi truy vấn).
- Bảng `ai_retrieval.embedding_models` (số chiều, chuẩn hóa L2).

**Kết quả:**
- **Sai so với code:** (2.2). Code dùng RRF có trọng số: w_keyword = 1,2, w_semantic = 1,0, k = 60. Báo cáo đang ghi RRF không trọng số.
- **Đúng nhưng còn thiếu quy ước:**
  - (2.6) MRR: chưa nói 1/rank_q = 0 khi không có kết quả liên quan trong danh sách.
  - (2.4) Recall@K và (2.8) NDCG@K: chưa nói cách xử lý khi |R| = 0 hoặc IDCG = 0.
- **Trích dẫn chưa chuẩn:** (2.7) dạng gain 2^rel − 1 là của Burges và cộng sự (2005). Bài [3] của Järvelin & Kekäläinen (2002) dùng gain tuyến tính rel_i.
- **Đúng:** (2.1), (2.3), (2.5), (2.8). Recall@1 và Recall@5 ở Bảng 5.3 đúng về toán, vì mỗi mẫu ảnh chỉ có 1 sản phẩm đúng nên Recall@K = Hit@K. Ngược lại, ở Bảng 5.2 các truy vấn semantic thường có 3 sản phẩm đúng, nên việc đổi tên thành Hit Rate@5 trước đó là cần thiết.

---

## 2026-10-23 — BCDK1: sửa báo cáo bám sát code và tài liệu project

**Mục đích:** Theo yêu cầu, mọi nội dung trong báo cáo phải có trong project hiện tại (code, migration, cấu hình, tài liệu trong `docs/`, `plan.md`). Không thêm điều gì không có trong project.

**Cách làm:** Rà từng khẳng định trong báo cáo với nguồn trong project. Chỗ nào lệch thì sửa theo nguồn; không thêm tài liệu ngoài.

| Nội dung | Báo cáo cũ | Nguồn trong project | Đã sửa thành |
|---|---|---|---|
| (2.2) RRF | Không trọng số | `rrf_fusion.py`, `settings.py:33-35` | Σ wᵢ/(k + rankᵢ); k = 60, w_keyword = 1,2, w_semantic = 1,0 |
| (2.7) DCG | Gain 2^rel − 1 | `Mission.md` C.8 | Σ rel_i / log₂(i + 1) |
| Quy ước nhãn | Không có | `database-design-v2.md` (grade 0–3, ngưỡng ≥ 2, chỉ tính trên case COMPLETE); `Mission.md` (bỏ user không có sản phẩm liên quan) | Thêm vào đoạn "trong đó…" và mục 3.6.5 |
| Độ trễ tìm kiếm văn bản | p95 dưới 1 giây | `product-hybrid-search-codex-implementation.md`; ngưỡng trong benchmark script | ≤ 1,5 giây khi có cache, ≤ 3 giây khi chưa có |
| "Gợi ý không làm chậm trang chi tiết" | Có | Không có trong project | Bỏ |
| API tìm ảnh | `/api/v1/visual-search` | `ProductController` `@PostMapping("/search-by-image")`, giới hạn 20; `VisualSearchStatusController` `/status` | Sửa ở mục 3.5 và Bảng 3.3 |
| Nguồn ảnh được tải | Chỉ Cloudinary | `catalog_image_allowed_hosts = res.cloudinary.com,cdn.shopify.com`; `image_source.py` bắt buộc HTTPS | Ghi đủ hai host |
| Luồng viết lại truy vấn | "LLM chỉ chạy khi hai cách trước rỗng" | `product_search_service.search()`: LLM viết lại trước khi truy xuất cho câu mô tả nhu cầu → truy vấn gốc → thử lại bỏ màu → từ đồng nghĩa → LLM theo danh mục (bỏ qua nếu đã viết lại trước) | Mô tả đủ 4 bước |

**Đã kiểm tra, khớp code (giữ nguyên):**
- Khóa biến thể theo thứ tự id (`OrderService` sort theo `variant.getId()`).
- Trả hàng 7 ngày (`RETURN_WINDOW_DAYS = 7`).
- Giỏ khách vãng lai qua cookie và gộp giỏ (`CartMergeService`).
- Outbox poll 1 giây; ứng viên 50 ở `VisualSearchService` và tối đa 20 kết quả ở `ProductController`.
- Sổ kho và lịch sử chỉ ghi thêm (trigger `reject_fact_mutation`).
- Hàng đợi retry 30 giây / 5 phút / 1 giờ và DLQ cho cả product-search và visual-search.
- Lịch sử chat lưu trong Redis; chatbot gọi backend bằng token của người dùng.
- LLM viết lại giữ nguyên màu, size, giá, giới tính.
- Các thuật ngữ HNSW, IVFFlat, CLIP, Qwen, BM25 đều có trong tài liệu project.

---

## 2026-10-23 — BCDK1: phiếu và chữ ký, tách danh mục, đối chiếu công thức với tài liệu gốc

**1. Phiếu phê duyệt và chữ ký: giữ lại vì là bắt buộc.**
Đọc bản .doc gốc QĐ449:
- Dòng 6–7: "bắt buộc một đồ án … bao gồm … PHÊ DUYỆT, GIAO NHIỆM VỤ ĐỀ TÀI".
- Dòng 26: "Trang nhiệm vụ đề tài (có đủ chữ ký phê duyệt, giao nhiệm vụ)".
- Mẫu 3 (Lời cảm ơn) và Mẫu 4 (Lời cam đoan) đều có dòng "(Ký và ghi rõ họ tên)".
Vì vậy không bỏ.

**2. Tách danh mục.** Theo Mẫu 5 (liệt kê DANH MỤC CÁC BẢNG và DANH MỤC CÁC HÌNH VẼ riêng), chia thành 3 phần có tiêu đề: DANH MỤC CÁC BẢNG, DANH MỤC CÁC SƠ ĐỒ, DANH MỤC CÁC HÌNH VẼ. Mỗi phần là một field TOC theo style riêng (BangHinh, SoDo, Hinh) và bắt đầu ở trang mới. Cả ba đều có trong Mục lục. Báo cáo tăng từ 33 lên 35 trang.

**3. Đối chiếu công thức với tài liệu gốc.**

| Công thức | Nguồn đã đọc | Kết luận |
|---|---|---|
| RRF | Cormack và cộng sự, SIGIR 2009 (PDF gốc): RRFscore(d) = Σ_{r∈R} 1/(k + r(d)), k = 60, không trọng số | Dùng đúng công thức gốc (2.2). Trọng số 1,2/1,0 của code ghi bằng lời |
| DCG | Järvelin & Kekäläinen 2002 (bản research note gốc): DCG[1] = G[1]; DCG[i] = DCG[i−1] + G[i]/log_b(i) | Dạng Σ rel_i/log₂(i+1) trong báo cáo (lấy từ Mission.md) **không khớp** tài liệu được trích → bỏ công thức, mô tả bằng lời theo bài gốc |
| NDCG = DCG/IDCG | Järvelin 2002 mục 2.3 (chia cho vector DCG lý tưởng) | Giữ, số mới (2.5) |
| HR@K, MRR | Không truy cập được tài liệu gốc (PDF TREC-8 QA chỉ là ảnh quét; trang của Karypis lỗi chứng chỉ) | Bỏ công thức; vẫn giữ tên độ đo vì có trong tài liệu phân công |
| Precision@K, Recall@K, cosine | Định nghĩa giáo trình; nhiều nguồn trích sách Manning chương 8 khớp. Không mở được trang Stanford (bị chặn kết nối) | Giữ |

**Lưu ý cho nhóm:** `Mission.md` mục C.8 đang ghi DCG = Σ rel_i/log₂(i+1), khác với tài liệu Järvelin mà báo cáo trích. Khi viết script đánh giá khuyến nghị, cần thống nhất chọn một định nghĩa và ghi nguồn tương ứng.

---

## 2026-10-23 — BCDK1: bỏ dòng ghi nguồn dưới bảng, sơ đồ, hình

**Mục đích:** Theo yêu cầu của Quyền, xóa 21 dòng "(Nguồn: …)" dưới các chú thích. Bố cục và nội dung còn lại giữ nguyên như bản final_v4.

**Cách làm:** Thêm cờ `ADD_SOURCES = False` vào script `fix_bcdk1.py`, chạy lại toàn bộ quy trình từ bản gốc, rồi dùng Word cập nhật mục lục và danh mục. Chú thích vẫn giữ style riêng (BangHinh/SoDo/Hinh) nên ba danh mục không đổi. Đã kiểm tra trên PDF: không còn dòng "(Nguồn:" nào; báo cáo vẫn 35 trang.

**Lưu ý:** Mẫu 7 của QĐ449 ghi "Ở cuối mỗi bảng biểu, sơ đồ, hình… phải có ghi chú, giải thích, nêu rõ nguồn trích hoặc sao chụp". Nếu GVHD yêu cầu, chỉ cần đặt lại `ADD_SOURCES = True` và chạy lại script.

---

## 2026-10-23 — BCDK1: khôi phục dòng ghi nguồn (bắt buộc theo QĐ449)

**Căn cứ:** Bản gốc QĐ449, phần ghi chú Mẫu 7 (dòng 258 của bản chuyển sang văn bản), viết: "Ở cuối mỗi bảng biểu, sơ đồ, hình,… trong mỗi chương **phải** có ghi chú, giải thích, nêu rõ nguồn trích hoặc sao chụp". Đây là yêu cầu bắt buộc.

**Cách làm:** Đặt lại `ADD_SOURCES = True` trong `fix_bcdk1.py`, chạy lại toàn bộ quy trình từ bản gốc, rồi dùng Word cập nhật mục lục và danh mục. Đủ 21 dòng nguồn; báo cáo 35 trang, nội dung giống bản final_v4.

---

## 2026-10-23 — BCDK1: đặt tên file nộp và tạo bản Word để chỉnh sửa

**Mục đích:** Có file PDF để nộp và file Word để các thành viên khác sửa.

**Cách làm:**
- Nhóm chưa có mã nhóm, nên dùng tên tạm `BCDK1_Nhom` (theo mẫu `BCDK1_<Nhóm>` của Khoa).
- Sao lưu bản khung cũ `BCDK1_Nhom.docx` và bản `BCDK1_Nhom_hoan_chinh.docx` vào thư mục scratchpad `old_deliverables/`. Sau đó đổi tên bản hoàn chỉnh thành `deliverables/BCDK1_Nhom.docx`.
- Dùng Word xuất `deliverables/BCDK1_Nhom.pdf`. Đã kiểm tra: 35 trang, chữ trùng với bản final_v6.

**Khi đã có mã nhóm:** sửa "NHÓM [mã nhóm]" trên bìa, rồi đổi tên cả hai file thành `BCDK1_<mã>.docx` và `BCDK1_<mã>.pdf`.

---

## [2026-11-16] Cập nhật tài liệu tổng quan theo kiến trúc microservice

### 1. Nhiệm vụ này để làm gì?

Nhánh `main` vừa nhận 83 commit tách backend monolith thành sáu microservice. Các tài liệu trong `docs/overview` (Summary, WorkFlow, Database Structure, Mission và các sơ đồ ERD/workflow) vẫn mô tả hệ thống cũ. Nếu dùng chúng để viết báo cáo hoặc bảo vệ thì sẽ trình bày sai kiến trúc. Vì vậy cần viết lại toàn bộ theo code mới.

### 2. Cách làm

1. **Kéo code mới:** `git fetch origin` rồi `git merge origin/main` vào nhánh `quyen` (fast-forward, không có conflict), sau đó push lên `origin/quyen`.
2. **Sao lưu tài liệu cũ** trước khi viết lại, vì thư mục `docs/overview` chưa được git theo dõi (xóa là mất hẳn). Bản sao nằm trong scratchpad của phiên làm việc (`overview-backup-2026-11-16`).
3. **Đọc nguồn sự thật thay vì đọc tài liệu cũ:**
   - Migration của từng service (`*/db/migration`, `*/migrations`) và `infra/database/phase10` để biết bảng, cột, quyền.
   - `gateway/app/main.py` để biết route, xác thực, rate limit.
   - Code Java của Catalog/Sales/Identity (luồng giữ hàng, đặt đơn, SePay, trả hàng).
   - Code Python của Search, Recommendation, Chatbot.
   - `rabbitmq/definitions.json` để biết sự kiện nào tới queue nào.
4. **Vẽ lại sơ đồ** bằng Mermaid: 9 ERD (tổng quan + 8 nhóm theo schema) và 12 sơ đồ luồng. Xuất PNG và SVG bằng `mmdc`. Đổi tên các file có chữ "du-kien" vì phần đó giờ đã có thật, và xóa các file sơ đồ cũ không còn dùng.
5. **Viết lại 4 file Markdown** và ghi rõ các hạn chế hiện tại của code (không suy đoán).

### 3. Kiến thức rút ra (dùng khi bảo vệ)

- **Database per service:** mỗi service có schema và role runtime riêng. Role runtime bị thu hồi quyền trên `public` và schema khác, nên ranh giới được **DB bảo đảm**, không chỉ dựa vào code. Tham chiếu chéo dùng cột `*_ref`, không có khóa ngoại.
- **API Gateway:** một cửa vào duy nhất. Gateway tự kiểm tra JWT rồi truyền `X-User-Id`/`X-User-Role` cho service, đồng thời xóa header cùng tên do client gửi để chống giả mạo.
- **Giao dịch phân tán bằng bù trừ:** đặt đơn chạm hai schema (`sales` và `catalog`) nên không dùng được một transaction. Sales giữ hàng trước, ghi đơn, rồi xác nhận; nếu bước sau lỗi thì nhả hàng hoặc hủy đơn. Giữ hàng có `idempotencyKey` để gửi lại không bị giữ hai lần.
- **Bản đọc + eventual consistency:** Search và Recommendation không đọc bảng Catalog mà giữ bản sao cập nhật qua sự kiện. Bản sao có thể trễ, nhưng tiền và tồn kho vẫn được kiểm tra ở service gốc.

### 4. Phát hiện quan trọng cho phần recommendation

Ghi chi tiết trong `Mission.md` mục A.3. Tóm tắt:
1. Sự kiện `order.created` gửi **ID variant**, còn Recommendation coi là **ID sản phẩm**, nên luật kết hợp không khớp khi gợi ý theo sản phẩm.
2. `recommendation.catalog_products` không được cập nhật vì sự kiện của Catalog không mang trường `product`.
3. Storefront chưa gửi sự kiện hành vi nào.
4. Đánh giá mới có Precision@K và Recall@K; thiếu Hit Rate@K, NDCG@K và chia theo thời gian.

### 5. Vấn đề gặp phải

| Vấn đề | Cách xử lý |
|---|---|
| Mermaid báo lỗi parse ở sơ đồ 07 | Dấu `;` trong nội dung tin nhắn của sequence diagram bị hiểu là ngắt câu. Đổi thành dấu phẩy |
| Không truy vấn được Supabase để lấy số dòng thực tế | Tài liệu chỉ ghi những gì đọc được từ code và migration, không ghi số liệu dữ liệu chưa kiểm chứng |

---

## [2026-11-16] Giai đoạn 1: Sửa dữ liệu đầu vào của recommendation-service

### 1. Nhiệm vụ này để làm gì?

Sau khi chạy dự án, API "thường mua cùng" trả **0 sản phẩm**. Đọc code thấy hai lỗi làm hỏng toàn bộ đầu vào của thuật toán:
- **Bảng `catalog_products` không bao giờ được nạp.** Catalog chỉ gửi sự kiện "mỏng" `PRODUCT_REINDEX_REQUESTED` (chỉ có `productId` + `sourceVersion`), còn worker chỉ ghi bảng khi sự kiện mang sẵn dữ liệu sản phẩm. Không có ứng viên thì cả luật kết hợp lẫn popularity đều rỗng.
- **Giỏ đơn hàng chứa ID variant.** Sales gửi `items` là ID của SKU, recommendation lại coi là ID sản phẩm. Nếu có đơn, luật sẽ được khai phá theo variant và không bao giờ khớp khi gợi ý cho một sản phẩm.

Mọi thuật toán và mọi con số đánh giá sau này đều dựa trên hai bảng này, nên phải sửa trước tiên.

### 2. Cách làm

Chỉ sửa trong `recommendation-service`, không đụng service của Dũng/Hải:

1. **`app/catalog.py`**: client chỉ đọc gọi 3 API của Catalog:
   - `GET /internal/v1/products/{id}/index-document` (cần `X-Internal-Service-Token`): trạng thái, version, variant, ảnh.
   - `GET /api/v1/products/{id}` (công khai): lấy `slug` để storefront dựng link. Chỉ gọi khi sản phẩm `ACTIVE`, vì API này không trả sản phẩm khác.
   - `GET /internal/v1/variants/{id}/sale-snapshot`: biết variant thuộc sản phẩm nào.

   Hàm `build_snapshot` tính giá min/max và tồn khả dụng **chỉ trên SKU `ACTIVE`**, giống truy vấn danh sách sản phẩm của Catalog, để hai nơi hiển thị khớp nhau.
2. **`app/enrichment.py`**: bổ sung dữ liệu cho sự kiện trước khi ghi:
   - Sự kiện sản phẩm → gọi Catalog dựng snapshot. Catalog trả 404 thì ghi bản `DELETED`.
   - Sự kiện đơn hàng → đổi từng ID sang ID sản phẩm. ID nào vốn đã là sản phẩm thì giữ nguyên, ID không khớp gì thì bỏ và ghi log.
3. Worker và API `/internal/v1/events` đều đi qua enricher. Catalog lỗi thì ném `CatalogUnavailable`, và sự kiện tự vào hàng đợi thử lại 30s/5m/1h.
4. **Sửa thêm hai lỗi nhỏ phát hiện khi viết test:**
   - Cùng một đơn đến hai lần với `eventId` khác nhau làm `purchase_count` bị cộng hai lần. Giờ chỉ cộng khi `INSERT order_baskets` thật sự thêm dòng (`returning order_id`).
   - Storefront đọc trường `thumbnail`, còn snapshot chỉ có `thumbnailUrl`. Giờ trả cả hai.
5. **Worker thử kết nối lại RabbitMQ khi khởi động.** Khi chạy, worker bật trước lúc RabbitMQ sẵn sàng 6 giây và thoát luôn. `connect_robust` của aio-pika chỉ tự nối lại *sau* lần kết nối đầu thành công, nên thêm vòng thử lại 2/5/10/20/30 giây.
6. **Script backfill** `scripts/backfill-read-models.ps1`: nạp snapshot cho mọi sản phẩm, sửa giỏ cũ, tính lại `purchase_count`. Script tính toàn bộ trong bộ nhớ (dùng `MemoryRepository`) rồi mới ghi nếu có `-Execute`, nên chế độ chạy thử và chạy thật dùng **cùng một đoạn code**.

### 3. Tại sao chọn cách này?

- **Không sửa Sales (cách B thay vì A):** Sales thuộc phần nhóm khác. Đổi hợp đồng sự kiện cần thống nhất trước, còn tự quy đổi trong recommendation thì không ảnh hưởng ai. Code vẫn đúng nếu sau này Sales chuyển sang gửi ID sản phẩm.
- **Gọi API thay vì đọc DB của Catalog:** role `recommendation_app` không có quyền đọc schema `catalog`, và đây là nguyên tắc ranh giới service của dự án. Search Worker cũng làm y hệt.
- **Sự kiện "mỏng" + gọi lại API:** luôn lấy bản mới nhất từ Catalog, nên sự kiện đến sai thứ tự vẫn không ghi đè dữ liệu cũ lên dữ liệu mới (đã có so `source_version`).

### 4. Kết quả

- Test: **28 passed** (11 cũ + 15 test enricher/client + 2 test worker). Test dùng `FakeCatalog` và `httpx.MockTransport`, không gọi mạng.
- Backfill chạy thử: **36 sản phẩm**, cả 36 gợi ý được, **0 giỏ đơn hàng**. Recommendation chưa từng nhận đơn nào, nên chưa có giỏ cũ nào bị lưu sai.
- Đã khởi động lại API và worker với code mới; cả hai đang chạy.

### 5. Việc còn lại / vấn đề

| Vấn đề | Trạng thái |
|---|---|
| Chạy backfill thật (`-Execute`) để nạp `catalog_products` | Chưa làm: ghi vào Supabase dùng chung nên cần Quyền xác nhận. Trước khi chạy, API gợi ý vẫn trả rỗng |
| Chưa có giỏ đơn hàng → chưa có luật kết hợp; popularity chỉ xếp theo `product_id` khi mọi `purchase_count` = 0 | Cần dữ liệu đơn hàng (thật hoặc synthetic, Giai đoạn 4) |
| Hợp đồng sự kiện (`contracts/*.schema.json`) chưa phản ánh việc `items` là variant ID | Cần chốt với nhóm |
| Thêm `httpx` vào `requirements.txt` vì code runtime giờ gọi HTTP | Đã làm; đồng thời sửa lỗi test không chạy được trên máy mới |

---

## [2026-11-16] Giai đoạn 2: Thu thập hành vi người dùng từ storefront

### 1. Nhiệm vụ này để làm gì?

Mọi thuật toán cá nhân hóa (content-based theo hồ sơ người dùng, hybrid) và mọi phép đo hiệu quả gợi ý (CTR theo model) đều cần **dữ liệu hành vi**. API ghi hành vi đã có sẵn trong `recommendation-service`, nhưng storefront chưa gọi lần nào, nên `user_interactions` không có dữ liệu thật mới. Khối "Thường mua cùng nhau" còn bỏ mất `requestId`, nên không biết lượt bấm thuộc về lần gợi ý nào.

### 2. Cách làm

**Phía recommendation-service** (`app/interactions.py`): endpoint này công khai (trình duyệt gọi trực tiếp), nên phải làm sạch dữ liệu trước khi ghi:

| Quy tắc | Lý do |
|---|---|
| `userRef` lấy từ header `X-User-Id` do Gateway gắn sau khi kiểm JWT; giá trị trong body bị bỏ qua | Không cho người khác giả mạo hành vi của tài khoản khác |
| Chỉ nhận `VIEW`, `ADD_TO_CART`, `WISHLIST`, `SEARCH_*`, `RECOMMENDATION_*` | `PURCHASE` phải đến từ đơn hàng thật (sự kiện Sales), không được để trình duyệt tự khai |
| `dataSource` chỉ `REAL` hoặc `DEMO` | `SYNTHETIC` là dữ liệu sinh offline để đánh giá, không được lẫn vào từ website |
| `RECOMMENDATION_IMPRESSION/CLICK` bắt buộc có `requestId` | Để tính CTR theo từng lần gợi ý / từng model |
| `occurredAt` = giờ server | Chia train/test theo thời gian không bị ảnh hưởng bởi đồng hồ máy khách sai |
| `metadata` ≤ 2 KB, `anonymousId` đúng định dạng | Chặn dữ liệu rác, giảm rủi ro lưu dữ liệu cá nhân |

**Phía storefront** (`src/modules/recommendation/`):
- `tracking.ts`: hàm `trackInteraction` gửi sự kiện, **không bao giờ ném lỗi** ra ngoài; dùng `keepalive` để sự kiện click vẫn gửi xong khi trình duyệt chuyển trang. `anonymousId` là UUID lưu trong `localStorage`.
- `ProductViewTracker`: ghi `VIEW` khi mở trang chi tiết, tối đa 1 lần/sản phẩm/10 phút để F5 liên tục không làm phồng số liệu.
- `RecommendationSection`: thay khối "Thường mua cùng nhau" cũ. Dùng `IntersectionObserver` để chỉ ghi `RECOMMENDATION_IMPRESSION` khi khối **thật sự hiện ≥ 50%** trên màn hình (gợi ý nằm cuối trang thường không được nhìn thấy), và ghi `RECOMMENDATION_CLICK` ở pha capture trước khi link chuyển trang. Mỗi sự kiện kèm `requestId`, vị trí, thuật toán, `modelVersion`.
- `ProductPurchasePanel`: ghi `ADD_TO_CART` (kèm `variantId`, số lượng) và `WISHLIST` sau khi thao tác thành công.

### 3. Kiến thức liên quan (dùng khi bảo vệ)

- **Impression và click:** CTR = số click / số impression. Phải có impression thật (khối được nhìn thấy) thì CTR mới có nghĩa; ghi impression ngay khi render sẽ làm CTR thấp giả tạo.
- **Position bias:** sản phẩm ở vị trí đầu được bấm nhiều hơn chỉ vì đứng đầu. Lưu `position` để sau này có thể hiệu chỉnh khi phân tích.
- **Throttle VIEW:** phản hồi ngầm dễ bị nhiễu (tải lại trang, bot). Giới hạn tần suất là cách giảm nhiễu đơn giản.

### 4. Kết quả kiểm tra

- `recommendation-service`: **32 passed** (thêm 4 test cho quy tắc làm sạch).
- Storefront: `typecheck` và `eslint` trên các file đã sửa đều đạt; trang chi tiết sản phẩm render 200.
- Qua Gateway: `PURCHASE`, `dataSource=SYNTHETIC`, `RECOMMENDATION_CLICK` thiếu `requestId` đều bị trả **422** đúng thông báo.
- **Chưa thử end-to-end bằng trình duyệt thật**, vì mỗi lần thử sẽ ghi sự kiện vào Supabase dùng chung. Khi thử, đặt `NEXT_PUBLIC_INTERACTION_DATA_SOURCE=DEMO` cho storefront để tách khỏi dữ liệu thật.

---

## [2026-11-16] Giai đoạn 0 (môi trường microservice): kiểm tra cấu hình, quyền DB và dọn file cũ

### 1. Nhiệm vụ này để làm gì?

Giai đoạn 0 bảo đảm môi trường làm việc **đúng và an toàn** trước khi viết thuật toán: service kết nối đúng role, role không ghi được ra ngoài schema của mình, bộ test và gate nghiệm thu chạy được, và không còn file cũ gây nhầm lẫn.

### 2. Cách làm và kết quả

| Việc | Cách làm | Kết quả |
|---|---|---|
| Kiểm tra `.env.phase10.local` | Đọc **tên biến** và độ dài giá trị, không in giá trị | Đủ biến; DSN dùng role runtime `recommendation_app`, không phải role quản trị. Mật khẩu RabbitMQ là `change-me`, khớp Docker local |
| Permission probe | `recommendation-service/scripts/verify_schema_permissions.py`: ghi thử 1 dòng vào `recommendation.processed_events` và thử tạo bảng trong `public`, cả hai trong một transaction rồi **rollback** | Đạt: ghi được schema riêng, bị từ chối ở `public`; không để lại dữ liệu |
| Gate Phase 5 đầy đủ | `scripts/verify-microservice-phase-5.ps1` (kiểm tra migration không có FK sang `public`, chạy test, kiểm tra RabbitMQ definitions, permission probe) | Đạt, 32 test |
| Đọc tài liệu Phase 5 | `docs/microservice-phase-5-recommendation-service.md` | Dữ liệu cũ chuyển sang có 0 hành vi/luật/log: recommendation bắt đầu từ con số 0 |
| File V10 nháp | Sao chép vào `docs/overview/archive/` (so khớp SHA-256) | Đã lưu bản tham khảo |

### 3. Kiến thức liên quan

- **Least privilege (quyền tối thiểu):** service chạy bằng role chỉ có quyền đúng việc của nó. Nếu code có lỗi hoặc bị khai thác, thiệt hại cũng chỉ trong schema `recommendation`.
- **Probe trong transaction rồi rollback:** cách kiểm tra quyền ghi mà không làm bẩn dữ liệu thật.

### 4. Vấn đề gặp phải

| Vấn đề | Cách xử lý |
|---|---|
| Gate gọi `python` chung của máy, không có thư viện của service | Đặt `recommendation-service\.venv\Scripts` lên đầu `PATH` trước khi chạy gate |
| `backend\.env` là bản duy nhất của cấu hình backend cũ (`.env.legacy-backend.local` mà `RUN_LOCAL.md` nhắc tới chưa tồn tại) | Không xóa. Đề xuất chuyển sang `.env.legacy-backend.local` |
| Lệnh di chuyển `.env` và xóa thư mục `backend\` bị hệ thống quyền chặn lần đầu | Quyền xác nhận; đã chuyển `backend\.env` → `.env.legacy-backend.local` (đã gitignore) và xóa `backend\` |

---

## [2026-11-16] Giai đoạn 1 (hoàn tất): Chạy backfill thật

- Quyền đồng ý cho ghi vào schema `recommendation` trên Supabase dùng chung. Chạy `recommendation-service\scripts\backfill-read-models.ps1 -Execute`: ghi **36 snapshot sản phẩm**, 0 bản xóa, 0 giỏ đơn hàng cần sửa.
- **Kiểm tra lặp lại (idempotency):** chạy thử lại ngay sau đó cho đúng số liệu cũ. Upsert có so `source_version` nên chạy bao nhiêu lần cũng không sinh trùng.
- **Kiểm tra qua Gateway:** `GET /api/v1/products/{id}/frequently-bought-together` trả **8 gợi ý** (trước đó là 0), mỗi gợi ý có `slug`, `thumbnail`, tồn kho, `requestId` riêng.
- **Hạn chế còn lại:** chưa có đơn hàng nên `purchase_count` = 0 cho mọi sản phẩm. Popularity lúc này chỉ xếp theo `product_id`, mọi trang sản phẩm thấy cùng một danh sách, và chưa có luật kết hợp nào. Đây là lý do cần dữ liệu đơn hàng (thật hoặc synthetic ở Giai đoạn 4) trước khi đánh giá thuật toán.

---

## [2026-11-16] Giai đoạn 2 (hoàn tất): Kiểm tra end-to-end bằng trình duyệt thật

### 1. Nhiệm vụ này để làm gì?

Test tự động chỉ chứng minh từng phần đúng riêng lẻ. Cần một lần chạy thật **trình duyệt → Gateway → recommendation-service → Supabase** để chắc rằng: storefront gửi đúng thời điểm, Gateway chuyển đúng, service chấp nhận, và dữ liệu trong DB đủ thông tin cho bước đánh giá sau này.

### 2. Cách làm

1. Đặt tạm `NEXT_PUBLIC_INTERACTION_DATA_SOURCE=DEMO` trong `frontend/storefront/.env.local` rồi khởi động lại storefront, để sự kiện thử **không lẫn với dữ liệu thật** (`REAL`).
2. Dùng Playwright (trình duyệt tự động) thao tác như một khách:
   - mở trang "Bình nước thể thao Black Flow 750ml";
   - cuộn tới khối "Thường mua cùng nhau";
   - bấm thẻ đầu tiên;
   - ở trang mới, chọn size M rồi bấm "Thêm vào giỏ";
   - mở lại trang đầu tiên để thử quy tắc chống spam VIEW.
3. Theo dõi request mạng trong trình duyệt, sau đó **đọc** bảng `recommendation.user_interactions` (kết nối ở chế độ read-only).
4. Gỡ biến `DEMO` và khởi động lại storefront.

### 3. Kết quả

| Thời điểm | Sự kiện | Kiểm tra |
|---|---|---|
| 17:27:42 | `VIEW` Bình nước | Có `anonymousId`, không có `userRef` |
| 17:27:50 | 4 × `RECOMMENDATION_IMPRESSION` | Chỉ gửi khi cuộn tới khối gợi ý, không gửi lúc tải trang; vị trí 0–3, `algorithm=POPULARITY`, `modelVersion`, `sourceProductId`; `requestId` khớp với một dòng trong `recommendation_requests` |
| 17:28:02 | `RECOMMENDATION_CLICK` Áo Royal Motion | Vị trí 0, cùng `requestId`; được ghi dù trình duyệt chuyển trang ngay (nhờ `keepalive`) |
| 17:28:04 | `VIEW` Áo Royal Motion | Trang mới |
| 17:28:17 | `ADD_TO_CART` | Có `variantId`, `quantity=1`; giỏ hàng trả 201 |
| Mở lại trang đầu | (không có sự kiện) | Quy tắc 1 VIEW/sản phẩm/10 phút hoạt động |

Không có sự kiện `REAL` nào trong cùng khoảng thời gian; console trình duyệt không có lỗi.

### 4. Ý nghĩa cho phần đánh giá

- Chuỗi IMPRESSION → CLICK có cùng `requestId` cho phép tính **CTR theo từng lần gợi ý và theo model**.
- Lưu `position` giúp phân tích **position bias** (vị trí đầu được bấm nhiều hơn).
- `dataSource` tách được dữ liệu thử (`DEMO`) khỏi dữ liệu thật khi đánh giá.

### 5. Ghi chú

- Lần thử tạo một giỏ hàng khách vãng lai trong schema `sales` (1 sản phẩm size M) và 8 sự kiện `DEMO` trong `recommendation.user_interactions`. Bảng này chỉ cho thêm (trigger chặn sửa/xóa), nên 8 sự kiện được giữ lại; khi đánh giá thì lọc `data_source='REAL'` hoặc `'SYNTHETIC'`.
- Playwright để lại thư mục log `E:\.playwright-mcp` (ngoài dự án), có thể xóa.
