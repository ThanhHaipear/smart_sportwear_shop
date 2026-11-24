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
- [x] **Review R4:** tracking mặc định tắt. Storefront cần `NEXT_PUBLIC_INTERACTION_TRACKING=true` và nguồn được chỉ định. API yêu cầu `INTERACTION_DEMO_ENABLED=true` hoặc `INTERACTION_REAL_ENABLED=true` tương ứng. Chỉ bật `REAL` sau khi vận hành retention và xóa liên kết tài khoản theo [runbook](../recommendation-retention-proposal.md). API từ chối `SYNTHETIC` và `PURCHASE` từ trình duyệt.
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
- [x] Unit test: không dùng sự kiện sau mốc cutoff; chỉ trả sản phẩm trong tập ứng viên; không trả sản phẩm nguồn; metric khớp ví dụ tính tay C.12 (`tests/test_offline_evaluation.py`, 11 test).
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
- [x] Unit test hàm metric bằng ví dụ tính tay ở mục C.12.

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

> Mỗi mục gồm ba phần: **Khái niệm** (lý thuyết), **Áp dụng vào dự án** (vì sao cần, giải quyết vấn đề gì của shop đồ thể thao), **Triển khai trong dự án** (file, hàm, bảng, tham số, số liệu thật). Đường dẫn file tính từ `recommendation-service/` nếu không ghi khác.

### C.0. Bản đồ tổng thể

```
 Storefront (trình duyệt)                Catalog / Sales (service khác)
   │ VIEW, ADD_TO_CART, WISHLIST,          │ product.reindex.requested, order.created
   │ IMPRESSION, CLICK                     │ (outbox → RabbitMQ)
   ▼                                       ▼
 Gateway ──► API /interactions        Worker (app/worker.py, app/messaging.py)
             (app/interactions.py)         │ làm giàu sự kiện (app/enrichment.py)
   │                                       │
   ▼                                       ▼
 ┌─────────────── schema recommendation (Postgres/Supabase) ───────────────┐
 │ user_interactions   catalog_products   order_baskets   processed_events │
 │ association_rules   recommendation_models                               │
 │ recommendation_requests / recommendation_items   evaluation_runs        │
 └──────────────────────────────────────────────────────────────────────────┘
   │ rebuild (khai phá luật)            │ gợi ý (app/service.py)
   ▼                                    ▼
 recommendation_models          Tầng 1 luật kết hợp → Tầng 2 content → Tầng 3 popularity
                                        │ lọc ACTIVE + còn hàng, bỏ sản phẩm nguồn
                                        ▼
                          Trang chi tiết ("Thường mua cùng"), Giỏ hàng, Chatbot

 Đánh giá offline (không đụng DB): datasets/recommendation/synthetic-v1/
   generate_synthetic_interactions.py → build_evaluation_split.py → evaluate_offline.py
   → evidence/recommendation/*.json → render_results_table.py → docs/recommendation-results.md
```

| Kiến thức | Mục | Thành phần trong dự án |
|---|---|---|
| Bài toán khuyến nghị, ngữ cảnh | C.1 | `app/api.py`, `app/schemas.py` |
| Phản hồi ngầm, thu thập hành vi | C.2 | `frontend/storefront/src/modules/recommendation/`, `app/interactions.py` |
| Read model, sự kiện, idempotent | C.3 | `app/messaging.py`, `app/enrichment.py`, `app/repository.py` |
| Popularity | C.4 | `app/domain.py` (`popularity_scores`), cột `purchase_count` |
| Luật kết hợp | C.5 | `app/domain.py` (`mine_association_rules`, `rank_rules`) |
| Content-based | C.6 | `app/content.py` |
| Hybrid, cold-start | C.7 | `app/service.py`, `app/content.py` (`layered`) |
| Lọc nghiệp vụ | C.8 | `app/repository.py` |
| Ghi model, tái lập | C.9 | `recommendation_models`, `recommendation_requests/items` |
| Dữ liệu synthetic | C.10 | `scripts/generate_synthetic_interactions.py` |
| Chia theo thời gian, DEV/TEST | C.11 | `app/offline_split.py`, `scripts/build_evaluation_split.py` |
| Chỉ số P/R/HR/NDCG/Coverage | C.12 | `app/metrics.py` |
| Bootstrap, đăng ký trước | C.13 | `app/metrics.py`, `scripts/evaluate_offline.py` |

---

### C.1. Hệ thống khuyến nghị là gì?

**Khái niệm**

- Hệ thống khuyến nghị (recommender system) ước lượng **mức độ quan tâm** của một người với từng sản phẩm, rồi trả về **top-K** sản phẩm có điểm cao nhất.
- Đầu vào có ba loại:
  - **Ngữ cảnh hiện tại**: sản phẩm đang xem, giỏ hàng đang có.
  - **Lịch sử người dùng**: đã xem, thêm giỏ, mua gì.
  - **Thông tin sản phẩm**: môn thể thao, giới tính, loại, thương hiệu, giá.
- Đầu ra là một **danh sách có thứ tự**. Vì vậy không đánh giá bằng "đúng/sai" như phân loại, mà bằng **ranking metrics** (C.12).
- Hai họ phương pháp cơ bản:
  - **Dựa trên hành vi**: popularity, luật kết hợp, collaborative filtering.
  - **Dựa trên nội dung**: content-based.
  - Kết hợp hai họ gọi là **hybrid**.

**Áp dụng vào dự án**

Shop có ba chỗ cần gợi ý, gọi là ba **ngữ cảnh**:

| Ngữ cảnh | Câu hỏi nghiệp vụ | Đầu vào | Trạng thái |
|---|---|---|---|
| `FREQUENTLY_BOUGHT_TOGETHER` (FBT) | Khách xem giày chạy bộ thì nên mua kèm gì? | 1 sản phẩm nguồn | Chạy thật trên trang chi tiết, đánh giá offline |
| `CART_RECOMMENDATION` | Giỏ đang có giày + áo thì còn thiếu gì? | Nhiều sản phẩm nguồn | Chạy thật trên trang giỏ hàng (dùng chung thuật toán FBT) |
| `HOME` ("Gợi ý cho bạn") | Khách này thích gì? | Lịch sử của user | **Chỉ đánh giá offline**, chưa có API phục vụ |

Mục tiêu kinh doanh là tăng giá trị đơn hàng (mua kèm) và giúp khách tìm đúng đồ. Mục tiêu học thuật là chứng minh bằng số liệu rằng thuật toán tốt hơn baseline.

**Triển khai trong dự án**

- Service riêng `recommendation-service` (FastAPI, cổng `:8091`). Gateway định tuyến tới, có worker RabbitMQ riêng.
- API trong `app/api.py`:
  - `GET /api/v1/recommendations/products/{id}`, và alias cũ `/api/v1/products/{id}/frequently-bought-together`: ngữ cảnh FBT.
  - `POST /api/v1/recommendations/cart`: ngữ cảnh giỏ hàng, body chứa danh sách `productIds`.
  - Số gợi ý mặc định `K = 8`, tối đa 20 (`RECOMMENDATION_DEFAULT_LIMIT`, `RECOMMENDATION_MAX_LIMIT`).
- Mỗi sản phẩm trong kết quả mang theo `algorithm` (`ASSOCIATION_RULE`, `SIMILAR_CONTENT`, `POPULARITY`), `score` và `reason`. Nhờ vậy giao diện và chatbot biết vì sao sản phẩm được gợi ý.
- Bật/tắt bằng `RECOMMENDATION_ENABLED`. Khi tắt hoặc lỗi, storefront chỉ ẩn khối gợi ý, phần bán hàng vẫn chạy.

---

### C.2. Phản hồi ngầm, thu thập hành vi và trọng số sự kiện

**Khái niệm**

- **Phản hồi tường minh** (explicit feedback): người dùng tự nói mình thích gì, ví dụ chấm 5 sao.
- **Phản hồi ngầm** (implicit feedback): suy ra sở thích từ hành vi, ví dụ xem, thêm giỏ, mua. Loại này nhiều dữ liệu hơn nhưng nhiễu hơn:
  - Hành vi có **độ tin cậy khác nhau**: mua chắc chắn hơn thêm giỏ, thêm giỏ chắc chắn hơn xem. Vì vậy cần gán **trọng số**.
  - **Không tương tác không có nghĩa là không thích**. Có thể khách chưa bao giờ nhìn thấy sản phẩm đó.
- **Impression** là việc sản phẩm được hiển thị cho khách. **Click** là khách bấm vào. Tỷ lệ click/impression gọi là **CTR**, chỉ số online cơ bản để đo hiệu quả gợi ý.

**Áp dụng vào dự án**

- Shop không có chức năng chấm điểm gợi ý, nên toàn bộ dữ liệu là phản hồi ngầm.
- Thu thập hành vi là **điều kiện tiên quyết**. Khi dự án tách sang microservice, Recommendation có **0 hành vi** (xem Giai đoạn 0) nên không thuật toán nào học được gì.
- Ghi impression và click kèm `requestId` để sau này đo được CTR **theo từng model**, tức là biết gợi ý nào thực sự được bấm.

**Triển khai trong dự án**

Phía trình duyệt (`frontend/storefront/src/modules/recommendation/`):

| Sự kiện | Khi nào ghi | File |
|---|---|---|
| `VIEW` | Mở trang chi tiết. Tối đa 1 lần/sản phẩm/10 phút mỗi trình duyệt (`VIEW_THROTTLE_MS`) | `ProductViewTracker.tsx`, `tracking.ts` |
| `ADD_TO_CART` | Thêm giỏ thành công, kèm `variantId`, `quantity` | `ProductPurchasePanel.tsx` |
| `WISHLIST` | Thêm yêu thích | `ProductPurchasePanel.tsx` |
| `RECOMMENDATION_IMPRESSION` | Khối gợi ý hiện ≥ 50% trên màn hình | `RecommendationSection.tsx` |
| `RECOMMENDATION_CLICK` | Bấm vào thẻ gợi ý, kèm `requestId`, vị trí, thuật toán | `RecommendationSection.tsx` |
| `PURCHASE` | **Không** ghi từ trình duyệt. Lấy từ sự kiện `order.created` của Sales, lưu ở `order_baskets` | `app/messaging.py` |

- Khách vãng lai có `anonymousId` (UUID lưu ở `localStorage`). Khách đã đăng nhập thì service lấy `userRef` từ header `X-User-Id` do Gateway gắn sau khi kiểm JWT, **không tin** giá trị trình duyệt tự gửi.
- Các sự kiện được gửi theo lô: `POST /api/v1/recommendations/interactions/batch`, tối đa 10 sự kiện mỗi lô. Ví dụ 4 impression của một khối gợi ý chỉ tốn 1 request.
- `keepalive: true` giúp sự kiện click vẫn gửi xong dù trang chuyển ngay. Gửi lỗi thì bỏ qua, không làm hỏng trang.

Phía server (`app/interactions.py`):
- `sanitize_browser_interaction` kiểm tra loại sự kiện theo danh sách cho phép. Server từ chối `PURCHASE` và `dataSource = SYNTHETIC` gửi từ trình duyệt, và bắt buộc có `requestId` với impression/click.
- `occurredAt` được thay bằng **giờ server nhận**. Lý do: dữ liệu sẽ chia theo thời gian (C.11), nên không được phụ thuộc đồng hồ máy khách.
- Rate limit: 60 sự kiện/phút mỗi người, 300 sự kiện/phút mỗi IP. Vượt thì trả 429.
- Lưu vào `user_interactions`, chống trùng bằng `eventId` (bảng `processed_events`).

Trọng số dùng khi tính điểm (`EVENT_WEIGHTS` trong `app/domain.py`):

| Sự kiện | VIEW | RECOMMENDATION_CLICK | SEARCH_CLICK | WISHLIST | ADD_TO_CART | PURCHASE | IMPRESSION |
|---|---|---|---|---|---|---|---|
| Trọng số | 1 | 1 | 1 | 2 | 3 | 5 | 0 |

- Impression có trọng số 0 vì "được nhìn thấy" chưa nói lên sở thích.
- Bấm gợi ý sẽ mở trang chi tiết, và trang đó lại ghi một `VIEW`. Để không đếm hai lần, `drop_views_after_clicks` bỏ `VIEW` xảy ra trong vòng 60 giây sau click của cùng người, cùng sản phẩm.
- Kiểm tra độ nhạy: thử bộ trọng số thay thế `VIEW=1, ADD_TO_CART=2, PURCHASE=3` trên DEV, kết quả cho **cùng thứ hạng**. Nghĩa là kết luận không phụ thuộc vào con số trọng số cụ thể.

---

### C.3. Lấy dữ liệu trong kiến trúc microservice: read model, sự kiện, idempotent

**Khái niệm**

- **Database per service**: mỗi service chỉ đọc/ghi schema của mình, không đọc thẳng bảng của service khác.
- **Read model (bản đọc)**: bản sao dữ liệu của service khác, được cập nhật bằng **sự kiện**, chỉ dùng để đọc.
- **Transactional outbox**: service nguồn ghi sự kiện vào bảng outbox trong **cùng transaction** với dữ liệu, rồi một tiến trình khác mới publish lên message broker. Nhờ vậy không có chuyện dữ liệu đã lưu mà sự kiện bị mất, hoặc ngược lại.
- **At-least-once delivery**: broker có thể giao một sự kiện nhiều lần. Bên nhận phải là **idempotent consumer**: nhận lặp vẫn chỉ xử lý một lần.
- **Eventual consistency**: bản sao sẽ khớp bản gốc sau một khoảng trễ ngắn, không khớp tức thì.
- **Retry + DLQ (dead-letter queue)**: xử lý lỗi thì thử lại sau một khoảng chờ tăng dần. Vẫn lỗi thì chuyển sang hàng đợi "chết" để người kiểm tra.

**Áp dụng vào dự án**

- Recommendation cần ba thứ từ service khác: **thông tin sản phẩm** (trạng thái, tồn kho, thuộc tính) từ Catalog, **giỏ đơn hàng** từ Sales, và **hành vi** từ storefront. Theo kiến trúc, nó không được đọc bảng của Catalog hay Sales.
- Khi đọc code tìm ra hai lỗi làm hệ thống trả 0 gợi ý (mục A.3):
  - **Lỗi #1**: Sales gửi **variant ID**, còn luật khai phá cần **product ID**.
  - **Lỗi #2**: Catalog chỉ gửi sự kiện "mỏng" (`productId` + `sourceVersion`, không kèm dữ liệu sản phẩm), nên bảng `catalog_products` không bao giờ được nạp.

**Triển khai trong dự án**

- Hàng đợi `recommendation.events` (RabbitMQ). Worker `app/worker.py` tự thử kết nối lại nếu RabbitMQ chưa sẵn sàng (chờ 2s, 5s, 10s, 20s, 30s).
- **Làm giàu sự kiện** (`app/enrichment.py`, `EventEnricher`) để sửa hai lỗi trên:
  - Sự kiện sản phẩm: gọi API nội bộ của Catalog `index-document` lấy trạng thái, variant, tồn kho, ảnh, và API công khai lấy `slug`, rồi dựng snapshot. Catalog trả 404 thì lưu bản ghi `DELETED`, không bao giờ được gợi ý.
  - Sự kiện đơn hàng: đổi từng variant ID sang product ID qua `GET /internal/v1/variants/{id}/sale-snapshot`. ID vốn đã là product ID thì giữ nguyên, nên vẫn đúng nếu sau này Sales đổi sang gửi product ID.
- **Idempotent** (`app/repository.py`, `apply_event`):
  - Mỗi sự kiện ghi `eventId` vào `processed_events` với `on conflict do nothing`. Đã có rồi thì bỏ qua.
  - Cùng một đơn gửi lại với `eventId` khác: `order_baskets` có khóa `order_id`, nên `purchase_count` không bị cộng hai lần.
  - `source_version`: chỉ ghi đè snapshot khi phiên bản mới ≥ phiên bản đang lưu. Sự kiện cũ đến muộn không làm hỏng dữ liệu mới.
- **Retry**: lỗi (ví dụ Catalog tạm sập) thì đẩy sang `retry.30s` → `retry.5m` → `retry.1h`, cuối cùng vào `recommendation.events.dlq`.
- **Backfill**: `scripts/backfill-read-models.ps1` nạp lại toàn bộ `catalog_products` và sửa giỏ cũ. Mặc định chạy thử, thêm `-Execute` mới ghi. Ngày 16/11 đã nạp 36 sản phẩm; chạy lặp không sinh trùng.
- **Eventual consistency**: bản sao có thể trễ vài giây. Không sao, vì khi khách đặt hàng, Sales vẫn kiểm tra tồn kho thật, nên không thể bán vượt tồn.

---

### C.4. Popularity baseline

**Khái niệm**

- Gợi ý những sản phẩm **được quan tâm nhiều nhất**, giống nhau cho mọi người.
- Công thức có trọng số và suy giảm theo thời gian:

```
score(i) = Σ  w(loại sự kiện e) · exp(−λ · (T − t_e))        λ = ln 2 / half_life
          e ∈ sự kiện của sản phẩm i, xảy ra trước mốc T
```

- `T` là mốc cắt (thời điểm huấn luyện). `T − t_e` là "tuổi" của sự kiện.
  - **Half-life (chu kỳ bán rã)**: sau `half_life` ngày, sức nặng của một sự kiện còn một nửa. Hành vi gần đây quan trọng hơn hành vi cũ, ví dụ áo World Cup hot tuần này chứ không phải tháng trước.
  - Bỏ phần `exp` thì là **đếm có trọng số** thuần.
- **Baseline**: phương pháp đơn giản làm **mốc so sánh**. Thuật toán phức tạp phải vượt được nó mới có giá trị.
- **Popularity bias**: chỉ gợi ý vài sản phẩm "hot", sản phẩm khác không bao giờ được thấy. Biểu hiện là Coverage thấp (C.12).

**Ví dụ tính tay** (half-life 14 ngày, λ = 0,0495):

| Sản phẩm | Sự kiện | Đếm thuần | Có suy giảm |
|---|---|---|---|
| A | 2 PURCHASE, cách mốc 3 ngày | 2 × 5 = **10** | 10 × e^(−0,149) = 10 × 0,862 = **8,62** |
| B | 6 ADD_TO_CART, cách mốc 28 ngày | 6 × 3 = **18** | 18 × e^(−1,386) = 18 × 0,250 = **4,50** |

Đếm thuần xếp B trên A. Có suy giảm thì A lên trước, vì hành vi của A mới hơn.

**Áp dụng vào dự án**

- **Baseline** để chứng minh luật kết hợp, content và hybrid thực sự có ích.
- **Tầng dự phòng cuối cùng**: khi không có luật và không có sản phẩm tương đồng, vẫn luôn trả đủ K gợi ý.
- **Giải quyết cold-start user**: khách mới chưa có lịch sử vẫn nhận được gợi ý hợp lý.

**Triển khai trong dự án**

Có **hai phiên bản**, cần nói rõ khi bảo vệ:

| | Khi chạy thật (website) | Khi đánh giá offline |
|---|---|---|
| Điểm | `catalog_products.purchase_count`: số đơn chứa sản phẩm | `popularity_scores()` trong `app/domain.py`: trọng số sự kiện + suy giảm |
| Cập nhật | Cộng 1 mỗi khi worker nhận `order.created` (mỗi đơn chỉ một lần) | Tính lại từ dữ liệu train, **chỉ dùng sự kiện trước mốc cắt** |
| Cấu hình | Không tham số | Half-life ∈ {không, 14, 7} ngày; **chọn 14 ngày** trên DEV |
| Sắp xếp | `order by purchase_count desc, product_id` | `rank_by_score`: điểm giảm dần, hòa thì theo ID (kết quả tất định) |

- Lý do khác nhau: bản online được viết từ trước, khi chỉ có dữ liệu đơn hàng. Bản offline làm đúng theo thiết kế (mục 4 của `docs/recommendation-design.md`). Đưa công thức có suy giảm lên bản online là hướng phát triển.
- Hệ quả hiện tại: trên website chưa có đơn nào nên mọi `purchase_count = 0`. Tầng popularity khi đó chỉ sắp theo `product_id`, giống nhau với mọi sản phẩm nguồn.
- Kết quả TEST (FBT): popularity NDCG@5 = **0,230**, gần gấp đôi random (0,130), nhưng Coverage@5 chỉ **0,17**. Đây chính là popularity bias: chỉ khoảng 6 trên 36 sản phẩm từng được gợi ý.

---

### C.5. Luật kết hợp (association rules)

**Khái niệm**

- Bắt nguồn từ **phân tích giỏ hàng** (market basket analysis): *"ai mua A thường mua B"*, ký hiệu `A → B`. A gọi là **tiền đề** (antecedent), B gọi là **hệ quả** (consequent).
- Với N giỏ đơn hàng:

```
support(A→B)    = số giỏ chứa cả A và B / N                         độ phổ biến của cặp
confidence(A→B) = số giỏ chứa cả A và B / số giỏ chứa A    = P(B | A)   mua A thì bao nhiêu % mua B
lift(A→B)       = confidence(A→B) / (số giỏ chứa B / N)    = P(B | A) / P(B)
```

- Cách đọc **lift**:
  - `lift > 1`: A và B đi cùng nhau **nhiều hơn ngẫu nhiên**, tức là có quan hệ bổ trợ thật.
  - `lift = 1`: độc lập.
  - `lift < 1`: loại trừ nhau.
- Tại sao cần lift? Confidence cao chưa chắc có ý nghĩa. Nếu B là sản phẩm ai cũng mua (P(B) cao), thì `P(B|A)` cao là đương nhiên. Lift loại bỏ hiệu ứng đó.
- Thuật toán kinh điển là **Apriori** và **FP-Growth**, dùng để tìm tập mục lớn (A, B → C...) trong dữ liệu khổng lồ.

**Ví dụ tính tay:** có 10 đơn. Giày X có trong 4 đơn, tất Y trong 5 đơn, cả hai trong 3 đơn.
- support = 3/10 = **0,30**
- confidence(X→Y) = 3/4 = **0,75**
- lift = 0,75 / (5/10) = **1,5**. Mua giày X thì khả năng mua tất Y cao gấp 1,5 lần bình thường.
- Chiều ngược lại: confidence(Y→X) = 3/5 = 0,60, lift vẫn 1,5. Lift đối xứng, confidence thì không.

**Áp dụng vào dự án**

- Rất hợp với ngữ cảnh **"Thường mua cùng"**: học được **quan hệ bổ trợ** như giày + tất, áo đấu + quần đùi. Đây là điều content "giống nhau" không học được.
- **Dễ giải thích** với khách và hội đồng ("6 trên 10 người mua giày này cũng mua tất này").
- Nhược điểm: cần nhiều đơn có ≥ 2 sản phẩm. Sản phẩm mới chưa có đơn thì không có luật nào (**cold-start item**). Trên TEST, luật thuần có NDCG@5 = **0** với 39 ca cold.

**Triển khai trong dự án**

- **Khai phá** (`mine_association_rules` trong `app/domain.py`):
  - Không dùng Apriori. Code đếm **mọi cặp** sản phẩm trong mỗi giỏ (`itertools.combinations`), vì chỉ cần luật 1 → 1 và catalog nhỏ (36 sản phẩm, tối đa 630 cặp).
  - Mỗi cặp sinh hai chiều A→B và B→A, giữ luật đạt **cả ba ngưỡng**.
  - Ngưỡng mặc định **support ≥ 0,005, confidence ≥ 0,05, lift ≥ 1,0**, chọn trên DEV từ lưới 3 × 3 (Giai đoạn 6). Support 0,005 xấp xỉ 3 giỏ trên dữ liệu synthetic. Đây là biên thấp nhất của lưới, đã ghi nhận như một giới hạn.
- **Huấn luyện lại** (`RecommendationService.rebuild`): chỉ lấy giỏ **trước mốc cắt** (`baskets_before(cutoff)`). Trong một transaction: lưu trữ luật cũ (`ARCHIVED`), ghi luật mới vào `association_rules`, ghi một dòng `recommendation_models` (C.9). Chạy bằng `scripts/rebuild_model.py <version>` hoặc `POST /internal/v1/models/rebuild`.
- **Xếp hạng khi gợi ý** (`rank_rules`):
  - Lấy các luật có tiền đề nằm trong tập nguồn (1 sản phẩm hoặc cả giỏ hàng).
  - Bỏ hệ quả đã có trong nguồn.
  - Mỗi sản phẩm hệ quả giữ **luật tốt nhất**. Ví dụ giỏ có giày và áo cùng dẫn tới tất thì chỉ giữ luật mạnh hơn.
  - Sắp theo confidence → lift → support. Điểm hiển thị là `confidence × lift`.
- Mỗi gợi ý kèm `support`, `confidence`, `lift`, `pairCount` trong response, nên có thể kiểm tra lại.
- Kết quả TEST (FBT): luật thuần ở ca **warm** đạt NDCG@5 **0,370**, so với popularity 0,273. Paired bootstrap C1: +0,097, có ý nghĩa.

---

### C.6. Content-based (dựa trên nội dung)

**Khái niệm**

- Gợi ý sản phẩm **giống về đặc điểm** với sản phẩm nguồn, hoặc với hồ sơ sở thích của user. Không cần dữ liệu hành vi của người khác.
- Hai cách biểu diễn sản phẩm:
  - **Embedding**: vector số sinh bởi mô hình ngôn ngữ/ảnh, so bằng **cosine** `cos(a, b) = (a·b) / (‖a‖·‖b‖)`.
  - **Tập thuộc tính**: so bằng **Jaccard** `J(A, B) = |A ∩ B| / |A ∪ B|`, nhận giá trị từ 0 (không chung gì) đến 1 (trùng hết).
- Hai dạng bài toán:
  - **Item–item**: điểm của j = độ giống giữa sản phẩm nguồn và j.
  - **User-profile**: gom thuộc tính các sản phẩm user đã tương tác (có trọng số) thành một hồ sơ, rồi chấm sản phẩm theo mức khớp với hồ sơ.
- Ưu điểm: gợi ý được **sản phẩm mới** ngay (chỉ cần thuộc tính), giải thích được.
- Nhược điểm: dễ chỉ gợi ý đồ giống hệt nhau, và phụ thuộc chất lượng dữ liệu thuộc tính.

**Áp dụng vào dự án**

- Mục đích chính là **lấp chỗ trống của luật kết hợp** với sản phẩm mới (cold-start item), ví dụ 12 sản phẩm ra mắt ở tuần TEST.
- **Vì sao không dùng embedding?** Embedding văn bản nằm trong schema `ai_search` của Search service. Theo nguyên tắc database per service, Recommendation không được đọc. Vì vậy chọn **cách B**: Jaccard trên thuộc tính lấy từ snapshot sản phẩm.
- **Vì sao có biến thể "complement"?** Khách xem giày chạy bộ cần **tất, quần chạy bộ**, không cần thêm một đôi giày khác. Bản "giống nhau" (SIMILAR) gợi ý giày khác, sai với ý "mua kèm".

**Triển khai trong dự án** (`app/content.py`)

- Mỗi sản phẩm là một tập đặc trưng chia **7 nhóm**, mỗi nhóm có trọng số (`GROUP_WEIGHTS`):

| Nhóm | sport | gender | type | category | brand | price | color |
|---|---|---|---|---|---|---|---|
| Trọng số | 3 | 2 | 1 | 1 | 1 | 1 | 0,5 |

- `price` không dùng giá trực tiếp mà chia **4 khoảng giá** theo tứ phân vị của tập ứng viên (`price_thresholds`, `price_band` → Q1–Q4).
- Độ giống = **trung bình có trọng số của Jaccard từng nhóm**:

```
sim(a, b) = Σ_g  w_g · J(a_g, b_g)  /  Σ_g w_g
```

- Hai biến thể item–item (`item_scores`):
  - `SIMILAR`: dùng cả 7 nhóm.
  - `COMPLEMENT` (**đang dùng**, chọn trên DEV): bỏ nhóm `type` và `category`, **chỉ giữ ứng viên khác loại** với sản phẩm nguồn.
  - Giỏ có nhiều sản phẩm thì mỗi ứng viên lấy điểm giống **cao nhất** với bất kỳ sản phẩm nào trong giỏ.
- Chỉ trả sản phẩm có điểm **> 0**. Không có điểm chung nào thì nhường vị trí cho tầng popularity.
- **Ví dụ** (COMPLEMENT, tổng trọng số 3 + 2 + 1 + 1 + 0,5 = 7,5). Nguồn là *giày chạy bộ nam Nike, giá Q4, màu đen*:

| Ứng viên | sport | gender | brand | price | color | Điểm |
|---|---|---|---|---|---|---|
| Tất chạy bộ nam Nike, Q1, đen | 1 | 1 | 1 | 0 | 1 | (3 + 2 + 1 + 0 + 0,5) / 7,5 = **0,87** |
| Quần chạy bộ nữ Adidas, Q2, xanh | 1 | 0 | 0 | 0 | 0 | 3 / 7,5 = **0,40** |
| Giày chạy bộ nam Nike khác | — | — | — | — | — | **Bị loại** (cùng loại giày) |

- **User-profile** cho HOME (`user_profile`, `profile_scores`):
  - Hồ sơ `p_u(f)` = tổng `trọng số sự kiện × suy giảm` của các sản phẩm user đã tương tác có đặc trưng f, chuẩn hóa để tổng bằng 1.
  - Điểm sản phẩm j = tổng `p_u(f)` trên các đặc trưng của j, chia cho số đặc trưng của j.
- Kết quả:
  - DEV (FBT warm): complement **0,365** > popularity 0,329 > similar 0,266. Chứng minh "mua kèm" cần đồ khác loại.
  - TEST, ca **cold**: content **0,257** so với popularity 0,114. Có xu hướng tốt hơn nhưng khoảng tin cậy chạm 0 (C.13).
  - TEST HOME warm: hồ sơ nội dung 0,235 so với popularity 0,168. Có ý nghĩa (C8).
- **Hạn chế trên website thật**: Catalog hiện trả rỗng `gender`, `sportCodes`, `colorFamily`, `productType`. Hai nhóm nặng nhất (sport 3, gender 2) mất tác dụng, chỉ so được danh mục, thương hiệu, khoảng giá. Đó là lý do có lúc giày chạy bộ được gợi ý kèm áo bóng đá. Trên dữ liệu synthetic, các thuộc tính này được bổ sung từ `catalog/*/manifest.json`.

---

### C.7. Hybrid theo tầng và cold-start

**Khái niệm**

- **Cold-start** là thiếu dữ liệu để gợi ý. Có hai loại:
  - **Cold-start user**: khách mới, chưa có lịch sử.
  - **Cold-start item**: sản phẩm mới, chưa ai mua.
- **Hybrid** là kết hợp nhiều thuật toán để bù nhược điểm cho nhau. Các kiểu phổ biến:
  - **Weighted**: trộn điểm, ví dụ `α·điểm_luật + (1−α)·điểm_content`.
  - **Switching / cascade (theo tầng)**: dùng thuật toán chính trước, thiếu thì thuật toán phụ bổ sung.
- Trong đánh giá, ca có sản phẩm nguồn (hoặc user) đã xuất hiện trong dữ liệu train gọi là **warm**, chưa xuất hiện gọi là **cold**.

**Áp dụng vào dự án**

Mỗi thuật toán đơn lẻ đều có điểm mù. Hybrid theo tầng cho mỗi tầng cứu đúng trường hợp tầng trước bó tay:

| Tình huống | Luật kết hợp | Content | Popularity |
|---|---|---|---|
| Sản phẩm đã có lịch sử mua kèm (warm) | **Mạnh nhất** (0,370) | Trung bình | Yếu |
| Sản phẩm mới (cold item) | Không có luật (0) | **Lấp được** (0,257) | Yếu (0,114) |
| Khách mới (cold user) | — | Không có hồ sơ | **Luôn có kết quả** |

**Triển khai trong dự án**

- `RecommendationService.recommend` trong `app/service.py`:
  1. **Tầng 1**: luật kết hợp (`rank_rules`), lọc sản phẩm ACTIVE và còn hàng.
  2. Thiếu K thì **tầng 2**: content (`_content`, chế độ `RECOMMENDATION_CONTENT_MODE=COMPLEMENT`), loại sản phẩm đã có.
  3. Vẫn thiếu thì **tầng 3**: popularity (`repository.popular`).
- Mỗi sản phẩm giữ `algorithm` của tầng sinh ra nó. Nhờ vậy `recommendation_items.reason_code` cho biết tầng nào đã đóng góp, và chatbot nêu được lý do.
- Bản offline dùng hàm `layered()` trong `app/content.py` với cùng logic. HOME thì có hai tầng: hồ sơ nội dung → popularity.
- **Vì sao không trộn điểm α?** Điều kiện đặt trước trong thiết kế (mục 5.5): chỉ làm khi hybrid theo tầng thua thuật toán đơn lẻ tốt nhất trên DEV. Không xảy ra, nên không thêm tham số phải dò.
- Kết quả TEST (FBT tất cả): hybrid NDCG@5 **0,350**, cao hơn luật thuần (+0,079), content (+0,077), popularity (+0,119). Cả ba hiệu đều có khoảng tin cậy nằm trên 0 (C5–C7).
- Đổi `RECOMMENDATION_CONTENT_MODE=OFF` để tắt tầng content mà không cần sửa code. Hữu ích khi demo nếu Catalog còn thiếu thuộc tính.

---

### C.8. Lọc theo nghiệp vụ

**Khái niệm**

- Điểm số chỉ nói "khách có thể thích", còn **quy tắc nghiệp vụ** quyết định "có được phép gợi ý không". Bước lọc thường đặt **sau khi tính điểm** (post-filtering) và áp dụng chung cho mọi thuật toán.
- Lọc xong có thể bị thiếu K, nên cần lấy dư ứng viên hoặc có tầng dự phòng.

**Áp dụng vào dự án**

- Gợi ý sản phẩm **ngừng bán hoặc hết hàng** làm khách bực mình và mất lòng tin. Đây là yêu cầu bắt buộc trong bảng phân công.
- Không gợi ý lại chính sản phẩm khách đang xem hoặc đã có trong giỏ.

**Triển khai trong dự án**

- Mọi truy vấn lấy sản phẩm để trả về đều kèm điều kiện `status = 'ACTIVE' and available_quantity > 0` (`products`, `candidates`, `popular` trong `app/repository.py`).
  - `available_quantity` là tổng `availableQuantity` (do Catalog tính) của các variant đang ACTIVE (`app/catalog.py`, `build_snapshot`).
- **Bỏ sản phẩm nguồn**: `rank_rules` bỏ hệ quả nằm trong nguồn, content loại `excluded`, popularity loại `excluded`.
- **Không trùng giữa các tầng**: tầng sau loại các sản phẩm tầng trước đã chọn.
- Sản phẩm bị xóa khỏi Catalog được lưu `status = DELETED` nên tự rơi khỏi mọi truy vấn.
- Trong đánh giá offline, tập ứng viên (C.11) cũng chỉ gồm sản phẩm ACTIVE, còn hàng **và đã ra mắt** tại thời điểm đánh giá (`candidate_set`).
- Bộ lọc tùy chọn "bỏ sản phẩm đã mua" mới chỉ áp dụng khi đánh giá HOME (thiết kế mục 6.4).
- Có test: sản phẩm hết hàng hoặc ngừng bán không được gợi ý (`tests/test_enrichment.py`).

---

### C.9. Ghi lại model và kết quả, tái lập (reproducibility)

**Khái niệm**

- **Tái lập** nghĩa là người khác (hoặc chính mình sau này) chạy lại phải ra **đúng kết quả cũ**. Muốn vậy phải lưu đủ "nguyên liệu":
  - dữ liệu, xác định bằng **checksum**;
  - cấu hình hoặc siêu tham số;
  - mốc thời gian huấn luyện;
  - seed ngẫu nhiên;
  - phiên bản code (git commit).
- **Checksum SHA-256** là "dấu vân tay" của dữ liệu: đổi một byte thì checksum đổi hoàn toàn.
- **Model versioning**: mỗi lần huấn luyện là một phiên bản. Chỉ một phiên bản ACTIVE, các bản cũ chuyển ARCHIVED.
- **Logging request** để biết mỗi lần gợi ý đã trả gì, bằng model nào, mất bao lâu. Đây là cơ sở đo CTR và gỡ lỗi.

**Áp dụng vào dự án**

- Quy tắc chung của nhóm: *"Mỗi chức năng phải ghi model_id/model_version để tái lập kết quả."*
- Khi hội đồng hỏi "số 0,350 này từ đâu ra", phải chỉ được: model nào, dữ liệu nào, commit nào.

**Triển khai trong dự án**

| Bảng / file | Ghi gì |
|---|---|
| `recommendation_models` | `algorithm`, `version`, `config` (ngưỡng, số giỏ, số luật), `training_cutoff_at`, `training_event_checksum` (SHA-256 của giỏ train ở dạng chuẩn hóa), `status` |
| `association_rules` | Từng luật kèm `model_version` |
| `recommendation_requests` | Mỗi lần gợi ý: `id` (= `requestId`), `model_version`, `user_ref`, `context_type`, `source_product_ids`, `latency_ms` |
| `recommendation_items` | Từng sản phẩm đã trả: `rank`, `product_id`, `score`, `reason_code` |
| `evaluation_runs` (+ V3) | Kết quả đánh giá: `task`, `segment`, `k`, P/R/HR/NDCG/Coverage, `case_count`, `report_sha256` |
| `evidence/recommendation/*.json` | Báo cáo offline: commit, checksum dữ liệu, checksum giỏ train, seed, cấu hình |

- `training_checksum()` trong `app/domain.py`: sắp giỏ theo `orderId`, sắp sản phẩm, đưa giờ về UTC rồi băm. Cùng dữ liệu thì luôn ra cùng checksum, dù DB trả thứ tự khác.
- `requestId` được gắn vào impression/click (C.2). Từ đó nối được "model X trả danh sách Y, khách bấm vào vị trí Z".
- Đo thực tế trên Supabase: một lần gợi ý khoảng **290 ms** (`latency_ms`), do service gọi DB nhiều lần.
- `scripts/verify_model_tracking.py` kiểm tra SQL trên schema thật trong một transaction rồi rollback, không để lại dữ liệu.
- Lưu ý: migration V3 chưa áp dụng lên Supabase (cần role quản trị), nên kết quả TEST hiện lưu ở file JSON, chưa ghi vào `evaluation_runs`.

---

### C.10. Dữ liệu hành vi synthetic

**Khái niệm**

- **Dữ liệu synthetic** là dữ liệu sinh bằng chương trình theo một mô hình giả định, dùng khi chưa có hoặc không đủ dữ liệu thật.
- Yêu cầu để dữ liệu này đáng tin:
  - **seed cố định**, chạy lại ra y hệt;
  - **tham số chốt trước** khi xem kết quả mô hình, để không "chỉnh dữ liệu cho mô hình thắng";
  - có **nhiễu**;
  - trộn **nhiều cơ chế**, để không thuật toán nào thắng sẵn.

**Áp dụng vào dự án**

- Shop là đồ án, chưa có khách thật, nên `user_interactions` và `order_baskets` gần như rỗng. Không có dữ liệu thì không đánh giá được.
- Dữ liệu synthetic dùng để chứng minh **pipeline và phương pháp đánh giá đúng**. Nó **không** chứng minh hiệu quả kinh doanh. Phải nói rõ điều này với hội đồng.

**Triển khai trong dự án** (`scripts/generate_synthetic_interactions.py`)

- Seed `20261117`. Có 500 user giả, mỗi user có **persona**: môn thể thao, giới tính, ngân sách; 40% trung thành với một thương hiệu.
- Phễu `VIEW → ADD_TO_CART → PURCHASE` trong **8 tuần** từ 01/09/2026, mỗi sự kiện có timestamp.
- Bốn cơ chế trộn lẫn: sở thích theo persona (~70%), mua kèm bổ trợ theo bảng `complements` (~9%), độ phổ biến ẩn, nhiễu (cấu hình 25%, thực tế 21,5%).
- Kết quả: **623 đơn**, trong đó **324 đơn có ≥ 2 sản phẩm**, dùng để khai phá luật.
- Chỉ dùng **36 sản phẩm có thật** trong catalog: `scripts/export_catalog_snapshot.py` xuất snapshot qua API Catalog.
- Kịch bản S1: 12 sản phẩm **ra mắt ngày 49** (tuần 8), để tuần TEST có 39 ca cold đủ đo content-based.
- Manifest + SHA-256 ở `evidence/recommendation/synthetic-v1-manifest.json`, `dataSource = SYNTHETIC`. **Không** ghi vào Supabase; đánh giá chạy trên file.
- **Thiên vị cần thừa nhận**: bộ sinh chọn sản phẩm dựa trên thuộc tính (persona → môn, giới tính), mà content cũng dùng đúng các thuộc tính đó. Content vì vậy có lợi thế sẵn.

---

### C.11. Đánh giá offline: chia theo thời gian, DEV/TEST, tập ứng viên

**Khái niệm**

- **Đánh giá offline**: dùng dữ liệu lịch sử, giả vờ "quay về quá khứ", cho mô hình gợi ý, rồi so với những gì khách **thực sự làm sau đó**.
- **Data leakage (rò rỉ dữ liệu)**: thông tin tương lai lọt vào lúc huấn luyện, làm metric cao giả tạo. Chia ngẫu nhiên các sự kiện là kiểu rò rỉ phổ biến nhất trong khuyến nghị: mô hình học từ đơn tuần 8 để "đoán" đơn tuần 7.
- **Temporal split (chia theo thời gian)**: train là mọi thứ **trước** mốc cắt, đánh giá là khoảng **sau** mốc cắt. Đúng với thực tế: hệ thống chỉ biết quá khứ.
- **DEV / TEST**:
  - **DEV** dùng để thử và **chọn cấu hình** (ngưỡng, half-life, biến thể).
  - **TEST** chỉ chạy **một lần** với cấu hình đã chốt để báo cáo.
  - Dùng TEST để chỉnh thì kết quả TEST không còn khách quan ("học tủ đề thi").
- **Tập ứng viên** (candidate set): tập sản phẩm được phép gợi ý, **giống nhau cho mọi thuật toán**, để so sánh công bằng.
- **Ground truth / sản phẩm liên quan**: định nghĩa trước sản phẩm nào được coi là "đúng".

**Áp dụng vào dự án**

- Quy tắc nhóm: *"Không dùng TEST để điều chỉnh mô hình; chỉ dùng DEV để lựa chọn cấu hình."* Và `plan.md` quy tắc 14: *"protocol đánh giá phải có trước implementation"*.
- Bảng phân công yêu cầu rõ "tạo tập đánh giá **theo thời gian**".

**Triển khai trong dự án** (`app/offline_split.py`, `scripts/build_evaluation_split.py`)

```
|---------- tuần 1–6 ----------|-- tuần 7 --|-- tuần 8 --|
 DEV:  train ─────────────────► đánh giá
 TEST: train ──────────────────────────────► đánh giá
```

- Mốc là **nửa mở**: train dùng `occurredAt < trainUntil`. Khi chạy TEST, mô hình được huấn luyện lại trên tuần 1–7.
- **Ca đánh giá FBT** (`fbt_cases`), kiểu leave-one-in:
  - Mỗi đơn trong tuần đánh giá có ≥ 2 sản phẩm.
  - Mỗi sản phẩm của đơn lần lượt làm nguồn; **các sản phẩm còn lại của đơn là đáp án**.
  - Ca là warm nếu sản phẩm nguồn đã có trong giỏ train.
- **Ca đánh giá HOME** (`home_cases`):
  - Đáp án là sản phẩm user thêm giỏ (mức 1) hoặc mua (mức 2) trong tuần đánh giá.
  - Bỏ sản phẩm user đã mua trong train.
  - Bỏ user không có đáp án (chỉ xem).
- **Tập ứng viên đóng băng kèm checksum**: DEV 24 sản phẩm (12 sản phẩm mới chưa ra mắt), TEST 36 sản phẩm.

| Split | Ứng viên | FBT tất cả / warm / cold | HOME tất cả / warm / cold |
|---|---|---|---|
| DEV (tuần 7) | 24 | 125 / 125 / 0 | 102 / 88 / 14 |
| TEST (tuần 8) | 36 | 145 / 106 / 39 | 156 / 117 / 39 |

- Mọi thuật toán, kể cả popularity, **chỉ dùng sự kiện trước mốc cắt**. Có unit test cho điều này (`tests/test_offline_evaluation.py`).
- `scripts/evaluate_offline.py` **chặn chạy TEST** khi thiếu cờ `--final`, khi không ở stage `final`, hoặc khi đã chạy TEST mà không ghi `--rerun-reason`.
- TEST đã chạy **một lần** ngày 17/11, từ commit `b4f44506`.

---

### C.12. Các chỉ số đánh giá

**Khái niệm**

Ký hiệu: `L_K` là K gợi ý đầu, `R` là tập sản phẩm liên quan (đáp án), `rel_i` là mức liên quan của sản phẩm ở vị trí i.

| Chỉ số | Công thức | Ý nghĩa | Không đo được gì |
|---|---|---|---|
| **Precision@K** | `|L_K ∩ R| / K` | Trong K gợi ý, bao nhiêu % đúng | Thứ tự; bị giới hạn khi R nhỏ |
| **Recall@K** | `|L_K ∩ R| / |R|` | Trong các đáp án, tìm được bao nhiêu % | Thứ tự |
| **Hit Rate@K** | `1` nếu có ≥ 1 gợi ý đúng, ngược lại `0` | Tỷ lệ lần gợi ý "không vô ích" | Đúng bao nhiêu, ở vị trí nào |
| **NDCG@K** | `DCG@K / IDCG@K` | Chất lượng **có tính thứ hạng**: đúng ở vị trí cao được thưởng nhiều hơn | — |
| **Coverage@K** | Số sản phẩm khác nhau từng xuất hiện trong top-K / số ứng viên | Độ đa dạng, phát hiện popularity bias | Độ chính xác |

```
DCG@K  = Σ_{i=1..K} rel_i / log2(i + 1)       vị trí 1 chia log2(2) = 1, vị trí 2 chia 1,585, ...
IDCG@K = DCG của danh sách lý tưởng (đáp án xếp hết lên đầu) → NDCG nằm trong [0, 1]
```

**Ví dụ tính tay:** K = 5, `L = [A, B, C, D, E]`, `R = {B, E, F}`.
- Precision@5 = 2/5 = **0,40**; Recall@5 = 2/3 = **0,667**; Hit Rate@5 = **1**.
- DCG@5 = 1/log2(3) + 1/log2(6) = 0,631 + 0,387 = 1,018.
- IDCG@5 = 1 + 0,631 + 0,5 = 2,131 → NDCG@5 = **0,478**.
- Nếu B, E ở vị trí 1, 2 thì NDCG = 1,631 / 2,131 = **0,765**, còn Precision không đổi. Đây là lý do cần NDCG.

> Lưu ý nguồn: BCDK1 trích Järvelin & Kekäläinen (2002); bài gốc định nghĩa DCG hơi khác (vị trí 1 không chia log). Code dùng mẫu số `log2(i + 1)` cho mọi vị trí, giống `sklearn.metrics.ndcg_score`.

**Áp dụng vào dự án**

- Bảng phân công yêu cầu **P@K, R@K, HR@K, NDCG@K**. Coverage được thêm để phát hiện popularity bias.
- Đo ở **K = 5** (số thẻ thấy ngay trên màn hình) và **K = 10**.
- Cách đọc số liệu:
  - Mỗi ca FBT trung bình chỉ có khoảng 2 đáp án, nên P@5 **tối đa khoảng 0,4**. P@5 = 0,163 không có nghĩa là tệ; phải so với **random** (0,072).
  - Hit Rate dễ giải thích nhất: HR@5 = 0,621 nghĩa là cứ 10 lần gợi ý thì hơn 6 lần có ít nhất một món khách thực sự mua kèm.
  - NDCG là **chỉ số chính** dùng để chọn cấu hình và so sánh.

**Triển khai trong dự án** (`app/metrics.py`)

- `precision_at_k`, `recall_at_k`, `hit_rate_at_k`, `ndcg_at_k`, `coverage_at_k`, gộp trong `case_metrics`.
- `relevant` là dict `sản phẩm → mức liên quan`:
  - FBT: mọi đáp án mức 1 (nhị phân).
  - HOME: `PURCHASE = 2`, `ADD_TO_CART = 1`, nên NDCG thưởng nhiều hơn khi đoán đúng món khách **mua**.
- Danh sách ngắn hơn K (ví dụ luật thuần không đủ luật) được coi như phần còn lại là trượt: Precision vẫn chia cho K.
- `grouped_mean`: lấy trung bình theo đơn trước rồi mới trung bình chung, để một đơn nhiều sản phẩm không lấn át.
- Unit test khớp đúng ví dụ tính tay ở trên.
- Kết quả TEST đầy đủ (P/R/HR/NDCG @5, @10, Coverage, tách warm/cold) được sinh tự động: `scripts/render_results_table.py` → `docs/recommendation-results.md`.

---

### C.13. Khoảng tin cậy bootstrap, paired bootstrap và đăng ký trước

**Khái niệm**

- Một con số trung bình (NDCG = 0,350) chưa đủ. Với 145 ca, chỉ cần đổi vài ca là số đã khác. Cần biết **độ dao động**, tức **khoảng tin cậy 95% (CI)**.
- **Bootstrap** ước lượng CI mà không cần giả định phân phối:
  1. Lấy mẫu lại có hoàn lại từ dữ liệu, cùng kích thước.
  2. Tính lại trung bình.
  3. Lặp 1.000 lần.
  4. Lấy phân vị 2,5% và 97,5% làm CI.
- **Bootstrap theo cụm**: các ca trong cùng một đơn (hoặc cùng một user) liên quan nhau, nên phải lấy mẫu lại **cả đơn**, không lấy từng ca riêng lẻ.
- **Paired bootstrap**: so hai thuật toán **trên cùng các ca**. Tính hiệu từng ca rồi bootstrap hiệu đó.
  - Nếu CI của hiệu **không chứa 0** thì kết luận có khác biệt.
  - Cách này nhạy hơn so hai CI riêng: hai CI có thể chồng nhau mà CI của hiệu vẫn nằm trên 0.
- **Đăng ký trước (pre-registration)**: ghi sẵn cấu hình, các phép so sánh và quy tắc kết luận **trước** khi chạy TEST. Như vậy không thể chọn sau "so sánh nào đẹp thì báo cáo".
- **Đa so sánh**: làm nhiều phép kiểm định thì tăng khả năng có một kết quả "có ý nghĩa" do may mắn. Hiệu chỉnh kiểu Bonferroni làm ngưỡng chặt hơn.

**Áp dụng vào dự án**

- Catalog nhỏ (36 sản phẩm), số ca ít (145 FBT, 156 HOME), nên metric dao động lớn. Thiếu CI thì không thể nói "hybrid tốt hơn" một cách có cơ sở.
- Bốn câu hỏi nghiên cứu RQ1–RQ4 được trả lời bằng 9 so sánh **C1–C9** đăng ký trước.

**Triển khai trong dự án**

- `bootstrap_ci` trong `app/metrics.py`: 1.000 lần lấy mẫu lại, theo **đơn hàng** (FBT) hoặc **user** (HOME), seed cố định.
- `paired_bootstrap`: trả `meanDifference`, `ci95`, `verdict`. Chỉ kết luận "tốt hơn" khi CI không chứa 0.
- Đăng ký trước: `docs/recommendation-design.md` mục 7.9, commit `b4f44506` **trước** khi chạy TEST. Báo cáo TEST ghi đúng commit này.
- Kết quả:
  - RQ1, RQ3, RQ4 được ủng hộ.
  - **RQ2** (content giúp sản phẩm mới) có chiều thuận: +0,143, nhưng CI từ −0,003 đến 0,267 chạm 0, vì chỉ có 39 ca từ 30 đơn. Theo quy tắc đã đăng ký thì **chưa kết luận được**. Báo cáo trung thực đúng như vậy.
  - Không hiệu chỉnh đa so sánh. Nếu áp Bonferroni thì C9 (cận dưới 0,0002) sẽ mất ý nghĩa. Đã ghi nhận là giới hạn.

---

### C.14. Các phương pháp không dùng (để trả lời "tại sao không dùng?")

| Phương pháp | Khái niệm ngắn | Lý do không dùng trong dự án |
|---|---|---|
| **Collaborative filtering** (user-based, item-based) | "Người giống bạn thích gì": tìm user/sản phẩm tương tự qua ma trận tương tác | Dữ liệu thưa, catalog nhỏ; không xử lý được cold-start. `plan.md` 8.3: *"Không triển khai CF phức tạp khi dữ liệu hành vi chưa đủ."* |
| **Matrix Factorization / ALS** | Phân rã ma trận user × sản phẩm thành vector ẩn | Cần nhiều tương tác; dễ overfit với 500 user × 36 sản phẩm; khó giải thích |
| **Deep learning** (two-tower, sequence models) | Mạng nơ-ron học từ chuỗi hành vi | Cần dữ liệu lớn và GPU; quá mức cho pilot |
| **Embedding văn bản/ảnh cho content** | So vector của Search service | Vi phạm database per service; Jaccard trên thuộc tính đủ tốt và giải thích được |
| **Apriori / FP-Growth** | Khai phá tập mục nhiều phần tử | Chỉ cần luật 1 → 1; đếm mọi cặp với 36 sản phẩm là đủ nhanh |
| **Hybrid trộn điểm α** | Cộng điểm có trọng số | Hybrid theo tầng đã đạt điều kiện ở DEV; thêm α là thêm tham số phải dò |
| **Fine-tuning, ANN** | Tinh chỉnh mô hình; tìm kiếm gần đúng | Quy tắc nhóm: chưa cần trong pilot |

---

### C.15. Giới hạn cần nêu thẳng thắn

1. **Dữ liệu synthetic**: kết quả chứng minh phương pháp đúng, chưa chứng minh tăng doanh số. Bộ sinh dựa trên thuộc tính nên content có lợi thế sẵn.
2. **Catalog nhỏ** (36 sản phẩm), ít ca → CI rộng, RQ2 chưa kết luận được.
3. **Offline ≠ online**: muốn chứng minh hiệu quả thật cần **A/B test** và đo CTR, tỷ lệ chuyển đổi. Hạ tầng đã có sẵn (impression/click kèm `requestId`).
4. **Popularity online khác offline**: website dùng `purchase_count`, đánh giá dùng công thức có trọng số và suy giảm.
5. **HOME chưa có API** phục vụ thật, mới đánh giá offline.
6. **Catalog thiếu thuộc tính** (`gender`, `sportCodes`, `colorFamily`, `productType`) → content trên website yếu hơn trên dữ liệu đánh giá.
7. **Đơn bị hủy vẫn tính là đã mua**, vì Sales chưa phát `order.cancelled` đúng định dạng vào hàng đợi.
8. **Eventual consistency**: bản sao catalog có thể trễ vài giây.
9. **Độ trễ khoảng 290 ms** mỗi lần gợi ý, chưa tối ưu (có thể cache ứng viên hoặc gộp truy vấn).
10. **Không hiệu chỉnh đa so sánh** cho 9 phép so sánh.

---

## PHẦN D — Câu hỏi bảo vệ thường gặp

| Câu hỏi | Gợi ý trả lời |
|---|---|
| Vì sao tách recommendation thành service riêng? | Triển khai, mở rộng và tắt độc lập; lỗi AI không làm sập bán hàng (tắt `RECOMMENDATION_ENABLED` thì storefront chỉ ẩn khối gợi ý). Có schema và role DB riêng. |
| Service lấy dữ liệu sản phẩm và đơn hàng thế nào khi không được đọc DB của service khác? | Nhận sự kiện qua RabbitMQ (outbox của Catalog và Sales), lưu bản đọc `catalog_products`, `order_baskets`. Chống trùng bằng `processed_events`, bỏ bản cũ bằng `source_version`. |
| Làm sao không gợi ý sản phẩm hết hàng? | Lọc `ACTIVE` và `available_quantity > 0` trên bản đọc; Sales vẫn kiểm tra tồn thật khi đặt hàng. |
| Vì sao dùng luật kết hợp và popularity mà không dùng collaborative filtering? | Dữ liệu ít, thưa, catalog nhỏ; CF cần nhiều tương tác và không xử lý cold-start. Luật kết hợp giải thích được và phù hợp ngữ cảnh "thường mua cùng"; popularity là baseline và fallback. |
| Support, confidence, lift khác nhau thế nào? | Dùng ví dụ ở C.5. |
| Vì sao chia train/test theo thời gian? | Tránh data leakage; mô phỏng đúng việc chỉ biết quá khứ. |
| DEV và TEST khác nhau? | DEV chọn cấu hình; TEST chạy một lần để báo cáo. |
| Vì sao cần NDCG khi đã có Precision? | Precision không quan tâm thứ tự; NDCG thưởng cho gợi ý đúng ở vị trí cao. |
| Precision chỉ 0,2 có tệ không? | So với random baseline và popularity; nếu user chỉ có 1–2 sản phẩm liên quan thì P@5 tối đa 0,2–0,4. |
| Cold-start xử lý thế nào? | User mới: popularity. Sản phẩm mới: content-based. |
| Dữ liệu synthetic có tin được không? | Seed cố định, tham số chốt trước, có nhiễu; kết quả chứng minh pipeline và phương pháp đánh giá đúng, chưa chứng minh hiệu quả kinh doanh. |
| Làm sao tái lập kết quả? | Lưu model version, config, cutoff, checksum dữ liệu, seed, git commit (C.9). |
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
