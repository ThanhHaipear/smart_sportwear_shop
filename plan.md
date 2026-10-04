# Kế hoạch tái kiến trúc và triển khai hệ thống thương mại điện tử trang phục thể thao tích hợp AI

## 1. Mục đích tài liệu

Tài liệu này là kế hoạch thực thi để tái cấu trúc codebase hiện tại thành một hệ thống phù hợp với đề tài:

> Nghiên cứu và xây dựng hệ thống thương mại điện tử trang phục thể thao tích hợp các phương pháp AI trong tìm kiếm, tư vấn và khuyến nghị sản phẩm.

Mục tiêu của kế hoạch:

- Tận dụng những phần nghiệp vụ và AI đã hoạt động tốt trong code cũ.
- Loại bỏ các phần nằm ngoài trọng tâm hoặc làm kiến trúc khó phát triển theo nhóm.
- Xác định rõ service nào sở hữu dữ liệu nào.
- Chuẩn hóa tìm kiếm văn bản, tìm kiếm ảnh, chatbot RAG và recommendation.
- Xây dựng quy trình đánh giá AI có thể tái lập và đưa vào luận văn.
- Cho phép nhóm 4-6 người phát triển song song mà giảm xung đột code và database.
- Duy trì một phiên bản chạy được sau mỗi giai đoạn, không viết lại toàn bộ theo kiểu “big bang”.

---

## 2. Nguyên tắc thực hiện

1. Không xóa hoặc sửa dữ liệu hiện tại trước khi có backup và kế hoạch chuyển đổi.
2. Không viết lại các nghiệp vụ thương mại điện tử đang hoạt động nếu chỉ cần refactor.
3. Tái xây dựng ranh giới service, ownership database, authentication và evaluation.
4. Mỗi bảng chỉ có đúng một service sở hữu quyền ghi.
5. Giao tiếp đồng bộ dùng REST/OpenAPI; giao tiếp bất đồng bộ dùng RabbitMQ và event contract.
6. Mọi thay đổi schema phải qua migration; không dùng Hibernate tự tạo hoặc sửa bảng.
7. Redis không được dùng làm nơi lưu dữ liệu nghiệp vụ lâu dài.
8. RabbitMQ không phải database; dữ liệu cần audit phải được lưu trong PostgreSQL.
9. Ảnh/file lưu ở Cloudinary; PostgreSQL chỉ lưu metadata, URL và định danh.
10. Mọi thí nghiệm AI phải lưu dataset version, model version, cấu hình và kết quả.
11. `main` luôn phải build và chạy được.
12. Không đưa secret thật vào Git, log, tài liệu hoặc dữ liệu benchmark.
13. Ưu tiên giải pháp mã nguồn mở/chạy local cho embedding và benchmark; API trả phí phải có budget guard, cache và số liệu chi phí.
14. Tài liệu phân tích thiết kế và protocol đánh giá phải có trước implementation tương ứng; không vẽ sơ đồ hoặc tạo ground truth hồi cứu chỉ để khớp code đã viết.

---

## 3. Phạm vi sản phẩm cuối cùng

Phạm vi được ưu tiên theo `MUST/SHOULD/COULD` để nhóm hoàn thành đúng trọng tâm đề tài trước khi mở rộng. Chức năng đã có trong code cũ nhưng không thuộc `MUST` được giữ nếu không cản trở kiến trúc; không mặc nhiên trở thành đường găng của đồ án.

### 3.1. MUST - bắt buộc cho nghiệm thu

#### Commerce

- Đăng ký, đăng nhập, đăng xuất và làm mới phiên.
- Quản lý hồ sơ và địa chỉ.
- Danh mục, thương hiệu, sản phẩm, biến thể và ảnh.
- Thuộc tính đặc trưng của trang phục thể thao.
- Tìm kiếm theo bộ lọc có cấu trúc.
- Giỏ hàng.
- Checkout và tạo đơn hàng.
- Thanh toán COD và VNPAY.
- Theo dõi, hủy và xử lý đơn hàng.
- Quản lý tồn kho, nhập, xuất và điều chỉnh.
- Cảnh báo tồn kho thấp.
- Quản trị sản phẩm, danh mục, người dùng và tham số hệ thống.
- Báo cáo doanh thu, khách hàng, sản phẩm, đơn hàng và tồn kho.

#### AI và đánh giá

- Phân tích truy vấn ngôn ngữ tự nhiên.
- Trích xuất loại sản phẩm, giới tính, môn thể thao, mục đích, màu, size và giá.
- Keyword search.
- Semantic search bằng text embedding và `pgvector`.
- Hybrid search bằng keyword + vector + RRF/reranking.
- Tìm kiếm sản phẩm bằng hình ảnh.
- Chatbot tư vấn sử dụng RAG.
- Size advisor và product recommendation.
- Recommendation theo độ phổ biến, nội dung và hành vi.
- Thu thập interaction event phục vụ recommendation.
- Đánh giá text search, image search và recommendation.

### 3.2. SHOULD - thực hiện sau khi luồng bắt buộc ổn định

- Wishlist, review và notification.
- Giao hàng, yêu cầu hủy, đổi trả và hoàn tiền ở mức đủ xử lý phát sinh đơn hàng.
- Size advisor.
- Google login nếu không làm chậm luồng authentication chính.
- Admin theo dõi indexing job và evaluation run.

### 3.3. COULD/Experimental - không nằm trên đường găng

Các phần sau được giữ nếu đã hoạt động, hoặc chuyển sang khu vực thử nghiệm; không phải dependency bắt buộc khi demo:

- Khuyến mãi, combo, CMS/banner/page nâng cao.
- AI Forecasting và replenishment nâng cao.
- Admin Copilot và cơ chế agent approval.
- Các workflow AI quản trị không liên quan trực tiếp đến tìm kiếm, tư vấn và recommendation.

### 3.4. Workstream nghiên cứu và luận văn

Workstream này chạy song song với implementation và phải tạo ra tài liệu có trích dẫn, câu hỏi nghiên cứu, giả thuyết, cấu hình thí nghiệm và kết luận:

- Tổng quan thương mại điện tử và nghiệp vụ kinh doanh trang phục thể thao.
- Đặc trưng sản phẩm: loại, giới tính, màu, size, chất liệu, thương hiệu, môn thể thao, mục đích và giá.
- Tìm kiếm ngôn ngữ tự nhiên, semantic search, vector embedding và các độ đo tương đồng.
- LLM, prompt engineering, structured output và so sánh GPT với mô hình/provider tương đương.
- Tìm kiếm ảnh và các mô hình image embedding.
- RAG cho tư vấn sản phẩm, chính sách và hướng dẫn chọn size.
- Recommendation: popularity, content-based, association/behavior và hybrid.
- Kiến trúc Spring Boot microservices, Next.js/React, RabbitMQ, Python, PostgreSQL/pgvector.
- Giới hạn nghiên cứu, rủi ro dữ liệu, quyền sử dụng ảnh, chi phí và khả năng tái lập.

Mỗi bảng/biểu đồ trong luận văn phải truy ngược được tới dataset version, Git commit, model/prompt version và evaluation run.

---

## 4. Kiến trúc mục tiêu

```text
Storefront (Next.js) ─┐
                     ├──► Commerce Core (Spring Boot) ───► Supabase PostgreSQL/public
Admin (Next.js) ─────┘              │
                                    ├──► Cloudinary
                                    ├──► Redis
                                    ├──► RabbitMQ / Transactional Outbox
                                    │              │
                                    │              ▼
                                    └──────► AI Retrieval Service (FastAPI)
                                                   ├── Supabase/ai_retrieval
                                                   ├── Supabase/evaluation
                                                   └── pgvector

Storefront ──► Commerce API ──► Assistant Service (FastAPI/LangGraph)
                                      ├── gọi Commerce API
                                      ├── gọi AI Retrieval API
                                      ├── Redis session
                                      └── PostgreSQL/assistant
```

### 4.1. Commerce Core

Nền tảng: Java 21, Spring Boot, Spring Security, JPA, Flyway.

Trách nhiệm:

- Nguồn dữ liệu chuẩn của catalog, giá, tồn kho, khách hàng và đơn hàng.
- Authentication và authorization.
- Toàn bộ transaction thương mại điện tử.
- Admin API và reporting nghiệp vụ.
- Phát catalog/order/interaction event bằng transactional outbox.
- Xác minh trạng thái, giá và tồn kho cuối cùng trước khi trả kết quả hoặc checkout.

Không chịu trách nhiệm:

- Sinh embedding.
- Vector search.
- LLM orchestration.
- Lưu search index.

### 4.2. AI Retrieval Service

Nền tảng: Python, FastAPI, PostgreSQL, `pgvector`, Pillow, embedding providers.

Trách nhiệm:

- NLP query parser và query rewrite.
- Keyword, semantic và hybrid search.
- Image preprocessing, image embedding và visual search.
- Recommendation algorithms.
- Search/recommendation read model.
- Indexing worker và model versioning.
- Benchmark/evaluation runner.
- Lưu kết quả đánh giá.

Không được:

- Sửa bảng catalog của Commerce.
- Quyết định giá và tồn kho cuối cùng.
- Tạo đơn hàng hoặc sửa giỏ hàng.

### 4.3. Assistant Service

Nền tảng: Python, FastAPI, LangGraph, Redis.

Trách nhiệm:

- Intent routing.
- Tool selection và policy guard.
- RAG với knowledge base và dữ liệu sản phẩm.
- Tư vấn sản phẩm, kích thước, chính sách và chăm sóc sản phẩm.
- Thực hiện hành động giỏ hàng/đơn hàng thông qua Commerce API.
- Lưu lịch sử chat bền vững và session ngắn hạn.
- Streaming response.

Không được:

- Truy cập trực tiếp bảng Commerce.
- Tự cài đặt một search engine thứ hai.
- Tự cập nhật catalog hoặc tồn kho.

---

## 5. Thiết kế repository mục tiêu

```text
smart-sportswear-shop/
├── apps/
│   ├── storefront/
│   └── admin/
├── services/
│   ├── commerce-core/
│   ├── ai-retrieval/
│   └── assistant/
├── contracts/
│   ├── openapi/
│   ├── events/
│   └── examples/
├── datasets/
│   ├── raw/
│   ├── normalized/
│   ├── seed/
│   └── evaluation/
├── infrastructure/
│   ├── docker/
│   ├── postgres/
│   ├── rabbitmq/
│   └── monitoring/
├── docs/
│   ├── architecture/
│   ├── diagrams/
│   ├── adr/
│   └── thesis/
└── experiments/
    ├── forecasting/
    └── admin-copilot/
```

Không di chuyển toàn bộ thư mục ngay trong một commit. Thực hiện sau khi API và ownership đã ổn định để tránh xung đột merge lớn.

---

## 6. Ownership database

### 6.1. Mô hình triển khai đã chốt

Dùng **một Supabase project mới và một PostgreSQL database vật lý duy nhất** cho môi trường tích hợp/demo. Project mới được tạo trắng ở cấp ứng dụng; không chạy migration cũ lên đó cho đến khi ERD và baseline migration mới được duyệt. Không tạo Supabase riêng cho từng service hoặc từng thành viên. Mỗi thành viên dùng PostgreSQL + `pgvector` local hoặc Testcontainers khi phát triển và kiểm thử.

Supabase cũ được giữ read-only làm nguồn đối chiếu và chuyển dữ liệu. Không xóa project cũ trước khi hệ thống mới được nghiệm thu và dữ liệu cần giữ đã được kiểm tra đầy đủ.

Để giảm tối đa thay đổi code cũ, không chuyển các bảng nghiệp vụ hiện tại từ `public` sang schema `commerce`:

| Schema | Chủ sở hữu | Nội dung |
|---|---|---|
| `public` | Commerce Core | Identity, catalog, sales, inventory, fulfillment, reporting và outbox |
| `ai_retrieval` | AI Retrieval | Search read model, text/image embeddings, indexing và recommendation |
| `evaluation` | AI Retrieval | Dataset metadata, ground truth, runs và metrics |
| `assistant` | Assistant Service | Chat session/message, summary, feedback và tool audit |

Giai đoạn đầu, các backend có thể dùng connection hiện tại để giảm cấu hình. Trước bản demo cuối, tạo role riêng nếu thời gian cho phép:

- `commerce_app`: ghi các bảng nghiệp vụ trong `public`.
- `ai_retrieval_app`: ghi `ai_retrieval` và `evaluation`, chỉ đọc những view/API được cấp để đối chiếu catalog khi cần.
- `assistant_app`: ghi `assistant`; không có quyền đọc trực tiếp bảng Commerce trong `public`.
- Frontend không được nhận database password hoặc kết nối trực tiếp PostgreSQL.
- Không service runtime nào nên dùng database owner/superuser sau giai đoạn hardening.

### 6.2. Migration ownership

- Giữ migration cũ trong Git tag/nhánh legacy để phục vụ đối chiếu; không sửa lịch sử của database cũ.
- Không cho Supabase mới chạy thư mục `classpath:db/migration` hiện tại vì nó chứa 52 migration của hệ thống cũ.
- Tạo location mới, ví dụ `classpath:db/v2-migration`, và chỉ chuyển `spring.flyway.locations` sang location này sau khi baseline V2 được duyệt.
- Baseline đầu tiên của Commerce tạo các bảng nghiệp vụ cần giữ trong `public`; không tái tạo bảng ngoài phạm vi chỉ vì chúng từng tồn tại.
- AI Retrieval quản lý migration của `ai_retrieval` và `evaluation` trong chính service AI hoặc location tách biệt. Các bảng `visual_search` cũ được xem là nguồn tham khảo và được ánh xạ vào `ai_retrieval`, không tái tạo thành một schema runtime riêng.
- Không dùng chung số thứ tự migration giữa Commerce và AI nếu hai service có Flyway/Alembic history riêng.
- Assistant quản lý migration của schema `assistant` và là service duy nhất được ghi các bảng chat bền vững.
- Chỉ một người được phân công hoặc một CI job được chạy migration lên Supabase chung.
- Không chỉnh schema trực tiếp trên Dashboard mà không tạo migration tương ứng.
- Test migration trên PostgreSQL local/database tạm trước khi áp dụng lên Supabase.

### 6.3. Dữ liệu Commerce

Baseline V2 ưu tiên nhóm `MUST`; nhóm `SHOULD/COULD` chỉ được đưa vào baseline khi code giữ lại thực sự phụ thuộc hoặc nhóm xác nhận sẽ demo. Không sao chép đủ 52 migration legacy chỉ để giữ mọi bảng cũ.

Nhóm `MUST`:

- Identity: `users`, `refresh_tokens`, `password_reset_tokens`, `addresses`.
- Catalog: `categories`, `brands`, `products`, `product_variants`, `product_images` và taxonomy thể thao.
- Sales: `carts`, `cart_items`, `orders`, `order_items`, `payments`.
- Inventory: `inventory_transactions` và quantity trên variant hoặc bảng balance riêng.
- System: `site_settings` hoặc bảng tham số hệ thống tương đương.
- Operations: `audit_logs`, `integration_outbox`.
- AI input: `user_interactions`.

Nhóm `SHOULD/COULD` cần review dependency trước khi giữ:

- Fulfillment nâng cao: `shipping_methods`, `shipments`, `returns`, `return_items`, `refunds`.
- Engagement: `wishlists`, `wishlist_items`, `product_reviews`, `notifications`.
- Content: `collections`, `banners`, `banner_items`, `pages`.
- Marketing: `promotions`, `promotion_products`, `combos`, `combo_products`.
- Vận hành phụ trợ: `email_logs` và các bảng chỉ phục vụ tính năng ngoài đường găng.

### 6.4. Thuộc tính trang phục thể thao

Các trường lọc thường xuyên đặt trực tiếp hoặc chuẩn hóa quan hệ:

- `product_type`
- `gender`
- `brand_id`
- `category_id`
- `base_price`
- `status`
- môn thể thao
- mục đích sử dụng
- chất liệu
- công nghệ vải
- điều kiện sử dụng

Đề xuất bảng:

- `sports`
- `product_sports`
- `use_cases`
- `product_use_cases`
- `materials`
- `product_materials`
- `technologies`
- `product_technologies`

JSONB chỉ dùng cho thuộc tính thưa hoặc ít cần join, ví dụ độ thoáng khí, mức chống nước, compression level và kiểu fit.

Màu, size, SKU, giá bán, reserved quantity và stock quantity thuộc `product_variants`.

### 6.5. AI Retrieval schema

Các bảng mục tiêu:

- `product_search_documents`
- `text_embedding_models`
- `product_text_embeddings`
- `image_embedding_models`
- `product_image_embeddings`
- `knowledge_documents`
- `knowledge_chunks`
- `knowledge_embeddings`
- `indexing_jobs`
- `indexing_job_items`
- `processed_events`
- `usage_events`
- `recommendation_models`
- `recommendation_items`
- `recommendation_events`

Yêu cầu:

- Embedding gắn với model, dimension, document hash và thời điểm cập nhật.
- Cho phép tồn tại nhiều model version.
- Chỉ có một model active theo loại embedding.
- Consumer idempotent thông qua `processed_events`.
- Có trạng thái `PENDING`, `PROCESSING`, `READY`, `FAILED`.

Ownership recommendation được chốt như sau:

- Commerce chỉ sở hữu giao dịch gốc và ghi nhận/phát interaction, cart, wishlist và purchase event.
- AI Retrieval sở hữu thuật toán, association rule/model, candidate score, recommendation response log và evaluation.
- Commerce hydrate kết quả bằng product ID và kiểm tra lại trạng thái, giá, tồn kho trước khi trả ra frontend.
- Module recommendation hiện có trong Spring là nguồn tham khảo/compatibility tạm thời; sau khi AI Retrieval đạt parity, không duy trì hai recommendation engine cùng là nguồn chuẩn.

### 6.6. Evaluation schema

Các bảng mục tiêu:

- `evaluation_datasets`
- `evaluation_queries`
- `evaluation_relevance_labels`
- `evaluation_runs`
- `evaluation_run_results`
- `evaluation_metrics`

Mỗi evaluation run lưu tối thiểu:

- Dataset name và version.
- Git commit.
- Loại bài toán.
- Model/provider/version.
- Embedding dimension.
- Search weights và thresholds.
- Reranking configuration.
- Start/end time.
- Precision@K, Recall@K, MRR, NDCG@K hoặc Hit Rate@K.
- P50/P95 latency.
- Failure count và error summary.

### 6.7. Dữ liệu Assistant

Tạo schema `assistant` vì database V2 đang trắng và cần ownership rõ ràng:

- `chat_sessions`
- `chat_messages`
- `conversation_summaries`
- `assistant_feedback`
- `tool_execution_logs` nếu cần audit.

Không sao chép lịch sử chat cũ mặc định. Assistant chỉ nhận dữ liệu Commerce qua REST/OpenAPI và dữ liệu retrieval qua AI Retrieval API.

Redis chỉ lưu:

- Context ngắn hạn.
- Pending confirmation.
- Cache.
- Rate limit.
- Streaming/session coordination.

---

## 7. API và event contracts

### 7.1. REST API

Mỗi service phải xuất OpenAPI specification.

Commerce cung cấp internal API cho Assistant:

- Tìm/lấy chi tiết sản phẩm chính xác theo ID hoặc SKU.
- Kiểm tra biến thể, giá và tồn kho.
- Thêm sản phẩm vào giỏ.
- Xem trạng thái đơn hàng.
- Hủy đơn với confirmation.
- Lấy chính sách hoặc dữ liệu người dùng được phép.

AI Retrieval cung cấp:

- `POST /internal/v1/search/text`
- `POST /internal/v1/search/image`
- `POST /internal/v1/retrieval/knowledge`
- `POST /internal/v1/recommendations`
- `POST /internal/v1/indexing/reindex`
- `GET /internal/v1/indexing/jobs/{id}`
- `POST /internal/v1/evaluations/runs`
- `GET /internal/v1/evaluations/runs/{id}`

Assistant cung cấp qua Commerce proxy hoặc gateway:

- `POST /api/v1/chat`
- `POST /api/v1/chat/stream`
- `GET /api/v1/chat/history`
- `POST /api/v1/chat/feedback`

### 7.2. Event envelope

Mọi event dùng envelope thống nhất:

```json
{
  "eventId": "uuid",
  "eventType": "catalog.product.updated.v1",
  "eventVersion": 1,
  "aggregateId": "uuid",
  "occurredAt": "2026-01-01T00:00:00Z",
  "producer": "commerce-core",
  "correlationId": "uuid",
  "payload": {}
}
```

Các event cần có:

- `catalog.product.created.v1`
- `catalog.product.updated.v1`
- `catalog.product.deleted.v1`
- `catalog.image.changed.v1`
- `catalog.variant.changed.v1`
- `order.completed.v1`
- `user.interaction.recorded.v1`

### 7.3. RabbitMQ topology

- Exchange: `domain.events` kiểu topic.
- Queue chính cho indexing.
- Queue chính cho recommendation updates nếu tách worker.
- Retry 30 giây, 5 phút và 1 giờ.
- Dead-letter queue.
- Manual acknowledgment.
- Message persistent.
- Prefetch có giới hạn.
- Consumer phải idempotent.

---

## 8. Thiết kế các module AI

### 8.1. Text search pipeline

```text
Query
  -> normalize
  -> intent/attribute extraction
  -> optional LLM rewrite
  -> keyword retrieval
  -> semantic retrieval
  -> RRF fusion
  -> hard filters
  -> reranking
  -> result guard
  -> product IDs + scores + explanation
  -> Commerce hydrate/validate
```

Các mode bắt buộc để đánh giá:

- Keyword-only baseline.
- Semantic-only.
- Hybrid không rerank.
- Hybrid có rerank.

Thí nghiệm phân tích truy vấn/prompt engineering:

- Rule-based baseline không dùng LLM.
- LLM zero-shot với structured JSON output.
- LLM few-shot với cùng schema đầu ra.
- Hybrid rule + LLM fallback cho truy vấn khó hoặc mơ hồ.
- Version hóa system prompt, few-shot examples, JSON schema, temperature và provider/model.
- Đo intent accuracy; exact match/micro-F1 theo từng thuộc tính; tỷ lệ output JSON hợp lệ; latency; token và chi phí.
- Không để LLM tự tạo giá trị taxonomy: giá trị trích xuất phải được map/validate với code hợp lệ hoặc đánh dấu `UNKNOWN`.

### 8.2. Image search pipeline

```text
Upload
  -> size/MIME/pixel validation
  -> EXIF cleanup và normalization
  -> color signature
  -> query embedding
  -> pgvector cosine search
  -> group theo product
  -> commerce filters
  -> ranked results
```

Quy tắc:

- Không lưu query image mặc định.
- Chặn SSRF đối với catalog image URL.
- Có giới hạn byte, pixel, redirect và timeout.
- Catalog image embedding được tạo bất đồng bộ.
- Query embedding có timeout, rate limit và budget guard.

### 8.3. Recommendation pipeline

Triển khai theo ba mức:

1. Popularity baseline.
2. Content-based similarity.
3. Behavior/association-based recommendation.

Không triển khai collaborative filtering phức tạp khi dữ liệu hành vi chưa đủ. Popularity là baseline bắt buộc; content-based xử lý cold-start; association/behavior chỉ được chọn làm cấu hình chính nếu kết quả split theo thời gian tốt hơn baseline.

Interaction types:

- `PRODUCT_VIEW`
- `SEARCH_IMPRESSION`
- `SEARCH_CLICK`
- `RECOMMENDATION_IMPRESSION`
- `RECOMMENDATION_CLICK`
- `ADD_TO_CART`
- `ADD_TO_WISHLIST`
- `PURCHASE`

Mỗi response recommendation có:

- `requestId`
- `algorithm`
- `modelVersion`
- `context`
- product ID, score và rank.

### 8.4. RAG/Assistant pipeline

```text
Message
  -> authentication/context
  -> intent router
  -> policy guard
  -> tool selection
  -> search/commerce/knowledge tool
  -> evidence collection
  -> answer generation
  -> consistency validation
  -> response + product cards + sources
```

Các nhóm knowledge:

- Chính sách giao hàng.
- Chính sách đổi trả.
- Thanh toán.
- Hướng dẫn chọn size.
- Chăm sóc sản phẩm.
- FAQ.

Knowledge document/chunk và embedding do AI Retrieval sở hữu. Assistant gọi retrieval API để lấy evidence; không tự tạo vector index thứ hai và không đọc trực tiếp bảng `ai_retrieval`.

Hành động thay đổi dữ liệu phải yêu cầu confirmation khi phù hợp, đặc biệt hủy đơn và thao tác giỏ hàng có tác động rõ ràng.

### 8.5. Chiến lược mô hình và chi phí

- Ưu tiên một baseline text embedding đa ngôn ngữ chạy local và một cấu hình API để so sánh nếu ngân sách cho phép.
- Ưu tiên CLIP/SigLIP hoặc mô hình image embedding mã nguồn mở chạy batch; không gọi API ảnh trả phí cho mỗi lần reindex nếu không cần thiết.
- LLM trả phí chỉ dùng cho query parsing khó, RAG generation hoặc thí nghiệm đối chứng; luồng structured filter/keyword vẫn hoạt động khi provider lỗi.
- Cache theo input hash + model/prompt version; không sinh lại embedding khi document/image checksum không đổi.
- Mỗi evaluation run lưu số request, token, thời gian chạy và chi phí ước tính.
- Cấu hình budget tối đa theo ngày/run; khi vượt budget phải dừng hoặc fallback thay vì tiếp tục âm thầm.
- Demo chuẩn bị sẵn index và dataset đã duyệt để không phụ thuộc vào việc gọi API hàng loạt tại thời điểm trình bày.

---

## 9. Authentication và bảo mật

- Access token có thời gian sống ngắn.
- Refresh token lưu trong cookie `HttpOnly`, `Secure`, `SameSite`.
- Không lưu refresh token trong `localStorage`.
- Không tin role lưu ở frontend; backend luôn kiểm tra quyền.
- Password dùng BCrypt.
- Internal service token khác JWT người dùng.
- Secret production không có default value.
- CORS whitelist rõ ràng.
- Rate limit cho login, chat, text search và image search.
- Validate file upload và giới hạn payload.
- Log phải redact token, password, API key và PII.
- Payment callback phải kiểm tra chữ ký và idempotency.
- Admin endpoint phải có role guard.
- Internal endpoint phải xác thực service-to-service.
- Thêm dependency/secret scanning trong CI.

---

## 10. Quy trình phát triển nhóm

### 10.1. Phân công tham khảo cho nhóm 5 người

| Vai trò | Phạm vi chính |
|---|---|
| Thành viên 1 | Commerce Core, dữ liệu nghiệp vụ trong `public`, auth |
| Thành viên 2 | Storefront và trải nghiệm tìm kiếm/chat/checkout |
| Thành viên 3 | Admin, catalog, order, inventory, reports |
| Thành viên 4 | AI Retrieval, text/image search, recommendation và indexing |
| Thành viên 5 | Assistant/RAG, dataset/evaluation, tài liệu nghiên cứu và integration |

Mỗi phần phải có người review ngoài người viết.

### 10.2. Branching

- `main`: luôn chạy được và được bảo vệ.
- `feat/<issue>-<name>`: chức năng mới.
- `fix/<issue>-<name>`: sửa lỗi.
- `refactor/<issue>-<name>`: tái cấu trúc.
- Nhánh tồn tại ngắn, merge qua pull request.
- Không dùng nhánh dài hạn riêng cho từng thành viên.

### 10.3. Pull request

Mỗi PR phải có:

- Mục tiêu và issue liên quan.
- Danh sách thay đổi.
- Ảnh/video nếu thay đổi UI.
- Migration note nếu đổi schema.
- API/event contract nếu đổi integration.
- Cách kiểm thử.
- Rủi ro và rollback.
- Ít nhất một approval.

### 10.4. Definition of Done

- Code build thành công.
- Unit/integration tests liên quan pass.
- Migration được kiểm tra trên database sạch.
- API contract được cập nhật.
- Phân quyền và validation đầy đủ.
- Có loading/empty/error state nếu là UI.
- Log và error response không lộ secret.
- Có tài liệu vận hành ngắn.
- Benchmark không suy giảm ngoài ngưỡng cho phép.

---

## 11. CI/CD và môi trường

### 11.1. Môi trường

- `local`: PostgreSQL + `pgvector`, Redis và RabbitMQ bằng Docker Compose.
- `test`: Testcontainers/ephemeral database, tuyệt đối không trỏ vào Supabase chung.
- `integration/demo`: một Supabase project dùng chung cho code đã review và dữ liệu trình bày.

Không tạo thêm Supabase staging/production trong phạm vi đồ án nếu không có nhu cầu triển khai thực tế. Dữ liệu thử cá nhân không được ghi vào database demo dùng chung.

### 11.2. CI cho pull request

- Commerce: compile, unit test, integration test, migration validation.
- Python: lint, type check nếu áp dụng, unit/integration test.
- Frontend: lint, typecheck, build và smoke test.
- Validate OpenAPI/JSON Schema.
- Secret scan.
- Dependency scan.
- Build Docker image cho service thay đổi.

### 11.3. Local development

Docker Compose mục tiêu chạy:

- PostgreSQL + pgvector.
- Redis.
- RabbitMQ.
- Commerce Core.
- AI Retrieval API và worker.
- Assistant.
- Storefront.
- Admin.

Có thể hỗ trợ profile để chỉ chạy hạ tầng và khởi động service trực tiếp từ IDE.

---

## 12. Chiến lược kiểm thử

### 12.1. Commerce

- Unit test cho business rules.
- Repository integration test với PostgreSQL thật qua Testcontainers.
- Controller/security integration test.
- Payment signature/idempotency tests.
- Order/inventory concurrency tests.

### 12.2. AI Retrieval

- Query parser tests.
- Attribute extraction tests.
- Keyword/vector/fusion/rerank tests.
- Image validation/security tests.
- Indexing idempotency tests.
- RabbitMQ retry/DLQ integration tests.
- Provider client tests bằng fake transport.

### 12.3. Assistant

- Intent routing tests.
- Policy/confirmation tests.
- Tool contract tests.
- RAG evidence/answer consistency tests.
- Session/history tests.
- Không gọi provider thật trong unit tests.

### 12.4. Frontend

- Component tests cho phần quan trọng.
- API client tests.
- Playwright end-to-end cho:
  - Đăng nhập.
  - Tìm kiếm văn bản.
  - Tìm kiếm ảnh.
  - Chat tư vấn.
  - Thêm giỏ hàng.
  - Checkout COD.
  - Thanh toán online ở sandbox/mocked flow.
  - Admin tạo/cập nhật sản phẩm.
  - Nhập/xuất kho.

---

## 13. Kế hoạch đánh giá AI

### 13.1. Text search

Dataset phải có:

- Query ID.
- Câu truy vấn tự nhiên.
- Intent và thuộc tính mong đợi.
- Sản phẩm liên quan và relevance grade.
- Nhóm truy vấn: exact, attribute, need-based, ambiguous, typo và long-tail.

Đánh giá riêng bước phân tích truy vấn:

- Intent accuracy.
- Exact match và micro/macro-F1 cho product type, gender, sport, use case, color, size và price range.
- Tỷ lệ structured output hợp lệ và tỷ lệ giá trị map được vào taxonomy.
- So sánh rule-based, zero-shot, few-shot và hybrid rule + LLM trên cùng một tập query cố định.

Chỉ số:

- Precision@5, Precision@10.
- Recall@5, Recall@10.
- MRR.
- NDCG@5, NDCG@10.
- P50/P95 latency.

So sánh:

- Keyword-only.
- Semantic-only.
- Hybrid.
- Hybrid + rerank.

Tập development dùng để chỉnh prompt/weight phải tách khỏi tập test cuối. Không sửa prompt theo lỗi của test set rồi báo cáo lại trên chính test set đó.

### 13.2. Image search

Dataset phải dùng ảnh truy vấn độc lập với ảnh catalog nếu có thể.

Chỉ số:

- Precision@K.
- Recall@K.
- Top-1/top-5 product accuracy.
- Category accuracy.
- Color accuracy nếu có ground truth.
- P50/P95 latency.

Phân tích riêng:

- Ảnh nền đơn giản/phức tạp.
- Góc chụp khác nhau.
- Ánh sáng khác nhau.
- Sản phẩm cùng loại nhưng khác màu.
- Sản phẩm bị che khuất.

### 13.3. Recommendation

Chia train/test theo thời gian để tránh leakage.

Chỉ số:

- Precision@K.
- Recall@K.
- Hit Rate@K.
- NDCG@K.
- Coverage.
- Diversity nếu dữ liệu đủ.

So sánh:

- Popularity baseline.
- Content-based.
- Association rules.
- Hybrid recommendation.

### 13.4. Assistant/RAG

Đánh giá:

- Intent accuracy.
- Tool selection accuracy.
- Product relevance.
- Groundedness/faithfulness.
- Policy answer correctness.
- Action success rate.
- Hallucination rate.
- P95 response latency.

---

## 14. Kế hoạch triển khai theo giai đoạn

### Trạng thái thực tế ngày 04/10/2026

Đã hoàn thành:

- [x] Chốt kiến trúc dùng một Supabase project mới cho integration/demo.
- [x] Tạo Supabase project mới tại Singapore.
- [x] Cấu hình Session Pooler, SSL và xác nhận kết nối PostgreSQL chỉ đọc thành công.
- [x] Xác nhận database mới đang trắng ở cấp ứng dụng: schema `public` có 0 bảng ứng dụng.
- [x] Cấu hình Cloudinary và xác nhận Admin API chỉ đọc trả HTTP 200.
- [x] Chuẩn hóa `backend/.env`, bổ sung secret nội bộ và tắt Flyway/seed/AI feature trong lúc thiết kế lại.
- [x] Giữ `SPRING_FLYWAY_ENABLED=false`, `APP_SEED_ENABLED=false`, `PRODUCT_HYBRID_SEARCH_ENABLED=false` và `VISUAL_SEARCH_ENABLED=false`.

Chưa hoàn thành hoặc chưa được xác minh:

- [ ] Backup/export database cũ và Flyway history.
- [ ] Tạo tag Git cho phiên bản code cũ.
- [x] Commit phiên bản đầu của `plan.md` tại commit `e74ce68` (`docs: add v2 architecture and data rebuild plan`).
- [ ] Xác định chủ sở hữu thay đổi chưa commit tại `VisualSearchDialog.tsx`.
- [ ] Chạy toàn bộ test và lưu baseline report.
- [ ] Thiết kế ERD/data dictionary cho database mới.
- [ ] Bật extension `vector`; hiện tại `pgvector` chưa được bật trên Supabase mới.
- [ ] Tạo baseline migration mới.
- [ ] Kiểm tra upload/delete ảnh thật qua backend; hiện mới xác thực Cloudinary ở chế độ chỉ đọc.
- [ ] Xây dựng và version hóa dataset v2.

Việc tiếp theo ưu tiên: hoàn thành bảo toàn code/dữ liệu cũ, sau đó thiết kế data dictionary + ERD trước khi tạo bất kỳ bảng ứng dụng nào trên Supabase mới.

### Giai đoạn 0 - Bảo toàn hiện trạng và lập baseline

Mục tiêu: có điểm phục hồi trước khi tái cấu trúc.

- [ ] Tạo tag Git cho phiên bản hiện tại.
- [ ] Ghi nhận file đang thay đổi chưa commit và chủ sở hữu.
- [ ] Backup database hiện tại và giữ project cũ read-only.
- [x] Tạo một Supabase project mới trắng ở cấp ứng dụng.
- [ ] Export schema và danh sách Flyway history.
- [ ] Chạy toàn bộ test hiện có và lưu báo cáo.
- [ ] Chạy smoke test các luồng chính.
- [ ] Lưu benchmark text/image hiện tại làm baseline.
- [ ] Liệt kê API và event đang được frontend/service sử dụng.
- [ ] Viết ADR về kiến trúc mục tiêu.

Tiêu chí hoàn thành:

- Có tag, backup, test report và baseline benchmark.
- Có thể khôi phục code/database về trước tái cấu trúc.

### Giai đoạn 1 - Thu gọn phạm vi và chuẩn hóa môi trường

- [ ] Chuyển `ai_forecasting_service` sang khu vực experimental hoặc tắt khỏi runtime mặc định.
- [ ] Chuyển `chatbot-admin-service` sang experimental hoặc tắt khỏi runtime mặc định.
- [ ] Dọn log/runtime artifacts khỏi root và cập nhật `.gitignore`.
- [ ] Chuẩn hóa `.env.example` cho từng service.
- [ ] Loại bỏ secret mặc định nguy hiểm.
- [ ] Chuẩn hóa phiên bản Node/Next cho hai frontend nếu khả thi.
- [ ] Tạo Dockerfile chuẩn cho các service chính.
- [ ] Tạo Compose profiles cho infra và full stack.

Tiêu chí hoàn thành:

- Hệ thống chính chạy mà không cần Forecasting/Admin Copilot.
- Một thành viên mới có thể khởi động dự án từ tài liệu.

### Giai đoạn 2 - Phân tích thiết kế, domain, dữ liệu và database

- [ ] Vẽ Context diagram.
- [ ] Vẽ Container diagram.
- [ ] Vẽ Use Case tổng thể và xác định actor/quyền hạn.
- [ ] Vẽ domain/Class diagram cho các aggregate chính.
- [ ] Vẽ Sequence cho đặt hàng/thanh toán, text search, image search, recommendation và chatbot RAG.
- [ ] Vẽ ERD nghiệp vụ trong schema `public`.
- [ ] Vẽ ERD schema AI Retrieval/Evaluation.
- [ ] Vẽ ERD schema `assistant`.
- [ ] Chốt taxonomy môn thể thao, mục đích, chất liệu và công nghệ.
- [ ] Chốt dữ liệu bắt buộc/tùy chọn của sản phẩm.
- [ ] Viết data dictionary V2 và mapping dữ liệu cũ sang V2.
- [ ] Chốt data retention cho chat và interaction.
- [ ] Tạo database roles và permission matrix.
- [ ] Backup và kiểm kê Supabase cũ để làm nguồn đối chiếu.
- [x] Chưa chạy migration cũ lên Supabase mới; đã xác nhận `public` có 0 bảng ứng dụng.
- [ ] Chốt ERD và tạo baseline migration mới từ thiết kế đã duyệt.
- [ ] Đóng băng/checksum raw catalog hiện có và tạo pilot normalized dataset 30-50 sản phẩm.
- [ ] Viết evaluation protocol trước khi tối ưu mô hình; tạo development set và test set tách biệt phiên bản đầu.
- [ ] Chuẩn bị tập query/ảnh pilot đủ để kiểm tra ERD, indexing và metric end-to-end.

Tiêu chí hoàn thành:

- Mỗi bảng có owner.
- Không còn bảng “dùng chung nhưng không ai chịu trách nhiệm”.
- Use Case, Class, Sequence, ERD, data dictionary và migration plan được cả nhóm review.
- Baseline migration dựng được database local sạch; pilot dataset import được và có validation report.
- Evaluation protocol được chốt trước khi dùng test set để lựa chọn mô hình.

### Giai đoạn 3 - Tái cấu trúc Commerce Core

- [ ] Giữ schema nghiệp vụ hiện tại là `public` để tránh sửa JPA/native SQL hàng loạt.
- [ ] Chuẩn hóa các module theo bounded context.
- [ ] Giữ nguyên API cần thiết hoặc có compatibility layer.
- [ ] Bổ sung taxonomy sản phẩm thể thao.
- [ ] Bổ sung `user_interactions`.
- [ ] Chuẩn hóa transactional outbox.
- [ ] Phát catalog và order events.
- [ ] Bổ sung low-stock threshold/alert trong Commerce.
- [ ] Loại bỏ migration sở hữu bởi AI service.
- [ ] Chuyển recommendation Spring hiện tại thành compatibility adapter tạm thời hoặc tắt sau khi AI Retrieval đạt parity; không duy trì hai nguồn recommendation chuẩn.
- [ ] Viết migration/import script.

Tiêu chí hoàn thành:

- Commerce chạy độc lập khi AI service tắt.
- CRUD catalog, cart, order, payment và inventory vẫn hoạt động.
- Outbox event được tạo cùng transaction nghiệp vụ.

### Giai đoạn 4 - Xây AI Retrieval Service thống nhất

- [ ] Tạo skeleton service và migration riêng.
- [ ] Tạo search document read model.
- [ ] Vertical slice 4A: query normalization, structured attribute extraction, keyword search và metric pilot.
- [ ] Vertical slice 4B: text embedding/indexer, vector search, RRF/reranker và metric pilot.
- [ ] Vertical slice 4C: image preprocessing/embedding, visual search và metric pilot.
- [ ] Vertical slice 4D: popularity/content/behavior recommendation và metric pilot.
- [ ] Vertical slice 4E: ingest/chunk/embed tài liệu chính sách và knowledge retrieval có citation metadata.
- [ ] Tạo model registry thống nhất.
- [ ] Tạo indexing job model thống nhất.
- [ ] Tạo event consumer idempotent.
- [ ] Tạo retry/DLQ/reconciliation.
- [ ] Bổ sung evaluation API/runner.

Tiêu chí hoàn thành:

- AI Retrieval không ghi các bảng nghiệp vụ do Commerce sở hữu trong `public`.
- Full reindex có thể chạy từ event/backfill.
- Text/image search đạt hoặc vượt baseline.
- Recommendation được đánh giá trên split theo thời gian và có phương án cold-start.
- Provider lỗi không làm Commerce ngừng hoạt động.

### Giai đoạn 5 - Tái cấu trúc Assistant Service

- [ ] Loại bỏ direct database read tới Commerce.
- [ ] Tạo Commerce API client typed.
- [ ] Tạo AI Retrieval API client typed.
- [ ] Chuẩn hóa tool registry.
- [ ] Giữ confirmation cho hành động thay đổi dữ liệu.
- [ ] Tạo migration và repository cho các bảng chat do Assistant sở hữu trong schema `assistant`.
- [ ] Giữ Redis cho context tạm.
- [ ] Thêm citation/evidence vào câu trả lời.
- [ ] Thêm feedback API.
- [ ] Thêm streaming integration.

Tiêu chí hoàn thành:

- Chatbot hoạt động khi chỉ được cấp API access, không có quyền đọc Commerce DB.
- Các câu trả lời chính sách có nguồn.
- Hành động giỏ hàng/đơn hàng có kiểm tra quyền và confirmation.

### Giai đoạn 6 - Authentication và frontend integration

- [ ] Chuyển refresh token sang HttpOnly cookie.
- [ ] Loại refresh token khỏi `localStorage`.
- [ ] Chuẩn hóa API client và refresh flow.
- [ ] Không dùng role frontend làm nguồn phân quyền.
- [ ] Tích hợp text/hybrid search UI.
- [ ] Tích hợp image upload/search UI.
- [ ] Tích hợp chatbot streaming và product cards.
- [ ] Tích hợp recommendation impression/click events.
- [ ] Hoàn thiện admin màn hình indexing/evaluation.
- [ ] Kiểm tra responsive và accessibility cơ bản.

Tiêu chí hoàn thành:

- Storefront/Admin không cần biết địa chỉ nội bộ của AI service.
- Các luồng người dùng chính có E2E test.

### Giai đoạn 7 - Dataset và evaluation

- [ ] Mở rộng và chốt version catalog từ pilot Giai đoạn 2.
- [ ] Mở rộng, review và khóa tập text queries/ground truth; giữ test set chưa dùng để tuning.
- [ ] Mở rộng và khóa tập image queries độc lập.
- [ ] Xây recommendation split theo thời gian.
- [ ] Chạy các baseline.
- [ ] Chạy từng mô hình/chiến lược.
- [ ] Chạy ma trận prompt rule-based/zero-shot/few-shot/hybrid cho query understanding.
- [ ] Lưu kết quả vào database và export JSON/CSV.
- [ ] Phân tích failure cases.
- [ ] Báo cáo latency, token/API usage và chi phí ước tính cho từng cấu hình.
- [ ] Chốt cấu hình cuối dựa trên số liệu.

Tiêu chí hoàn thành:

- Có thể chạy lại thí nghiệm từ tài liệu/script.
- Mọi bảng số liệu trong luận văn truy ngược được về một evaluation run.

### Giai đoạn 8 - Hardening và hoàn thiện báo cáo

- [ ] Load/smoke test.
- [ ] Security review.
- [ ] Kiểm tra backup/restore.
- [ ] Kiểm tra DLQ và replay.
- [ ] Kiểm tra model rollback.
- [ ] Cập nhật Use Case, Class, Sequence, ERD và deployment diagrams đã thiết kế từ Giai đoạn 2 theo implementation cuối.
- [ ] Hoàn thiện mô tả thuật toán và công thức metric.
- [ ] Chuẩn bị demo script và dữ liệu demo ổn định.
- [ ] Tạo release tag cuối.

Tiêu chí hoàn thành:

- Có bản demo ổn định.
- Có tài liệu cài đặt từ máy sạch.
- Có kết quả đánh giá và bằng chứng kỹ thuật đầy đủ.

---

## 15. Thứ tự pull request khuyến nghị

1. `docs: freeze MVP scope and add target architecture/ownership ADR`
2. `docs: add use cases, domain/sequence diagrams, data dictionary and evaluation protocol`
3. `chore: isolate experimental services from default runtime`
4. `infra: add full local compose and health checks`
5. `db: add reviewed V2 baseline, service-owned schemas and roles`
6. `data: add taxonomy and versioned pilot catalog pipeline`
7. `commerce: adapt core modules to V2 baseline`
8. `commerce: add user interaction events`
9. `commerce: standardize catalog transactional outbox`
10. `contracts: publish catalog event v2 and OpenAPI contracts`
11. `ai-retrieval: create service skeleton and migrations`
12. `ai-retrieval: consume catalog events and build read model`
13. `ai-retrieval: migrate structured and hybrid text search`
14. `ai-retrieval: migrate visual search into ai_retrieval schema`
15. `ai-retrieval: implement recommendation pipeline`
16. `ai-retrieval: implement evaluation storage and runner`
17. `assistant: add assistant schema and remove direct commerce database access`
18. `assistant: integrate retrieval and commerce clients`
19. `security: migrate refresh flow to HttpOnly cookie`
20. `storefront: integrate unified search and assistant`
21. `admin: integrate AI operations and evaluation screens`
22. `test: add cross-service E2E suite and reproducible benchmark runner`

---

## 16. Rủi ro và biện pháp xử lý

| Rủi ro | Biện pháp |
|---|---|
| Viết lại quá nhiều cùng lúc | Chia theo giai đoạn, giữ compatibility layer |
| Mất dữ liệu khi đổi schema | Backup, database mới, import script và rehearsal |
| Migration xung đột | Ownership rõ, không sửa migration đã merge |
| AI provider không ổn định | Timeout, retry, fake provider, fallback keyword |
| Embedding model thay đổi | Model registry và versioned index |
| RabbitMQ gửi trùng | Idempotent consumer và processed event table |
| Search index lỗi thời | Reconciliation job và coverage dashboard |
| Team merge conflict | PR nhỏ, CODEOWNERS, module ownership |
| Thiếu dữ liệu recommendation | Interaction logging sớm, baseline rõ ràng |
| Benchmark bị leakage | Query/ảnh độc lập, split theo thời gian |
| Prompt overfit vào test set | Tách development/test, khóa test set và version hóa prompt |
| Chi phí API vượt ngân sách | Local baseline, cache theo hash, budget guard và báo cáo cost/run |
| Hết connection Supabase Free | Pool nhỏ theo service, local DB cho dev/test, theo dõi connection |
| Scope creep | Forecasting/Admin Copilot để experimental |
| Secret bị lộ | Secret scanning, `.env.example`, rotate credential |

---

## 17. Tiêu chí nghiệm thu toàn hệ thống

### Chức năng

- Người dùng tìm sản phẩm bằng bộ lọc, ngôn ngữ tự nhiên và hình ảnh.
- Chatbot tư vấn được sản phẩm, size và chính sách dựa trên dữ liệu có nguồn.
- Recommendation xuất hiện trên các vị trí đã định và ghi nhận impression/click.
- Người dùng đặt hàng và thanh toán COD/VNPAY.
- Admin quản lý catalog, người dùng, tham số hệ thống, đơn hàng, tồn kho và báo cáo.

### Kiến trúc

- Không service AI nào ghi trực tiếp bảng Commerce.
- Không còn migration AI thuộc Commerce Core.
- Mỗi schema/bảng có đúng một write owner; Assistant không đọc trực tiếp bảng Commerce.
- Event consumer idempotent và có DLQ.
- Commerce vẫn hoạt động khi AI provider tạm thời lỗi.

### AI và nghiên cứu

- Có dataset versioned cho ba bài toán.
- Có baseline và mô hình đề xuất.
- Có Precision/Recall/MRR/NDCG/Hit Rate phù hợp.
- Có kết quả query understanding/prompt engineering trên test set tách biệt.
- Có latency và failure analysis.
- Có model/prompt version, token/API usage và chi phí ước tính.
- Kết quả có thể tái lập bằng script và configuration đã lưu.

### Chất lượng

- CI pass.
- Các luồng chính có integration/E2E tests.
- Không có secret trong Git.
- Có backup/restore và hướng dẫn cài đặt.
- Có tài liệu kiến trúc, ERD và sequence diagrams cập nhật.
- Có phần tổng quan lý thuyết, lựa chọn phương pháp và giới hạn nghiên cứu liên kết với bằng chứng thực nghiệm.

---

## 18. Việc cần quyết định trước khi bắt đầu code

- [ ] Số thành viên và người phụ trách từng module.
- [x] Tạo một Supabase project mới trắng cho tích hợp/demo; giữ project cũ read-only; local/test dùng PostgreSQL cục bộ.
- [ ] Có giữ Google login trong phạm vi cuối hay không.
- [ ] Embedding provider cuối cho text và image.
- [ ] LLM provider chính và fallback.
- [ ] Baseline local/mã nguồn mở và ma trận mô hình/prompt cần so sánh.
- [ ] Bộ taxonomy sản phẩm thể thao.
- [ ] Quy mô dataset benchmark.
- [ ] Chính sách lưu chat và interaction data.
- [x] Không tạo staging riêng trong phạm vi đồ án; Supabase chung là integration/demo.
- [ ] Mức ngân sách API AI tối đa.
- [ ] Ngưỡng nghiệm thu metric/latency sau khi có baseline, không đặt số tùy ý trước dữ liệu.

Không bắt đầu di chuyển schema hoặc xóa service trước khi hoàn thành các quyết định trên và Giai đoạn 0.

---

## 19. Hướng dẫn cấu hình một Supabase dùng chung

### 19.1. Kiến trúc triển khai

- Một Supabase project mới, ưu tiên region Singapore.
- Một PostgreSQL database.
- Bật extension `vector` để dùng `pgvector`.
- Giữ bảng nghiệp vụ trong `public`.
- Tạo `ai_retrieval`, `evaluation` và `assistant` bằng migration. Image embedding nằm trong `ai_retrieval`; không tạo schema `visual_search` mới.
- Storefront/Admin chỉ gọi backend API, không gọi trực tiếp Data API của Supabase.
- Không thêm `ai_retrieval`, `evaluation`, `assistant` vào danh sách Exposed schemas nếu frontend không cần truy cập trực tiếp.

### 19.2. Chiến lược tạo trắng

“Tạo trắng” nghĩa là Supabase mới chưa có bảng nghiệp vụ của ứng dụng. Supabase vẫn tự có các schema hệ thống như `auth`, `storage`, `extensions`, `realtime` và các schema quản trị; không xóa hoặc sửa các schema hệ thống này.

Thực hiện theo thứ tự:

1. Backup hoặc `pg_dump` database cũ.
2. Xuất `flyway_schema_history` và danh sách dữ liệu cần giữ để tham khảo.
3. Giữ project cũ ở chế độ read-only về mặt quy trình nhóm.
4. Tạo Supabase project mới tại Singapore.
5. Lưu credential mới vào secret store/`.env` cục bộ, không commit Git.
6. `backend/.env` hiện đã trỏ tới project mới nhưng phải giữ Flyway, seed và các feature AI ở trạng thái tắt; không khởi động luồng nghiệp vụ vào database trắng trước khi baseline schema tồn tại.
7. Thiết kế ERD, ownership, constraint và index.
8. Tạo baseline migration mới trên một PostgreSQL local sạch.
9. Review migration bằng pull request.
10. Chạy baseline migration lên Supabase mới đúng một lần.
11. Seed dữ liệu chuẩn hoặc import dữ liệu đã chọn từ project cũ.
12. Chỉ sau khi smoke test thành công mới chuyển các service sang connection mới.

Checklist:

- [ ] Backup database cũ.
- [ ] Kiểm kê schema/table/extension/role của database cũ.
- [x] Tạo project Supabase mới tại Singapore và kiểm tra kết nối SSL thành công.
- [ ] Đổi/rotate credential nếu secret cũ từng bị lộ.
- [ ] Phân công một thành viên chịu trách nhiệm migration.
- [ ] Chốt ERD trước khi tạo bảng ứng dụng.
- [ ] Có baseline migration tái tạo được toàn bộ schema mới.

### 19.3. Kiểm tra `pgvector`

Trong Supabase Dashboard:

1. Mở `Database`.
2. Mở `Extensions`.
3. Tìm extension `vector`.
4. Enable nếu chưa bật.
5. Trên project mới, giữ cách cài extension mặc định của Supabase và ghi lại schema chứa extension trong baseline migration.

Có thể kiểm tra bằng SQL Editor:

```sql
select extname, extversion
from pg_extension
where extname = 'vector';
```

### 19.4. Tạo schema AI mới

Không chạy trực tiếp trên database chung cho đến khi SQL này được đưa vào migration và kiểm tra local:

```sql
create schema if not exists ai_retrieval;
create schema if not exists evaluation;
create schema if not exists assistant;
```

Schema `visual_search` chỉ thuộc hệ thống cũ. Nếu gặp trong database nguồn thì export để đối chiếu/mapping; không tái tạo trên database V2.

Kiểm tra:

```sql
select schema_name
from information_schema.schemata
where schema_name in ('public', 'ai_retrieval', 'evaluation', 'assistant')
order by schema_name;
```

### 19.5. Connection string

Lấy connection string tại `Project Dashboard -> Connect`.

Với Spring Boot/FastAPI chạy lâu dài:

- Dùng direct connection nếu môi trường chạy hỗ trợ IPv6.
- Nếu cần IPv4, dùng Supavisor session mode.
- Dùng SSL (`sslmode=require`).
- Migration/Flyway dùng direct connection hoặc session mode, không dùng transaction mode.
- Không in connection string vào log hoặc commit vào Git.

Các service có thể cùng host/database nhưng dùng biến cấu hình riêng:

```dotenv
# Spring Boot
DB_HOST=<supabase-host-or-session-pooler>
DB_PORT=<port>
DB_NAME=postgres
DB_USERNAME=<server-side-role>
DB_PASSWORD=<secret>
DB_PARAMS=?sslmode=require

# Python AI service
DATABASE_URL=postgresql://<role>:<secret>@<host>:<port>/postgres?sslmode=require
```

Không sao chép giá trị thật vào tài liệu hoặc frontend `.env.local`.

### 19.6. Connection pool ban đầu

- Commerce Core: tối đa 8 connection.
- AI Retrieval API + worker: tổng tối đa 5 connection.
- Assistant: tối đa 3 connection.
- Theo dõi connection trong Supabase Dashboard trước khi tăng.
- Không để mỗi thành viên chạy nhiều service local cùng trỏ liên tục vào Supabase chung.

### 19.7. Quy trình migration của nhóm

1. Thành viên tạo migration trong feature branch.
2. Chạy migration trên PostgreSQL local sạch.
3. Chạy unit/integration tests.
4. Tạo pull request và được review.
5. Merge vào nhánh chung.
6. Người phụ trách hoặc CI chạy migration một lần lên Supabase.
7. Kiểm tra Flyway/Alembic history và smoke test.
8. Nếu lỗi, dừng rollout và dùng migration sửa tiếp theo; không sửa migration đã áp dụng.

### 19.8. Quy tắc dữ liệu dùng chung

- Dữ liệu demo có ID/marker ổn định và seed idempotent.
- Không chạy test tự động lên Supabase chung.
- Không xóa hàng loạt dữ liệu chung khi chưa backup và xác nhận phạm vi.
- Không dùng database chung để thử migration chưa review.
- Mỗi benchmark phải ghi dataset version và Git commit.

---

## 20. Hướng dẫn cấu hình Cloudinary

### 20.1. Mô hình sử dụng

- Chỉ backend Spring Boot upload/xóa ảnh bằng authenticated API.
- Frontend gửi file đến backend, không giữ `CLOUDINARY_API_SECRET`.
- Cloudinary lưu binary image.
- PostgreSQL lưu `public_id`, `secure_url`, width, height, màu và metadata liên quan.
- Visual Search tải ảnh catalog từ `res.cloudinary.com` theo allowlist hiện có.

### 20.2. Tạo và lấy thông tin cấu hình

1. Đăng nhập Cloudinary Console.
2. Chọn đúng product environment.
3. Mở trang API Keys/Developer Dashboard.
4. Lấy `cloud name`, `API key` và `API secret`.
5. Không gửi API secret qua chat, ảnh chụp màn hình hoặc commit Git.
6. Nếu secret từng bị lộ, rotate secret trước khi tiếp tục.

### 20.3. Cấu hình backend hiện tại

Thêm vào `backend/.env` bằng giá trị thật trên máy của từng thành viên:

```dotenv
APP_STORAGE_PROVIDER=cloudinary
CLOUDINARY_CLOUD_NAME=<cloud-name>
CLOUDINARY_API_KEY=<api-key>
CLOUDINARY_API_SECRET=<api-secret>
```

Code hiện tại:

- Chỉ tạo Cloudinary bean khi `APP_STORAGE_PROVIDER=cloudinary`.
- Fail fast nếu thiếu một trong ba credential.
- Bật `secure=true`, nên URL trả về dùng HTTPS.
- Upload signed từ backend.
- Upload ảnh vào thư mục cố định `products`.
- `CLOUDINARY_PRODUCT_IMAGE_FOLDER` hiện chưa được implementation sử dụng; không dựa vào biến này cho đến khi refactor code.

### 20.4. Tổ chức thư mục

Để thay đổi ít nhất, tiếp tục dùng thư mục hiện tại:

```text
products/
```

Chỉ refactor thành `smart-sportswear/products` nếu cả code upload, dữ liệu `public_id`, delete flow và visual-search allowlist đã được kiểm thử. Không tự di chuyển asset đang dùng vì thay đổi public ID/URL có thể làm hỏng dữ liệu catalog tùy folder mode.

### 20.5. Upload preset

Luồng hiện tại upload signed từ backend nên không bắt buộc tạo unsigned upload preset.

Nếu sau này upload trực tiếp từ trình duyệt:

- Ưu tiên signed upload, chữ ký do backend sinh.
- Tuyệt đối không đưa API secret vào frontend.
- Nếu buộc dùng unsigned preset, giới hạn định dạng, không cho client đặt public ID tùy ý và áp dụng normalization trong preset.

### 20.6. Kiểm tra sau cấu hình

1. Khởi động backend và kiểm tra health.
2. Đăng nhập Admin.
3. Upload một ảnh JPEG/PNG/WebP hợp lệ dưới giới hạn backend.
4. Kiểm tra asset xuất hiện trong Cloudinary folder `products`.
5. Kiểm tra database lưu `secure_url` và `public_id`.
6. Mở URL ảnh bằng HTTPS.
7. Đổi ảnh/xóa ảnh thử nghiệm và kiểm tra Cloudinary destroy flow.
8. Nếu dùng Visual Search, kiểm tra worker tải được URL và tạo embedding.

### 20.7. Quy tắc làm việc nhóm với Cloudinary

- Cả nhóm có thể dùng một Cloudinary product environment để tiết kiệm chi phí.
- Chỉ chia sẻ credential qua kênh bí mật, không qua Git.
- Dùng tài khoản/thành viên Cloudinary riêng nếu gói hỗ trợ; không chia sẻ mật khẩu đăng nhập.
- Asset demo phải có quy ước tên/tag để tránh xóa nhầm.
- Không chạy cleanup hàng loạt từ máy cá nhân.
- Database phải lưu `public_id`; không chỉ lưu URL vì thao tác xóa cần public ID.

### 20.8. Tiêu chí hoàn thành hạ tầng lưu trữ

- [x] Một Supabase project mới hoạt động và kết nối Session Pooler thành công.
- [ ] Bật và kiểm tra extension `pgvector` sau khi thiết kế vector schema.
- [ ] Baseline migration V2 chạy/validate thành công trên PostgreSQL local sạch và Supabase mới; không chạy chuỗi 52 migration legacy.
- [ ] Các schema `public`, `ai_retrieval`, `evaluation`, `assistant` đúng ownership.
- [ ] Local/test database không trỏ vào Supabase chung.
- [x] Cloudinary credential xác thực thành công bằng API chỉ đọc.
- [ ] Backend upload và xóa ảnh Cloudinary thành công.
- [ ] Không có secret trong Git hoặc frontend bundle.
- [ ] Catalog lưu cả `secure_url` và `public_id`.
- [ ] Text/image search đọc được dữ liệu vector sau indexing.

---

## 21. Kế hoạch làm lại dữ liệu từ đầu

### 21.1. Mục tiêu

Database mới là greenfield ở cấp ứng dụng. Không restore nguyên database cũ và không chạy chuỗi migration cũ lên Supabase mới. Dữ liệu v2 được thiết kế lại theo yêu cầu đề tài, có nguồn gốc, có phiên bản và có thể tái tạo bằng script.

Nguyên tắc:

- Database cũ chỉ là nguồn tham khảo, không phải nguồn schema chuẩn.
- Không nhập password hash, refresh token, password-reset token hoặc session cũ.
- Không nhập dữ liệu test lẫn với dữ liệu benchmark.
- Không sao chép embeddings cũ; luôn sinh lại từ dữ liệu catalog đã chuẩn hóa.
- Không sử dụng dữ liệu đơn hàng giả như dữ liệu người dùng thật; phải gắn nhãn `DEMO`/`SYNTHETIC`.
- Mọi seed/import phải idempotent và có báo cáo số lượng, lỗi, checksum.
- Không tạo bảng trực tiếp bằng Supabase Table Editor; schema phải được tạo từ baseline migration.

### 21.2. Kiểm kê dữ liệu hiện có

Nguồn seed hiện tại `backend/src/main/resources/seed/real-running-catalog.json` có:

- 60 sản phẩm.
- 2 thương hiệu.
- 2 danh mục.
- 3 nhóm giới tính: `MEN`, `WOMEN`, `UNISEX`.
- Chỉ 1 môn thể thao: `running`.
- Chỉ 1 loại tổng quát: `APPAREL`.
- 300 URL ảnh.
- 247 giá trị size và 501 source SKU.

Kết luận: nguồn hiện tại có thể tái sử dụng làm **raw input**, nhưng chưa đủ độ đa dạng cho đề tài trang phục thể thao tổng quát, tìm kiếm ảnh và recommendation. Không đưa thẳng file này vào database mới nếu chưa normalize, kiểm tra nguồn ảnh và bổ sung taxonomy.

### 21.3. Phân loại dữ liệu v2

| Nhóm dữ liệu | Cách xây lại | Có chuyển từ hệ cũ? |
|---|---|---|
| Taxonomy | Thiết kế thủ công, seed có version | Không nhập thẳng |
| Catalog sản phẩm | Làm sạch nguồn cũ + bổ sung dữ liệu mới | Chuyển có chọn lọc |
| Variant/SKU | Sinh từ size, màu và SKU đã kiểm tra | Chuyển có biến đổi |
| Ảnh sản phẩm | Kiểm tra quyền sử dụng, upload vào Cloudinary mới | Không hotlink tùy tiện |
| User | Tạo admin mới và tài khoản demo | Không chuyển credential cũ |
| Tồn kho | Seed số lượng hợp lý theo variant | Tạo mới |
| Đơn hàng/thanh toán | Sinh kịch bản demo có nhãn | Không giả làm dữ liệu thật |
| Interaction | Sinh/thu thập theo event schema mới | Tạo mới |
| Text/image embeddings | Index lại sau khi catalog ổn định | Không chuyển vector cũ |
| Knowledge/RAG | Viết/duyệt chính sách và FAQ, lưu nguồn + version rồi chunk/index lại | Chuyển có biên tập |
| Chat history | Bắt đầu mới | Không chuyển mặc định |
| Evaluation ground truth | Gán nhãn và version độc lập | Tạo mới |

### 21.4. Dataset catalog mục tiêu

Mục tiêu ban đầu để đủ độ đa dạng cho nghiên cứu, có thể điều chỉnh sau pilot:

- 150-300 sản phẩm hợp lệ.
- Ít nhất 5 môn thể thao, ví dụ chạy bộ, gym/fitness, bóng đá, cầu lông và đạp xe.
- Bao phủ áo, quần, giày và phụ kiện; có thể bổ sung đồ bảo hộ nếu dữ liệu đủ.
- Bao phủ `MEN`, `WOMEN`, `UNISEX`.
- Có nhiều khoảng giá, thương hiệu, màu, size, chất liệu và mục đích sử dụng.
- Mỗi sản phẩm có ít nhất một biến thể hợp lệ và một ảnh hợp lệ.
- Mỗi ảnh có `public_id`, `secure_url`, width, height, source/license và checksum nếu có.
- SKU và slug duy nhất.

Các con số trên là target thiết kế, không phải dữ liệu được tự động bịa. Mỗi bản ghi phải có nguồn hoặc được đánh dấu synthetic rõ ràng.

### 21.5. Taxonomy cần chốt trước ERD

- Category tree.
- Product type.
- Sport.
- Gender/audience.
- Use case: training, competition, daily wear, recovery.
- Material.
- Technology/features: moisture-wicking, breathable, waterproof, thermal, compression.
- Color chuẩn và color family.
- Size system và size value.
- Price band.
- Season/weather và indoor/outdoor nếu cần.

Taxonomy phải có mã ổn định, tên hiển thị tiếng Việt, slug, trạng thái và version. Không dùng text tự do cho thuộc tính cần lọc hoặc đánh giá.

### 21.6. Cấu trúc thư mục dữ liệu

```text
datasets/
├── raw/
│   ├── catalog/
│   └── manifests/
├── normalized/
│   ├── catalog-v2.json
│   ├── taxonomy-v1.json
│   ├── knowledge-v1.json
│   └── validation-report.json
├── seed/
│   ├── reference-data/
│   ├── catalog/
│   ├── knowledge/
│   └── demo-transactions/
└── evaluation/
    ├── text-search/
    ├── image-search/
    └── recommendation/
```

Không commit API key, PII hoặc ảnh benchmark không có quyền phân phối. File ảnh lớn có thể nằm ngoài Git; manifest và checksum được commit để tái lập.

### 21.7. Pipeline làm lại catalog

1. Đóng băng và checksum raw input.
2. Lập mapping từ field cũ sang data model v2.
3. Normalize slug, Unicode, giá, gender, sport, product type, material và color.
4. Phát hiện SKU/slug trùng.
5. Loại bản ghi thiếu giá, variant hoặc nguồn ảnh hợp lệ.
6. Kiểm tra URL ảnh, MIME, kích thước và quyền sử dụng.
7. Upload ảnh được duyệt lên Cloudinary folder `products`.
8. Ghi lại `public_id`, `secure_url`, width, height và checksum.
9. Sinh file normalized và validation report.
10. Import taxonomy trước, catalog sau, variant và ảnh cuối cùng.
11. Đối soát count, foreign key, unique constraint và tổng tồn kho.
12. Chỉ sau khi catalog ổn định mới chạy text/image indexing.

### 21.8. Dữ liệu người dùng và giao dịch demo

- Tạo đúng một admin bootstrap bằng biến môi trường hoặc command riêng; không hard-code password vào seed.
- Tạo một tập user demo không chứa PII thật.
- Sinh đơn hàng theo các trạng thái hợp lệ và theo thời gian để phục vụ báo cáo.
- Thanh toán demo chỉ dùng COD hoặc VNPAY sandbox; không lưu thông tin thẻ.
- Mọi đơn hàng, interaction và forecast giả phải có `data_source='DEMO'` hoặc metadata tương đương.
- Không trộn DEMO với REAL trong báo cáo/evaluation mà không có bộ lọc.

### 21.9. Dữ liệu recommendation

Interaction schema tối thiểu:

- `event_id`
- `occurred_at`
- `user_id` hoặc anonymous/session ID đã pseudonymize
- `event_type`
- `product_id`
- `variant_id` nếu có
- `source` và `request_id`
- `algorithm`/`model_version` nếu là recommendation
- `data_source`

Các event: impression, view, search click, recommendation click, add-to-cart, wishlist và purchase. Dataset đánh giá phải split theo thời gian để tránh leakage.

### 21.10. Dữ liệu evaluation

- Text search: tối thiểu 100 query độc lập, có intent, extracted attributes và relevance labels.
- Image search: tối thiểu 100 ảnh truy vấn độc lập với catalog originals, có product/category ground truth.
- Recommendation: train/test theo thời gian, lưu danh sách relevant items cho mỗi context/user giả lập.
- Mỗi dataset có `dataset_id`, version, created_at, source, license, checksum và reviewer.
- Kết quả benchmark phải liên kết với dataset version, model version, Git commit và configuration.

### 21.11. Kiểm tra chất lượng dữ liệu

Trước mỗi lần import, pipeline phải kiểm tra:

- Unique slug, SKU, email demo và public ID.
- Foreign key không bị orphan.
- Giá và số lượng không âm.
- Reserved quantity không vượt stock policy đã chọn.
- Product ACTIVE phải có variant ACTIVE và ảnh primary.
- Attribute code phải tồn tại trong taxonomy.
- URL Cloudinary dùng HTTPS và đúng cloud name.
- Không có placeholder secret hoặc PII thật.
- Dataset benchmark không dùng trực tiếp ảnh catalog làm query nếu bài đánh giá yêu cầu ảnh độc lập.

### 21.12. Reset và xóa dữ liệu an toàn

- Chỉ reset tự động trên PostgreSQL local/test.
- Không viết script `drop schema public cascade` dùng chung cho Supabase.
- Tuyệt đối không xóa các schema hệ thống `auth`, `storage`, `extensions`, `realtime`.
- Script reset Supabase phải yêu cầu environment guard, tên project và xác nhận rõ.
- Xóa dữ liệu demo theo `data_source`/batch ID, không xóa toàn bộ bảng.
- Xóa asset Cloudinary phải dựa trên danh sách `public_id` thuộc batch import và có dry-run.

### 21.13. Thứ tự thực hiện data v2

- [ ] Backup/export nguồn cũ cần tham khảo.
- [ ] Viết data dictionary v2.
- [ ] Chốt taxonomy v1.
- [ ] Vẽ ERD và ownership.
- [ ] Viết baseline migration.
- [ ] Tạo validation/normalization script.
- [ ] Chuẩn hóa 60 sản phẩm hiện có thành raw/normalized input.
- [ ] Bổ sung sản phẩm để đạt độ đa dạng đã chốt.
- [ ] Kiểm tra quyền sử dụng và upload ảnh lên Cloudinary.
- [ ] Seed taxonomy, catalog, variants và inventory.
- [ ] Biên soạn/version hóa chính sách, FAQ, hướng dẫn size/chăm sóc và index knowledge cho RAG.
- [ ] Seed user/transaction demo an toàn.
- [ ] Ghi interaction data theo schema mới.
- [ ] Sinh lại text/image embeddings.
- [ ] Tạo ba bộ evaluation dataset.
- [ ] Chạy quality gate và xuất validation report.
- [ ] Gắn tag dataset version đầu tiên.

Tiêu chí hoàn thành:

- Database mới được dựng từ một baseline migration trên database trắng.
- Toàn bộ dữ liệu ứng dụng được tạo lại bằng seed/import có version.
- Không phụ thuộc trực tiếp vào schema hoặc embedding của database cũ.
- Có thể reset local và tái tạo cùng số lượng/checksum dữ liệu.
- Dữ liệu demo, real và synthetic phân biệt rõ ràng.
- Bộ evaluation độc lập với dữ liệu huấn luyện/indexing ở mức cần thiết cho từng bài toán.

---

## 22. Tài liệu chính thức tham khảo

### Supabase

- PostgreSQL database overview: <https://supabase.com/docs/guides/database/overview>
- `pgvector`: <https://supabase.com/docs/guides/database/extensions/pgvector>
- Database connections: <https://supabase.com/docs/guides/database/connecting-to-postgres>
- Connection pooling: <https://supabase.com/docs/guides/database/connecting-to-postgres/pooling-and-limits>
- Custom schemas: <https://supabase.com/docs/guides/api/using-custom-schemas>
- Regions: <https://supabase.com/docs/guides/platform/regions>

### Cloudinary

- Java SDK: <https://cloudinary.com/documentation/java_integration>
- Upload API: <https://cloudinary.com/documentation/image_upload_api_reference>
- Upload parameters/folders: <https://cloudinary.com/documentation/upload_parameters>
- Upload presets: <https://cloudinary.com/documentation/upload_presets>
- Client-side upload security: <https://cloudinary.com/documentation/client_side_uploading>

