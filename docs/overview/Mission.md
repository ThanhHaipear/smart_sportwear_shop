# Mission — Nhiệm vụ AI của Quyền: Hệ thống khuyến nghị (Recommendation)

> **Cập nhật 16/11/2026, theo code nhánh `main` sau khi tách microservice.**
>
> **Mục đích tài liệu:**
> 1. Danh sách việc cần làm dạng checklist, đánh dấu `[x]` khi xong.
> 2. Kiến thức nền để hiểu và **bảo vệ được** phần AI của mình trước hội đồng.
>
> Nguồn: `Phân chia nhiệm vụ AI.docx`, `plan.md` (8.3, 13.3, 21.9), `recommendation-service/`, `docs/microservice-phase-5-recommendation-service.md`, `contracts/recommendation-*.schema.json`.

---

## PHẦN A — Nhiệm vụ trong một trang

**Theo bảng phân công:**

| Phần việc chính | Nhiệm vụ AI tương ứng |
|---|---|
| Thu thập hành vi; xây **popularity baseline** và **content-based**; loại sản phẩm ngừng bán/hết hàng; ghi lại model và kết quả khuyến nghị; tạo tập đánh giá theo thời gian | Recommendation, **Precision@K, Recall@K, Hit Rate@K, NDCG@K** |

**Bài toán trong một câu:**
> Cho một ngữ cảnh (khách đang xem sản phẩm X, đang có giỏ hàng G, hoặc khách U với lịch sử H), trả về **K sản phẩm** khách có khả năng quan tâm nhất, không có sản phẩm ngừng bán hoặc hết hàng. Sau đó chứng minh bằng số liệu rằng cách làm tốt hơn baseline.

### A.1. Điều gì đã thay đổi so với kế hoạch cũ?

| Kế hoạch cũ (monolith) | Hiện trạng `main` |
|---|---|
| Viết migration `V10__create_1c_recommendation.sql` trong `backend/`, schema `ai_retrieval` | **Không cần nữa.** Schema `recommendation` đã có (`recommendation-service/migrations/V1`, `V2`). Backend và `ai_retrieval` đã bị xóa. File V10 nháp trên máy (chưa commit) giờ đã lỗi thời |
| Module Python mới trong `chatbot-service`, backend Java làm proxy | **Service riêng `recommendation-service` (:8091)**, Gateway định tuyến thẳng tới, có worker RabbitMQ |
| Đọc `public.user_interactions`, `public.products`, `product_variants` để lọc tồn kho | **Không được đọc bảng service khác.** Dữ liệu sản phẩm phải đến qua sự kiện/API và lưu ở `recommendation.catalog_products` |
| Content-based dùng BGE-M3 trong `ai_retrieval.product_text_embeddings` | BGE-M3 đã bỏ. Embedding văn bản giờ là OpenAI 1536 chiều trong `ai_search.text_embeddings`, **thuộc Search service**, Recommendation không có quyền đọc |
| Chưa có thuật toán nào chạy | **Đã chạy** luật kết hợp (association rules) + popularity fallback, có API, có ghi request/item, có API đánh giá P@K và R@K |

### A.2. Những gì đã có sẵn trong `recommendation-service`

| Thành phần | File | Ghi chú |
|---|---|---|
| Khai phá luật kết hợp | `app/domain.py` → `mine_association_rules` | Đếm cặp sản phẩm trong cùng giỏ, tính support, confidence, lift, lọc theo ngưỡng (mặc định 0,01 / 0,10 / 1,0) |
| Xếp hạng luật | `app/domain.py` → `rank_rules` | Mỗi sản phẩm đích giữ luật tốt nhất; xếp theo confidence → lift → support |
| Phục vụ gợi ý | `app/service.py` → `recommend` | Luật trước, thiếu thì bù **popularity** (`purchase_count`); chỉ trả sản phẩm `ACTIVE` và `available_quantity > 0` |
| Huấn luyện lại | `scripts/rebuild_model.py <version>`, `POST /internal/v1/models/rebuild` | Ghi `association_rules` mới và `recommendation_models` |
| Ghi hành vi | `POST /api/v1/recommendations/interactions` | Ghi `user_interactions`, chống trùng theo `eventId` |
| Nhận sự kiện | `app/worker.py`, `app/messaging.py` | Queue `recommendation.events`, retry 30s/5m/1h, DLQ |
| Đánh giá | `POST /internal/v1/evaluations` | Nhận sẵn danh sách `predicted`/`relevant`, tính P@K, R@K, lưu `evaluation_runs` |
| Hiển thị | Storefront `fetchFrequentlyBoughtTogether` | Khối "thường mua cùng" ở trang chi tiết; lỗi thì ẩn khối |
| Test | `tests/` | 11 test pass ở gate Phase 12 |

### A.3. Lỗi và khoảng trống tìm thấy khi đọc code (cần xử lý trước tiên)

> **Cập nhật 16/11:** lỗi #1, #2 đã sửa (Giai đoạn 1); #4 đã sửa (Giai đoạn 2); #3 cần chốt với nhóm; #5 – #8 còn mở.

| # | Vấn đề | Bằng chứng trong code | Hậu quả |
|---|---|---|---|
| 1 | **Giỏ đơn hàng chứa ID variant, không phải ID sản phẩm** | Sales gửi `items = order.items().map(OrderLine::variantId)` (`JdbcSalesRepository.java:31`). Recommendation lưu thẳng vào `order_baskets.product_ids` (`repository.py:102`) | Luật khai phá theo **variant**; khi gợi ý, nguồn là **product id** nên không khớp luật nào. `purchase_count` cũng không tăng vì so `product_id = any(variant ids)` |
| 2 | **`catalog_products` không được cập nhật từ sự kiện** | Catalog chỉ gửi `PRODUCT_REINDEX_REQUESTED` với `productId`, `sourceVersion`, không có trường `product` (`V5__align_catalog_search_events.sql`). Worker chỉ ghi `catalog_products` khi `event.product` có giá trị (`repository.py:98`). V2 backfill cũng không chép bảng này | Không có ứng viên để lọc ACTIVE/còn hàng → cả luật lẫn popularity đều có thể trả rỗng |
| 3 | Hợp đồng sự kiện lệch nhau | `contracts/recommendation-catalog-product-event-v1.schema.json` yêu cầu `PRODUCT_CHANGED`/`INVENTORY_CHANGED` kèm `product` | Cần chốt lại hợp đồng với nhóm |
| 4 | **Storefront chưa gửi hành vi nào** | Không có lời gọi `/recommendations/interactions` trong `frontend/storefront/src` | `user_interactions` không có dữ liệu thật mới; không đo được CTR |
| 5 | Thiếu thông tin tái lập | `record_response` không ghi `latency_ms`, `user_ref`; `recommendation_models` không ghi `config`, `training_cutoff_at`, `training_event_checksum` | Không chứng minh được kết quả cũ chạy lại ra giống |
| 6 | Đánh giá chỉ có P@K, R@K | `precision_recall_at_k` trong `domain.py` | Thiếu Hit Rate@K, NDCG@K theo phân công; chưa có chia train/test theo thời gian |
| 7 | Chưa có content-based | — | Thiếu thuật toán được phân công; cần cách lấy vector mà không đọc schema `ai_search` |
| 8 | Chatbot "sản phẩm liên quan" luôn rỗng | `chatbot-service/app/services/recommendation_service.py` đọc `relatedProducts`, Catalog luôn trả `[]` (`PublicCatalogRepository.java:144`) | Có thể nối sang recommendation-service (phối hợp với Dũng) |

---

## PHẦN B — Checklist công việc

> Mục **(phối hợp)** cần thống nhất với Dũng/Hải trước khi làm, vì sửa sang service của người khác hoặc đổi hợp đồng sự kiện.

### Giai đoạn 0 — Chuẩn bị môi trường mới

- [x] Đọc `README.md`, `RUN_LOCAL.md`, `recommendation-service/README.md`, `docs/microservice-phase-5-recommendation-service.md`. Điểm cần nhớ từ Phase 5: dữ liệu cũ chuyển sang có **0 hành vi, 0 luật, 0 log**; tài liệu yêu cầu chỉ mở gợi ý cho khách sau khi có đánh giá TEST độc lập (launcher local thì luôn bật).
- [x] Có `.env.phase10.local` (đã gitignore). Đã kiểm tra đủ `RECOMMENDATION_DATABASE_URL` (đúng role runtime `recommendation_app`), `RECOMMENDATION_INTERNAL_TOKEN`, `CATALOG_INTERNAL_TOKEN`, RabbitMQ. **Không commit file này.**
- [x] Chạy permission probe và gate đầy đủ `scripts/verify-microservice-phase-5.ps1`: **đạt** (32 test, role ghi được schema `recommendation`, bị từ chối ghi `public`). Lưu ý: gate gọi `python` chung của máy, phải đặt `recommendation-service\.venv\Scripts` lên đầu `PATH` trước khi chạy.
- [x] Chạy `.\scripts\run\start-all-local.cmd`, kiểm tra `http://localhost:8091/health/ready` trả `ready`.
- [x] Tạo venv cho `recommendation-service`, chạy `pytest -q` (ban đầu 11 passed sau khi bổ sung `httpx`).
- [x] Gọi thử `GET http://localhost:8085/api/v1/products/{id}/frequently-bought-together`: trước khi sửa trả **0 sản phẩm** (`modelVersion = popularity-v1`).
- [x] Lưu bản V10 nháp làm tài liệu tham khảo: `docs/overview/archive/V10__create_1c_recommendation.draft.sql`.
- [x] Dọn thư mục `backend/` còn sót (không thuộc git): đã chuyển `backend\.env` thành `.env.legacy-backend.local` theo quy ước của `RUN_LOCAL.md` (bản duy nhất của cấu hình backend cũ, đã gitignore), rồi xóa `backend\` (V10 đã sao lưu, `target\` là file biên dịch của monolith đã gỡ).
- [x] Môi trường local: tạo `.venv` cho 4 service Python và junction `.tools\node-v24.14.1-win-x64` → Node 24 của máy.

### Giai đoạn 1 — Sửa dữ liệu đầu vào (ưu tiên cao nhất)

- [x] **Lỗi #1 – giỏ theo product id.** Đã chọn **cách B** (không phải sửa Sales): `app/enrichment.py` đổi từng ID trong `items` sang ID sản phẩm qua `GET /internal/v1/variants/{id}/sale-snapshot` của Catalog. ID vốn đã là sản phẩm thì giữ nguyên, nên vẫn đúng nếu sau này Sales gửi product ID (cách A).
- [x] **Lỗi #2 – nạp `catalog_products`.** Worker nhận `product.reindex.requested` thì gọi `index-document` (trạng thái, variant, tồn, ảnh) và API chi tiết công khai (lấy `slug`), dựng snapshot rồi UPSERT có so `source_version`. Sản phẩm bị xóa thành bản ghi `DELETED`.
- [x] Sửa thêm: cùng một đơn gửi lại hai lần (khác `eventId`) không còn cộng `purchase_count` hai lần; snapshot có thêm `thumbnail` (storefront đọc trường này).
- [x] Viết script backfill `recommendation-service/scripts/backfill-read-models.ps1` (chạy thử mặc định, `-Execute` mới ghi). Chạy thử 16/11: 36 sản phẩm, 36 gợi ý được, **0 giỏ đơn hàng**.
- [x] **Chạy backfill thật** (`-Execute`, 16/11): nạp 36 sản phẩm vào `catalog_products`. Chạy thử lại cho kết quả y hệt (chạy lặp không sinh trùng). API "thường mua cùng" giờ trả **8 gợi ý** (`POPULARITY`, đủ slug, ảnh, tồn kho) thay vì 0. Vì chưa có đơn hàng, mọi `purchase_count` = 0 nên thứ tự hiện chỉ theo `product_id`, giống nhau cho mọi sản phẩm nguồn.
- [ ] **(phối hợp)** Chốt lại hợp đồng sự kiện (#3): ghi rõ `items` của `ORDER_CREATED` là variant ID, và Catalog chỉ gửi sự kiện "mỏng" (không kèm `product`).
- [x] Test: sự kiện trùng không ghi hai lần; `source_version` cũ không ghi đè; sản phẩm hết hàng/ngừng bán không được gợi ý; giỏ từ variant khai phá ra luật theo sản phẩm (`tests/test_enrichment.py`, 15 test).
- [x] Worker tự thử kết nối lại RabbitMQ khi khởi động (trước đây thoát luôn nếu RabbitMQ chưa sẵn sàng).

### Giai đoạn 2 — Thu thập hành vi thật

- [x] Storefront gửi `POST /api/v1/recommendations/interactions` (`frontend/storefront/src/modules/recommendation/tracking.ts`) tại các điểm:
  - [x] `VIEW` khi mở trang chi tiết, tối đa 1 lần/sản phẩm/10 phút mỗi trình duyệt (`ProductViewTracker`).
  - [x] `ADD_TO_CART` khi thêm giỏ thành công (kèm `variantId`, `quantity`); `WISHLIST` khi thêm yêu thích.
  - [x] `RECOMMENDATION_IMPRESSION` khi khối "Thường mua cùng nhau" hiện ≥ 50% trên màn hình, `RECOMMENDATION_CLICK` khi bấm vào thẻ; cả hai kèm `requestId`, vị trí, thuật toán (`RecommendationSection`).
- [x] **Quyết định: `PURCHASE` tính lúc đặt đơn** (sự kiện `order.created`, lưu ở `order_baskets`), không phải lúc giao. Lý do: Sales **không phát sự kiện "đã giao"**; `order.cancelled` chưa được nối tới queue `recommendation.events`, và payload khi khách tự hủy thiếu `eventId`/`eventType`/`traceId` nên worker không nhận được. Hệ quả cần nêu khi bảo vệ: đơn bị hủy vẫn được tính là đã mua. Muốn trừ đơn hủy cần **(phối hợp)** Sales phát `order.cancelled` đúng định dạng envelope và bind vào `recommendation.events`. Dữ liệu synthetic (Giai đoạn 4) không bị ảnh hưởng.
- [x] Khách vãng lai dùng `anonymousId` (UUID lưu ở `localStorage`). Đã đăng nhập thì **service lấy `userRef` từ header `X-User-Id` do Gateway gắn sau khi kiểm JWT**, bỏ qua giá trị client tự gửi. Không có dữ liệu cá nhân trong `metadata`.
- [x] `dataSource = REAL` mặc định; đặt `NEXT_PUBLIC_INTERACTION_DATA_SOURCE=DEMO` khi demo. API từ chối `SYNTHETIC` và `PURCHASE` từ trình duyệt.
- [x] Gửi lỗi thì bỏ qua, không làm hỏng trang (`keepalive`, `catch`). Tắt toàn bộ bằng `NEXT_PUBLIC_INTERACTION_TRACKING=false`.
- [x] Server dùng giờ nhận (`occurredAt`) thay vì giờ máy khách, để chia dữ liệu theo thời gian không bị lệch đồng hồ.
- [x] Rate limit riêng cho endpoint ghi hành vi (`InteractionRateLimiter`): **60 sự kiện/phút cho mỗi người** (`userRef` hoặc `anonymousId`) và **300/phút cho mỗi IP** (lấy từ `X-Forwarded-For` do Gateway ghi đè), vượt thì trả 429. Sự kiện bị từ chối (422) không bị tính vào hạn mức. Cấu hình qua `INTERACTION_RATE_LIMIT_PER_IDENTITY`, `INTERACTION_RATE_LIMIT_PER_IP`. Bộ đếm nằm trong bộ nhớ của tiến trình; nếu chạy nhiều instance cần chuyển sang Redis.
- [x] Gửi theo lô: `POST /api/v1/recommendations/interactions/batch` (tối đa 10 sự kiện, cùng một `anonymousId`, sai một sự kiện thì từ chối cả lô, ghi trong một transaction). 4 impression của khối gợi ý giờ đi **1 request** thay vì 4. Rate limit vẫn **đếm theo sự kiện** (lô 4 tốn 4 lượt) để không mở kẽ hở spam. Đã nối route ở Gateway và cập nhật `contracts/rest/recommendation-service-v1.yaml`. Thử trình duyệt (`DEMO`): xem trang + cuộn tới gợi ý còn 2 request (trước là 5), DB vẫn đủ 4 dòng.
- [x] Kiểm tra end-to-end bằng trình duyệt thật (Playwright, 16/11, `dataSource=DEMO`): VIEW → 4 IMPRESSION khi cuộn tới khối gợi ý → CLICK → VIEW trang mới → ADD_TO_CART. Cả 8 sự kiện vào `user_interactions` đúng loại, đúng `requestId`/vị trí/thuật toán, `variantId` có ở ADD_TO_CART, không có `userRef` (khách vãng lai). Mở lại trang trong 10 phút không sinh thêm VIEW. Console không lỗi.

### Giai đoạn 3 — Thiết kế và protocol đánh giá **trước khi code thuật toán mới**

> `plan.md` quy tắc 14: *"protocol đánh giá phải có trước implementation; không tạo ground truth hồi cứu chỉ để khớp code"*.

- [x] Tạo `docs/recommendation-design.md` (v1.0, chốt 17/11) gồm:
  - [x] Ngữ cảnh: `FREQUENTLY_BOUGHT_TOGETHER` (trang chi tiết), `CART_RECOMMENDATION` (giỏ hàng), `HOME` (nếu làm).
  - [x] Thuật toán: popularity (baseline), association rules (đã có), content-based, hybrid (tùy chọn).
  - [x] Trọng số sự kiện, ví dụ `VIEW=1`, `RECOMMENDATION_CLICK=1`, `WISHLIST=2`, `ADD_TO_CART=3`, `PURCHASE=5`.
  - [x] Bộ lọc nghiệp vụ: ACTIVE, còn hàng, bỏ sản phẩm nguồn, (tùy chọn) bỏ sản phẩm đã mua.
  - [x] Protocol: mốc chia thời gian, định nghĩa sản phẩm liên quan, tập ứng viên, K ∈ {5, 10}, metric, cách xử lý user lạnh.
  - [x] Giới hạn: dữ liệu synthetic, catalog nhỏ, đánh giá offline.
- [x] ~~Cho Dũng/Hải review.~~ Bỏ vòng review (17/11); các điểm phối hợp được tự quyết ở mục 11 của tài liệu thiết kế.

### Giai đoạn 4 — Dữ liệu hành vi synthetic

- [x] Script `recommendation-service/scripts/generate_synthetic_interactions.py` (17/11):
  - [x] Seed cố định `20261117`; 500 user giả có persona (môn, giới tính, ngân sách, 40% trung thành thương hiệu); nhiễu 25% lượt chọn (thực tế 21,5%).
  - [x] Phễu `VIEW → ADD_TO_CART → PURCHASE`, 8 tuần từ `2026-09-01`, có timestamp.
  - [x] Sinh cả **giỏ đơn hàng**: 623 đơn, 324 đơn có ≥ 2 sản phẩm (cơ chế mua kèm theo bảng `complements`).
  - [x] Chỉ dùng sản phẩm có trong catalog thật: `scripts/export_catalog_snapshot.py` xuất 36 sản phẩm qua API Catalog thành `evidence/recommendation/catalog-snapshot-20261117.json`. **Catalog trả `productType`, `gender`, `sportCodes`, `colorFamily` rỗng**, nên các trường này được bổ sung từ `catalog/*/manifest.json` theo slug, có ghi nguồn từng sản phẩm.
- [x] Manifest + SHA-256: `evidence/recommendation/synthetic-v1-manifest.json`, `dataSource = SYNTHETIC`. Dữ liệu ở `datasets/recommendation/synthetic-v1/` (`.gitattributes` chặn đổi xuống dòng để checksum không lệch).
- [x] **Không** ghi dữ liệu synthetic vào Supabase; đánh giá chạy trên file.
- [x] Chốt tham số bộ sinh trước khi xem kết quả mô hình (`config.json`, commit `ce11b991`). Phương án S1: 12 sản phẩm ra mắt ngày 49 (6 áo World Cup + 6 sản phẩm pilot unisex mỗi loại một món) để tuần TEST có 39 ca nguồn cold (S0 chỉ 6 áo thì có 8 ca, dưới ngưỡng 30).
- [ ] **(phối hợp, ngoài phạm vi)** Catalog lưu lại `productType`, `gender`, `sportCodes`, `colorFamily` vào DB. Cần cho content-based khi chạy thật và cho bộ lọc của Search.

### Giai đoạn 5 — Chia dữ liệu theo thời gian

- [x] Train = tuần 1–6, DEV = tuần 7, TEST = tuần 8 (`app/offline_split.py`). TEST huấn luyện lại trên tuần 1–7. Mốc train là **nửa mở** (`occurredAt < trainUntil`); mỗi đơn thuộc cửa sổ của chính thời điểm đặt.
- [x] Đóng băng tập ứng viên kèm checksum: DEV 24 sản phẩm (12 sản phẩm mới chưa ra mắt), TEST 36 sản phẩm.
- [x] Sản phẩm liên quan theo `docs/recommendation-design.md` mục 7.4: FBT leave-one-in trên giỏ ≥ 2 sản phẩm; HOME `ADD_TO_CART=1`/`PURCHASE=2`, bỏ sản phẩm user đã mua trong train.
- [x] Bỏ user không có sản phẩm liên quan (DEV 89, TEST 119 user chỉ xem); báo cáo riêng warm/cold.
- [x] Lưu mốc thời gian, checksum, chính sách loại trừ: `scripts/build_evaluation_split.py` → `datasets/recommendation/synthetic-v1/splits/{dev,test}.json` + `evidence/recommendation/synthetic-v1-splits-manifest.json`.

| Split | Ứng viên | FBT: tất cả / warm / cold | HOME: tất cả / warm / cold |
|---|---|---|---|
| DEV (tuần 7) | 24 | 125 / 125 / **0** | 102 / 88 / 14 |
| TEST (tuần 8) | 36 | 145 / 106 / 39 | 156 / 117 / 39 |

> DEV không có ca FBT cold vì sản phẩm mới ra mắt ở tuần 8. Cấu hình content-based được chọn trên ca warm của DEV (`docs/recommendation-design.md` 7.7).

### Giai đoạn 6 — Baseline và luật kết hợp trên cùng protocol

- [x] Random baseline (seed `20261117`, mỗi ca một seed con theo `caseId`).
- [x] Popularity tính **chỉ từ dữ liệu train** (`popularity_scores` trong `app/domain.py`): trọng số sự kiện mục 4 của thiết kế, suy giảm theo half-life ∈ {không, 14, 7} ngày, bỏ sự kiện ≥ mốc cắt, VIEW ngay sau CLICK chỉ tính một lần. Không dùng `purchase_count` của DB.
- [x] Association rules: khai phá từ giỏ train, lưới 3 × 3 support/confidence, chọn trên DEV. Đánh giá **thuần** (thiếu thì trả ít hơn K) và bản **bù popularity** riêng.
- [x] Unit test: không dùng sự kiện sau mốc cutoff; chỉ trả sản phẩm trong tập ứng viên; không trả sản phẩm nguồn; metric khớp ví dụ tính tay C.9 (`tests/test_offline_evaluation.py`, 11 test).
- [x] Chạy DEV (`scripts/evaluate_offline.py --split dev --stage phase6`), kết quả `evidence/recommendation/synthetic-v1-dev-phase6-20261117.json`. TEST bị chặn nếu không có `--final`.

**Kết quả DEV (FBT, 125 ca, tất cả warm):**

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | Coverage@5 |
|---|---|---|---|---|---|
| Random | 0,094 | 0,237 | 0,392 | 0,161 (0,12–0,21) | 1,00 |
| Popularity, half-life 14 ngày (**chọn**) | 0,179 | 0,415 | 0,608 | 0,329 (0,24–0,42) | 0,25 |
| Luật kết hợp s = 0,005, c = 0,05 (**chọn**) | 0,203 | 0,568 | 0,728 | 0,453 (0,37–0,54) | 1,00 |
| Luật kết hợp + bù popularity | 0,226 | 0,617 | 0,792 | 0,479 (0,40–0,56) | 1,00 |

- Bộ trọng số thay thế (`VIEW=1, ADD_TO_CART=2, PURCHASE=3`) cho **cùng thứ hạng** với bộ mặc định: popularity không nhạy với trọng số trên dữ liệu này.
- Ngưỡng tốt nhất của luật kết hợp nằm ở **biên thấp nhất** của lưới (support 0,005 ≈ 3 giỏ). Theo protocol không thêm cấu hình sau khi xem DEV; ghi nhận như một giới hạn.
- Khoảng tin cậy còn chồng nhau; kết luận "hơn/kém" để Giai đoạn 9 kiểm bằng paired bootstrap trên TEST.

### Giai đoạn 7 — Content-based

- [x] Chọn **cách B** (đã chốt ở thiết kế mục 11): đặc trưng thuộc tính từ snapshot, Jaccard có trọng số theo nhóm (`app/content.py`). Snapshot giữ thêm `gender`, `sportCodes`, `colorFamilies`, nhưng Catalog đang trả rỗng nên khi chạy thật hiện chỉ so được danh mục, thương hiệu, khoảng giá.
- [x] Item–item cho `FREQUENTLY_BOUGHT_TOGETHER`: hai biến thể `SIMILAR` / `COMPLEMENT`, chọn **COMPLEMENT** trên DEV. User-profile cho `HOME`.
- [x] Cold-start: hybrid theo tầng luật → content → popularity (`RecommendationService`, `RECOMMENDATION_CONTENT_MODE=COMPLEMENT`); HOME hồ sơ → popularity. Content chỉ trả sản phẩm có độ tương đồng > 0.
- [x] (Tùy chọn) Hybrid trộn điểm α: **không làm**, vì hybrid theo tầng đã ≥ thuật toán đơn lẻ tốt nhất trên DEV (điều kiện ở thiết kế mục 5.5).

**Kết quả DEV** (`evidence/recommendation/synthetic-v1-dev-phase7-20261117.json`):

| FBT (125 ca warm) | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) |
|---|---|---|---|---|
| Popularity | 0,179 | 0,415 | 0,608 | 0,329 (0,24–0,42) |
| Content similar | 0,136 | 0,407 | 0,584 | 0,266 (0,21–0,34) |
| Content complement (**chọn**) | 0,187 | 0,510 | 0,704 | 0,365 (0,29–0,45) |
| Luật kết hợp thuần | 0,203 | 0,568 | 0,728 | 0,453 (0,37–0,54) |
| Luật → popularity | 0,226 | 0,617 | 0,792 | 0,479 (0,40–0,56) |
| Hybrid luật → content → popularity | 0,216 | 0,606 | 0,768 | 0,471 (0,39–0,55) |

| HOME (102 user) | NDCG@5 tất cả | warm (88) | cold (14) | Coverage@5 |
|---|---|---|---|---|
| Popularity | 0,290 | 0,303 | 0,211 | 0,375 |
| Hồ sơ nội dung | 0,291 | 0,337 | 0 | 1,00 |
| Hybrid hồ sơ → popularity | 0,320 | 0,337 | 0,211 | 1,00 |

- Content complement vượt popularity, similar thì không: "thường mua cùng" cần đồ **khác loại** cùng môn.
- Trên DEV (toàn ca warm), content lấp chỗ trống kém popularity một chút (0,471 so với 0,479, khoảng tin cậy chồng nhau). Lợi ích của content dự kiến nằm ở ca cold, chỉ đo được trên TEST.

### Giai đoạn 8 — Ghi lại model và kết quả

- [x] Mỗi lần rebuild luật kết hợp = một dòng `recommendation_models` có `config` (ngưỡng, số giỏ, số luật), `training_cutoff_at`, `training_event_checksum` (SHA-256 của giỏ train ở dạng chuẩn hóa). Chỉ khai phá giỏ **trước** mốc cắt; model cũ chuyển `ARCHIVED` trong cùng transaction. Ngưỡng mặc định theo lựa chọn DEV: support 0,005, confidence 0,05.
- [x] `record_response` ghi thêm `latency_ms` (thời gian xếp hạng) và `user_ref` (lấy từ header `X-User-Id` do Gateway gắn, không trả lại trong response).
- [x] `reason_code` phân biệt rõ `ASSOCIATION_RULE`, `SIMILAR_CONTENT`, `POPULARITY` (làm ở Giai đoạn 7).
- [x] Báo cáo offline ghi cùng checksum giỏ train (`training.basketChecksum`) và checksum sự kiện train.
- [x] Kiểm tra SQL trên schema thật trong một transaction rồi rollback (`scripts/verify_model_tracking.py`): đủ cột, số dòng không đổi sau khi chạy.

> Đo thử trên Supabase: một lần gợi ý mất khoảng **290 ms** vì service gọi DB nhiều lần (luật, sản phẩm, ứng viên, snapshot nguồn, popularity). Chưa tối ưu; ghi nhận để báo cáo.

### Giai đoạn 9 — Đánh giá offline

- [x] Script `recommendation-service/scripts/evaluate_offline.py`, stage `final`: chạy các cấu hình đã chọn trên DEV (tên ngắn ≤ 50 ký tự), chỉ với dữ liệu train, so với đáp án đóng băng.
- [x] Hit Rate@K, NDCG@K, Coverage@K, bootstrap CI: `app/metrics.py` (làm sớm ở Giai đoạn 6). Thêm **paired bootstrap** để so sánh từng cặp.
- [x] Migration `migrations/V3__extend_evaluation_runs.sql`: thêm `task`, `segment`, `case_count`, `hit_rate_at_k`, `ndcg_at_k`, `coverage_at_k`, `report_sha256` (cho phép null). API `/internal/v1/evaluations` trả thêm HR, NDCG. `scripts/record_evaluation_runs.py` chép báo cáo vào bảng (mặc định chạy thử). **Chưa áp dụng V3 lên Supabase** (cần role quản trị).
- [x] Đăng ký trước cấu hình cuối, 9 so sánh C1–C9 và quy tắc kết luận: `docs/recommendation-design.md` mục 7.9, commit trước khi chạy TEST.
- [x] Chạy thử stage `final` trên DEV: số liệu khớp Giai đoạn 7; script chặn TEST khi thiếu `--final`, khi không phải stage `final`, và khi đã chạy TEST mà không có `--rerun-reason`.
- [x] Chạy TEST **một lần** (17/11, từ commit `b4f44506`): `evidence/recommendation/synthetic-v1-test-final-20261117T082418Z.json`. Kết quả ở dưới; chưa ghi vào `evaluation_runs` vì V3 chưa áp dụng (quyết định 17/11: chỉ lưu file).

**Kết quả TEST, FBT** (145 ca: 106 warm, 39 cold; 36 ứng viên):

| Cấu hình | HR@5 | NDCG@5 tất cả (CI 95%) | NDCG@5 warm | NDCG@5 cold | Coverage@5 |
|---|---|---|---|---|---|
| Random | 0,338 | 0,130 (0,09–0,16) | 0,133 | 0,119 | 1,00 |
| Popularity | 0,379 | 0,230 (0,15–0,32) | 0,273 | 0,114 | 0,17 |
| Luật kết hợp thuần | 0,428 | 0,270 (0,19–0,36) | 0,370 | 0 | 0,64 |
| Luật → popularity | 0,497 | 0,309 (0,23–0,40) | 0,381 | 0,114 | 0,64 |
| Content complement | 0,572 | 0,272 (0,21–0,33) | 0,278 | 0,257 | 1,00 |
| **Hybrid luật → content → popularity** | **0,621** | **0,350** (0,28–0,43) | **0,384** | **0,257** | 0,97 |

**Kết quả TEST, HOME** (156 user: 117 warm, 39 cold):

| Cấu hình | HR@5 | NDCG@5 tất cả (CI 95%) | warm | cold |
|---|---|---|---|---|
| Random | 0,244 | 0,084 (0,06–0,11) | 0,097 | 0,045 |
| Popularity | 0,301 | 0,157 (0,11–0,20) | 0,168 | 0,122 |
| Hồ sơ nội dung | 0,365 | 0,176 (0,13–0,22) | 0,235 | 0 |
| **Hybrid hồ sơ → popularity** | **0,429** | **0,206** (0,17–0,25) | **0,235** | 0,122 |

**So sánh đăng ký trước** (NDCG@5, paired bootstrap):

| Mã | Câu hỏi | So sánh | Hiệu (CI 95%) | Kết luận |
|---|---|---|---|---|
| C1 | RQ1 | Luật thuần − popularity, FBT warm | +0,097 (0,013–0,182) | Luật tốt hơn |
| C2 | RQ1 | Luật → popularity − popularity, FBT tất cả | +0,079 (0,021–0,138) | Tốt hơn |
| C3 | RQ2 | Content complement − popularity, FBT cold | +0,143 (−0,003–0,267) | **Chưa có ý nghĩa** |
| C4 | RQ2 | Hybrid − luật → popularity, FBT cold | +0,143 (−0,003–0,267) | **Chưa có ý nghĩa** |
| C5 | RQ3 | Hybrid − luật thuần, FBT tất cả | +0,079 (0,051–0,112) | Hybrid tốt hơn |
| C6 | RQ3 | Hybrid − content complement, FBT tất cả | +0,077 (0,015–0,146) | Hybrid tốt hơn |
| C7 | RQ3 | Hybrid − popularity, FBT tất cả | +0,119 (0,041–0,196) | Hybrid tốt hơn |
| C8 | RQ4 | Hồ sơ nội dung − popularity, HOME warm | +0,067 (0,007–0,130) | Hồ sơ tốt hơn |
| C9 | RQ4 | Hybrid hồ sơ − popularity, HOME tất cả | +0,050 (0,000–0,097) | Tốt hơn (sát biên) |

- **RQ1, RQ3, RQ4 được ủng hộ.** RQ2 có chiều thuận (content trên ca cold 0,257 so với 0,114), nhưng khoảng tin cậy chạm 0 (39 ca từ 30 đơn), nên theo quy tắc đăng ký trước thì **chưa kết luận được**.
- 9 so sánh không hiệu chỉnh đa so sánh; C9 có cận dưới 0,0002, rất sát ngưỡng.
- Số liệu TEST thấp hơn DEV vì có 36 ứng viên (DEV 24) và 12 sản phẩm mới.
- [x] Unit test hàm metric bằng ví dụ tính tay ở mục C.9.

### Giai đoạn 10 — Giao diện, chatbot và báo cáo

- [x] Khối **"Có thể bạn cũng cần"** ở trang giỏ hàng (`CartRecommendations`, `POST /api/v1/recommendations/cart`, `source = CART_RECOMMENDATION`), ghi impression/click như khối trang chi tiết. Typecheck và lint đạt; **đã thử bằng trình duyệt** (17/11, `DEMO`, xem nhật ký).
- [x] Tool `recommend_products` chế độ "related" gọi recommendation-service (`:8091`), lý do theo tầng; lỗi hoặc rỗng thì quay về `relatedProducts` của Catalog (`chatbot-service`, 5 test mới, 208 passed). Không qua vòng phối hợp với Dũng (quyết định 17/11).
- [x] Bảng so sánh tự sinh từ báo cáo TEST: `docs/recommendation-results.md` (`scripts/render_results_table.py`), đủ P/R/HR/NDCG @5, @10, Coverage, tách warm/cold, kèm C1–C9.
- [x] Sơ đồ luồng: `WorkFlow.md` mục 11 vẽ lại (rebuild có mốc cắt/checksum, 3 tầng, giỏ hàng, chatbot, đánh giá offline); sơ đồ 10 cập nhật tool `recommend_products`.
- [x] Kịch bản demo và câu SQL kiểm tra: `docs/recommendation-demo.md`.
- [ ] Luyện các câu hỏi ở PHẦN D (đã bổ sung câu hỏi có số liệu TEST).
- [x] Chạy demo bằng trình duyệt (17/11, `DEMO`): luồng VIEW → IMPRESSION → CLICK → VIEW → ADD_TO_CART → impression ở giỏ hàng ghi đúng; chatbot chế độ related gọi được recommendation-service. Phát hiện và sửa: click mang `requestId` khác impression do layout tự `router.refresh()` (xem nhật ký).
- [ ] **Chất lượng gợi ý trên website thật kém** khi Catalog thiếu thuộc tính: giày chạy bộ được gợi ý kèm áo đấu bóng đá (chỉ còn so được thương hiệu + khoảng giá). Cần Catalog lưu `productType`, `gender`, `sportCodes`, `colorFamily`, hoặc tắt tầng content khi demo.

---

## PHẦN C — Kiến thức cần nắm

### C.1. Hệ thống khuyến nghị là gì?

Hệ thống khuyến nghị dự đoán **mức độ quan tâm** của người dùng với các sản phẩm, rồi trả về **top-K** sản phẩm có điểm cao nhất. Đầu vào có thể là ngữ cảnh hiện tại (sản phẩm đang xem, giỏ hàng), lịch sử người dùng (xem, thêm giỏ, mua) và thông tin sản phẩm. Đầu ra là danh sách có thứ hạng, nên đánh giá bằng **ranking metrics**.

### C.2. Phản hồi ngầm và trọng số sự kiện

- Dự án **không có** chấm sao trực tiếp cho gợi ý; dùng **phản hồi ngầm**: xem, thêm giỏ, mua, lưu trong `recommendation.user_interactions` và `order_baskets`.
- Mua chắc hơn thêm giỏ, thêm giỏ chắc hơn xem → gán trọng số, ví dụ `VIEW=1, ADD_TO_CART=3, PURCHASE=5`.

> ⚠️ Không tương tác **không có nghĩa** là không thích; có thể khách chưa nhìn thấy.

### C.3. Popularity baseline

```
score(i) = Σ  w(loại sự kiện e) · exp(−λ · (T − t_e))
          e ∈ sự kiện train của sản phẩm i
```

`T` là mốc cuối train, `λ = ln 2 / half_life`. Bỏ phần exp thì là đếm thuần. Trong service hiện tại, popularity = `purchase_count` (số đơn chứa sản phẩm).

| Ưu điểm | Nhược điểm |
|---|---|
| Đơn giản, không cần lịch sử user, giải quyết cold-start user | Không cá nhân hóa; popularity bias |

Popularity thường **rất khó vượt**. Nếu thuật toán khác không vượt được, phải báo cáo trung thực.

### C.4. Luật kết hợp (association rules) — thuật toán đang chạy

Ý tưởng: *"Ai mua A thường mua B"*. Với N giỏ đơn hàng:

```
support(A→B)    = số giỏ chứa cả A và B / N
confidence(A→B) = số giỏ chứa cả A và B / số giỏ chứa A        = P(B | A)
lift(A→B)       = confidence(A→B) / (số giỏ chứa B / N)        = P(B | A) / P(B)
```

- `lift > 1`: A và B đi cùng nhau nhiều hơn mức ngẫu nhiên. `lift = 1`: độc lập.
- Service giữ luật có `support ≥ 0,01`, `confidence ≥ 0,10`, `lift ≥ 1,0`. Điểm hiển thị = `confidence × lift`.
- Code đếm **mọi cặp** trong mỗi giỏ (không dùng Apriori/FP-Growth) vì chỉ cần luật 1 → 1 và catalog nhỏ.

**Ví dụ tính tay:** 10 đơn; giày X có trong 4 đơn, tất Y trong 5 đơn, cả hai trong 3 đơn.
- support = 3/10 = 0,30; confidence(X→Y) = 3/4 = 0,75; lift = 0,75 / (5/10) = **1,5**.

| Ưu điểm | Nhược điểm |
|---|---|
| Dễ giải thích ("thường mua cùng"), học được quan hệ bổ trợ (giày + tất) | Cần nhiều đơn nhiều sản phẩm; sản phẩm mới không có luật (cold-start item) |

### C.5. Content-based

Gợi ý sản phẩm **giống về nội dung** với sản phẩm khách thích.
- Biểu diễn bằng embedding: hệ thống hiện dùng OpenAI `text-embedding-3-small` (1536 chiều) ở Search service.
- Độ tương đồng cosine: `cos(a, b) = (a · b) / (‖a‖ · ‖b‖)`. Trong pgvector, `a <=> b` là **khoảng cách** cosine = 1 − cos.
- Item–item: `score(j) = cos(v_nguồn, v_j)`. User-profile: `p_u = normalize(Σ w_e · v_{i_e})`, `score = cos(p_u, v_j)`.
- Không có embedding thì có thể dùng **Jaccard** trên tập thuộc tính: `J(A, B) = |A ∩ B| / |A ∪ B|`.

| Ưu điểm | Nhược điểm |
|---|---|
| Xử lý cold-start item, giải thích được, hợp với catalog nhỏ | Chỉ gợi ý đồ giống nhau, không học được "mua kèm" |
    
### C.6. Các phương pháp khác (để trả lời "tại sao không dùng?")

- **Collaborative filtering** (Matrix Factorization, ALS): cần nhiều dữ liệu hành vi; catalog nhỏ, dữ liệu thưa thì dễ overfit. `plan.md` 8.3: *"Không triển khai collaborative filtering phức tạp khi dữ liệu hành vi chưa đủ."*
- **Hybrid:** kết hợp nhiều nguồn điểm. Service hiện tại đã là một dạng hybrid đơn giản: luật kết hợp trước, popularity bù sau.

### C.7. Lọc theo nghiệp vụ

Mọi thuật toán chạy qua bước lọc **sau khi tính điểm**:
1. `status = 'ACTIVE'`.
2. `available_quantity > 0` (tổng `stock − reserved` của các SKU).
3. Bỏ sản phẩm nguồn (đã làm trong `rank_rules` và `popular(excluded)`).
4. Lấy dư ứng viên rồi mới lọc để còn đủ K.

**Kiến trúc microservice ảnh hưởng thế nào?** Recommendation không đọc được bảng của Catalog, nên phải giữ **bản sao** `catalog_products` cập nhật qua sự kiện. Bản sao có thể trễ vài giây (**eventual consistency**), nhưng Sales vẫn kiểm tra tồn kho thật khi khách đặt hàng, nên không bán vượt tồn.

### C.8. Đánh giá offline: chia theo thời gian, DEV/TEST

- Chia ngẫu nhiên làm sự kiện tương lai lọt vào train (**data leakage**), metric cao giả tạo. Phải chia theo thời gian.

```
|------ TRAIN (tuần 1-6) ------|-- DEV (tuần 7) --|-- TEST (tuần 8) --|
 mô hình chỉ học phần này       chọn cấu hình        báo cáo 1 lần
```

- **DEV** để thử cấu hình; **TEST** chạy một lần với cấu hình đã chốt.
- **Tập ứng viên** cố định cho mọi thuật toán; **sản phẩm liên quan** là sản phẩm user thêm giỏ hoặc mua trong cửa sổ test.

### C.9. Các chỉ số đánh giá

Ký hiệu: `L_K` là K gợi ý, `R` là tập sản phẩm liên quan, `rel_i = 1` nếu sản phẩm ở vị trí i thuộc R.

| Chỉ số | Công thức | Ý nghĩa |
|---|---|---|
| **Precision@K** | `|L_K ∩ R| / K` | Trong K gợi ý, bao nhiêu % đúng |
| **Recall@K** | `|L_K ∩ R| / |R|` | Trong các sản phẩm thích, tìm được bao nhiêu % |
| **Hit Rate@K** | `1 nếu |L_K ∩ R| ≥ 1, ngược lại 0` | Có ít nhất 1 gợi ý đúng không |
| **NDCG@K** | `DCG@K / IDCG@K` | Có tính thứ hạng |

```
DCG@K  = Σ_{i=1..K} rel_i / log2(i + 1)
IDCG@K = DCG của danh sách lý tưởng
```

> Lưu ý nguồn: BCDK1 trích Järvelin & Kekäläinen (2002), bài gốc định nghĩa DCG hơi khác (vị trí 1 không chia log). Khi viết script phải chọn một định nghĩa và ghi nguồn tương ứng.

**Ví dụ tính tay:** K = 5, `L = [A, B, C, D, E]`, `R = {B, E, F}`.
- Precision@5 = 2/5 = **0,40**; Recall@5 = 2/3 = **0,667**; Hit Rate@5 = **1**.
- DCG@5 = 1/log2(3) + 1/log2(6) = 0,631 + 0,387 = 1,018.
- IDCG@5 = 1 + 0,631 + 0,5 = 2,131 → NDCG@5 = **0,478**.
- Nếu B, E ở vị trí 1, 2 thì NDCG = 1,631 / 2,131 = **0,765**, Precision không đổi.

**Đọc số liệu:** Precision và Recall đánh đổi nhau; user chỉ có 1 sản phẩm liên quan thì Precision@5 tối đa 0,2; luôn so với **random baseline** (≈ |R|/N). Thêm **Coverage** = số sản phẩm khác nhau từng được gợi ý / tổng sản phẩm.

### C.10. Tái lập kết quả

Mỗi lần chạy lưu: checksum dữ liệu, `recommendation_models` (algorithm, version, config, cutoff, checksum), git commit, seed.

### C.11. Giới hạn cần nêu thẳng thắn

1. Dữ liệu hành vi phần lớn là synthetic; bộ sinh dựa trên thuộc tính nên content-based có lợi thế sẵn.
2. Catalog nhỏ → metric dao động lớn.
3. Đánh giá offline ≠ online; muốn chứng minh tăng doanh số cần A/B test.
4. Bản sao catalog trong Recommendation có độ trễ (eventual consistency).

---

## PHẦN D — Câu hỏi bảo vệ thường gặp

| Câu hỏi | Gợi ý trả lời |
|---|---|
| Vì sao tách recommendation thành service riêng? | Triển khai, mở rộng và tắt độc lập; lỗi AI không làm sập bán hàng (tắt `RECOMMENDATION_ENABLED` thì storefront chỉ ẩn khối gợi ý). Có schema và role DB riêng. |
| Service lấy dữ liệu sản phẩm và đơn hàng thế nào khi không được đọc DB của service khác? | Nhận sự kiện qua RabbitMQ (outbox của Catalog và Sales), lưu bản đọc `catalog_products`, `order_baskets`. Chống trùng bằng `processed_events`, bỏ bản cũ bằng `source_version`. |
| Làm sao không gợi ý sản phẩm hết hàng? | Lọc `ACTIVE` và `available_quantity > 0` trên bản đọc; Sales vẫn kiểm tra tồn thật khi đặt hàng. |
| Vì sao dùng luật kết hợp và popularity mà không dùng collaborative filtering? | Dữ liệu ít, thưa, catalog nhỏ; CF cần nhiều tương tác và không xử lý cold-start. Luật kết hợp giải thích được và phù hợp ngữ cảnh "thường mua cùng"; popularity là baseline và fallback. |
| Support, confidence, lift khác nhau thế nào? | Dùng ví dụ ở C.4. |
| Vì sao chia train/test theo thời gian? | Tránh data leakage; mô phỏng đúng việc chỉ biết quá khứ. |
| DEV và TEST khác nhau? | DEV chọn cấu hình; TEST chạy một lần để báo cáo. |
| Vì sao cần NDCG khi đã có Precision? | Precision không quan tâm thứ tự; NDCG thưởng cho gợi ý đúng ở vị trí cao. |
| Precision chỉ 0,2 có tệ không? | So với random baseline và popularity; nếu user chỉ có 1–2 sản phẩm liên quan thì P@5 tối đa 0,2–0,4. |
| Cold-start xử lý thế nào? | User mới: popularity. Sản phẩm mới: content-based. |
| Dữ liệu synthetic có tin được không? | Seed cố định, tham số chốt trước, có nhiễu; kết quả chứng minh pipeline và phương pháp đánh giá đúng, chưa chứng minh hiệu quả kinh doanh. |
| Làm sao tái lập kết quả? | Lưu model version, config, cutoff, checksum dữ liệu, seed, git commit (C.10). |
| **Kết quả chính là gì?** | TEST, "thường mua cùng": hybrid NDCG@5 **0,350**, popularity 0,230, random 0,130; Hit Rate@5 0,621 (cứ 10 lần gợi ý thì hơn 6 lần có ít nhất một món khách thực sự mua kèm). HOME: hybrid 0,206 so với popularity 0,157. Bảng đầy đủ: `docs/recommendation-results.md`. |
| Vì sao hybrid tốt hơn từng thuật toán? | Mỗi tầng cứu một trường hợp tầng trước bó tay: luật kết hợp mạnh với sản phẩm đã có lịch sử (warm 0,370) nhưng bằng 0 với sản phẩm mới; content lấp chỗ đó (cold 0,257); popularity bảo đảm luôn có gợi ý. Paired bootstrap: hybrid hơn luật thuần +0,079, hơn content +0,077, hơn popularity +0,119, cả ba khoảng tin cậy đều trên 0 (C5–C7). |
| Content-based có giúp sản phẩm mới không? | Có xu hướng: 0,257 so với 0,114 của popularity (hiệu +0,14). Nhưng khoảng tin cậy −0,003 đến 0,267 chạm 0 vì chỉ có 39 ca từ 30 đơn, nên theo quy tắc đã đăng ký trước thì **chưa kết luận được** (C3). Muốn khẳng định cần thêm dữ liệu sản phẩm mới. |
| Vì sao content dùng biến thể "complement" chứ không phải "giống nhau"? | Khách xem giày chạy bộ cần mua kèm tất, quần chạy bộ, không mua thêm đôi giày khác. Trên DEV: complement 0,365, similar 0,266, popularity 0,329. Complement bỏ nhóm "loại" và "danh mục", chỉ giữ sản phẩm khác loại cùng môn, giới tính. |
| Làm sao chắc không "chỉnh theo đề thi"? | Mọi tham số chọn trên DEV (tuần 7). Kế hoạch TEST (cấu hình, 9 so sánh, quy tắc kết luận) được commit trước (`b4f44506`); báo cáo TEST ghi đúng commit đó. Script chặn chạy TEST lần hai nếu không ghi lý do. |
| Paired bootstrap là gì, sao không so hai khoảng tin cậy? | Hai thuật toán làm cùng một đề nên so hiệu từng ca. Lấy mẫu lại cả đơn hàng (các ca trong một đơn liên quan nhau) 1.000 lần, lấy khoảng 95% của hiệu. Cách này nhạy hơn: hai khoảng riêng có thể chồng nhau mà khoảng của hiệu vẫn không chứa 0. |
| Có hiệu chỉnh đa so sánh không? | Không. 9 so sánh ít và đã đăng ký trước, nhưng đây là giới hạn: với Bonferroni, C9 (cận dưới 0,0002) sẽ không còn ý nghĩa. |
| Vì sao Precision@5 chỉ 0,16? | Mỗi ca trung bình chỉ có khoảng 2 đáp án, nên P@5 tối đa khoảng 0,4. So với random (0,072) thì hybrid gấp hơn 2 lần. |
| Dữ liệu synthetic sinh thế nào, có thiên vị không? | 500 user có persona, 8 tuần, trộn 4 cơ chế: sở thích persona (70%), mua kèm bổ trợ (9%), độ phổ biến ẩn, nhiễu (22%), để không thuật toán nào thắng sẵn. Tham số chốt trước khi chạy mô hình, seed cố định, có checksum. Giới hạn: bộ sinh và content cùng dựa trên thuộc tính sản phẩm. |
| Trên website thật content có chạy không? | Có tầng content, nhưng Catalog hiện không lưu giới tính, môn, màu (trả rỗng), nên chỉ so được danh mục, thương hiệu, khoảng giá. Nếu không có đặc trưng chung, vị trí đó rơi xuống popularity. Đây là việc cần Catalog sửa dữ liệu. |
| Gợi ý mất bao lâu? | Khoảng 290 ms trên Supabase (đo khi kiểm tra, ghi ở `latency_ms`), do service gọi DB nhiều lần. Đủ cho demo; tối ưu được bằng cache ứng viên hoặc gộp truy vấn. |

---

## PHẦN E — Thuật ngữ

| Thuật ngữ | Giải thích ngắn |
|---|---|
| Top-K | K sản phẩm có điểm cao nhất được trả về |
| Implicit feedback | Sở thích suy ra từ hành vi |
| Association rule | Luật "mua A thường mua B", đo bằng support, confidence, lift |
| Cold-start | User hoặc sản phẩm mới chưa có dữ liệu |
| Embedding | Vector số biểu diễn ý nghĩa của văn bản/ảnh |
| Cosine similarity | Độ giống nhau giữa 2 vector theo góc |
| Read model / bản đọc | Bản sao dữ liệu của service khác, cập nhật qua sự kiện, chỉ để đọc |
| Eventual consistency | Bản sao sẽ khớp bản gốc sau một khoảng trễ ngắn |
| Transactional outbox | Ghi sự kiện cùng transaction với dữ liệu rồi mới publish |
| Idempotent consumer | Nhận cùng sự kiện nhiều lần vẫn chỉ xử lý một lần |
| Data leakage | Dữ liệu tương lai lọt vào lúc huấn luyện |
| Temporal split | Chia dữ liệu theo mốc thời gian |
| Candidate set | Tập sản phẩm được phép gợi ý khi đánh giá |
| Popularity bias | Xu hướng chỉ gợi ý sản phẩm đã phổ biến |
| Coverage | Tỷ lệ catalog từng xuất hiện trong gợi ý |
| Baseline | Phương pháp đơn giản làm mốc so sánh |
