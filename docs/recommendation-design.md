# Thiết kế Recommendation và protocol đánh giá offline

> **Trạng thái:** đã chốt, v1.0, ngày 17/11/2026, người viết: Quyền. Không qua vòng review của Dũng và Hải; các điểm cần phối hợp được tự quyết ở mục 11.
>
> Tài liệu được chốt **trước khi** viết thuật toán mới, sinh dữ liệu synthetic hay chạy đánh giá (`plan.md`, nguyên tắc 14). Sau khi đã xem kết quả TEST thì không sửa protocol nữa. Mọi thay đổi sau review ghi ở mục 12.
>
> Tài liệu liên quan: `docs/overview/Mission.md` (checklist, kiến thức nền), `docs/microservice-phase-5-recommendation-service.md` (kiến trúc service), `contracts/rest/recommendation-service-v1.yaml`.

---

## 1. Mục tiêu và câu hỏi nghiên cứu

**Bài toán:** cho một ngữ cảnh (sản phẩm đang xem, giỏ hàng, hoặc người dùng có lịch sử), trả về K sản phẩm khách có khả năng quan tâm nhất. Không được trả sản phẩm ngừng bán hoặc hết hàng.

| Mã | Câu hỏi | Giả thuyết |
|---|---|---|
| RQ1 | Ở ngữ cảnh "thường mua cùng", luật kết hợp có tốt hơn popularity không? | Có, với sản phẩm nguồn đã xuất hiện trong giỏ train (warm). Với sản phẩm nguồn chưa có luật (cold), luật kết hợp không có gì để trả, nên kết quả lùi về popularity |
| RQ2 | Content-based có cải thiện được trường hợp sản phẩm nguồn cold không? | Có cải thiện so với popularity trên nhóm cold. Trên nhóm warm thì thấp hơn luật kết hợp, vì sản phẩm "mua kèm" thường **khác loại** với sản phẩm nguồn |
| RQ3 | Hybrid (luật → content → popularity) có ít nhất bằng thuật toán đơn lẻ tốt nhất trên toàn bộ tập không? | Có, vì mỗi tầng chỉ lấp chỗ trống của tầng trước |
| RQ4 | Ở ngữ cảnh cá nhân hóa (`HOME`), hồ sơ người dùng theo nội dung có tốt hơn popularity với user warm không? | Có, nhưng mức chênh nhỏ vì catalog chỉ khoảng 36 sản phẩm |

Kết quả được báo cáo **trung thực kể cả khi giả thuyết sai**. Popularity là baseline rất khó vượt trên catalog nhỏ.

---

## 2. Ngữ cảnh gợi ý

| Ngữ cảnh | `context_type` | Đầu vào | Vị trí hiển thị | Mức ưu tiên |
|---|---|---|---|---|
| Thường mua cùng | `FREQUENTLY_BOUGHT_TOGETHER` | 1 sản phẩm nguồn | Trang chi tiết `/sanpham/[slug]` (đã chạy) | **MUST**, ngữ cảnh chính của đánh giá |
| Gợi ý theo giỏ | `CART_RECOMMENDATION` | Tập sản phẩm trong giỏ | Trang giỏ hàng (API `POST /api/v1/recommendations/cart` đã có, chưa có giao diện) | SHOULD. Dùng chung thuật toán với FBT, nguồn là một tập |
| Gợi ý cho bạn | `HOME` | `userRef` hoặc `anonymousId` và lịch sử của họ | Trang chủ | COULD. Chỉ làm nếu kịp sau khi FBT đánh giá xong |

Một thuật toán có thể phục vụ nhiều ngữ cảnh. Điểm khác nhau là **đầu vào** (sản phẩm hay người dùng) và **cách đánh giá** (mục 7.4).

---

## 3. Dữ liệu đầu vào

Recommendation **không đọc bảng của service khác**. Mọi dữ liệu đều nằm trong schema `recommendation`.

| Bảng | Nguồn | Dùng cho |
|---|---|---|
| `catalog_products` | Sự kiện `product.reindex.requested` → gọi `index-document` của Catalog | Bộ lọc nghiệp vụ, đặc trưng content-based, thông tin hiển thị |
| `order_baskets` | Sự kiện `order.created` của Sales (variant đã đổi sang product) | Luật kết hợp, popularity, sự kiện `PURCHASE` |
| `user_interactions` | Storefront (`REAL`/`DEMO`) và bộ sinh synthetic (`SYNTHETIC`) | Popularity có trọng số, hồ sơ người dùng, nhãn đánh giá |
| `recommendation_requests`, `recommendation_items` | Chính service ghi khi trả gợi ý | Truy vết, tính CTR theo model |

**Mở rộng snapshot cho content-based:** hợp đồng `CatalogIndexDocument` đã có `gender`, `sportCodes`, `category`, `brand`, `productType` và `colorFamily` của từng variant, nhưng `build_snapshot` (`app/catalog.py`) hiện chỉ giữ `productType`, `categoryId`, `brandId` và giá. Giai đoạn 7 sẽ thêm `gender`, `sportCodes`, `colorFamilies` vào `ProductSnapshot` rồi chạy lại `backfill-read-models.ps1`. Không cần migration, vì `snapshot` là `jsonb`.

---

## 4. Trọng số sự kiện

Phản hồi ngầm (implicit feedback) có độ tin cậy khác nhau. Mua chắc chắn hơn thêm giỏ, thêm giỏ chắc chắn hơn xem.

| Sự kiện | Trọng số `w(e)` | Ghi chú |
|---|---|---|
| `VIEW` | 1 | Đã chống phồng số liệu: tối đa 1 lần/sản phẩm/10 phút mỗi trình duyệt |
| `RECOMMENDATION_CLICK` | 1 | Ngang với VIEW, vì click dẫn tới một lượt VIEW. Không cộng hai lần: VIEW phát sinh ngay sau một CLICK cùng sản phẩm (trong 60 giây) được bỏ khi tính điểm |
| `SEARCH_CLICK` | 1 | Như trên |
| `WISHLIST` | 2 | |
| `ADD_TO_CART` | 3 | |
| `PURCHASE` | 5 | Lấy từ `order_baskets` (lúc đặt đơn), không phải từ trình duyệt |
| `RECOMMENDATION_IMPRESSION`, `SEARCH_IMPRESSION` | 0 | Chỉ dùng để tính CTR. Được nhìn thấy không có nghĩa là quan tâm |

- Các trọng số này là **giả định nghiệp vụ**, chốt trước, không tinh chỉnh trên TEST. Chỉ được thử một bộ thay thế duy nhất (`VIEW=1, ADD_TO_CART=2, PURCHASE=3`) trên DEV để báo cáo độ nhạy.
- **Đơn bị hủy vẫn được tính là đã mua** (quyết định Giai đoạn 2: Sales chưa phát `order.cancelled` đúng envelope). Dữ liệu synthetic không có đơn hủy nên không ảnh hưởng tới đánh giá offline.

---

## 5. Thuật toán

Mọi thuật toán có chung giao diện: nhận ngữ cảnh, mốc thời gian `T` (chỉ được dùng sự kiện có `occurred_at < T`) và tập ứng viên, rồi trả về danh sách đã xếp hạng. Bộ lọc nghiệp vụ (mục 6) chạy **sau** khi tính điểm.

### 5.1. Random (cận dưới)

Xáo tập ứng viên bằng `random.Random(seed)` với seed cố định. Kỳ vọng Precision@K ≈ |R| / |C|, trong đó C là tập ứng viên. Dùng để chứng minh các thuật toán khác thật sự học được điều gì đó.

### 5.2. Popularity (baseline)

```
score(i) = Σ_{e ∈ E_train(i)} w(e) · exp(−λ · (T − t_e)),    λ = ln 2 / half_life
```

- `E_train(i)`: sự kiện của sản phẩm i trước mốc T.
- Biến thể chọn trên DEV: `half_life ∈ {∞ (không suy giảm), 7 ngày, 14 ngày}`.
- **Không dùng** cột `catalog_products.purchase_count` khi đánh giá offline: đó là số đếm toàn thời gian, sẽ làm rò dữ liệu tương lai vào train. Khi phục vụ thật, service vẫn có thể dùng cột này.
- Hòa điểm thì xếp theo `product_id`, để kết quả tất định.

### 5.3. Association rules (đang chạy)

Giữ nguyên `mine_association_rules` và `rank_rules` (`app/domain.py`): luật 1 → 1, đếm mọi cặp trong giỏ, lọc theo support, confidence, lift. Điểm = `confidence × lift`.

- Ngữ cảnh giỏ: với mỗi sản phẩm đích, lấy luật tốt nhất trong các sản phẩm nguồn (đã làm trong `rank_rules`).
- Lưới tham số chọn trên DEV: `min_support ∈ {0,005; 0,01; 0,02}`, `min_confidence ∈ {0,05; 0,10; 0,20}`, `min_lift = 1,0`. Tổng cộng 9 cấu hình.
- **Đánh giá thuần**: thiếu thì trả ít hơn K, không bù. Bản bù bằng popularity là một cấu hình riêng (mục 5.5), để tách được đóng góp của từng tầng.

### 5.4. Content-based

**Cách lấy đặc trưng: đề xuất cách B** (Mission, Giai đoạn 7): Recommendation tự tính từ snapshot của mình, không cần embedding.

| Cách | Ưu | Nhược | Kết luận |
|---|---|---|---|
| A. Search mở API "sản phẩm tương tự" (cosine trên `ai_search.text_embeddings`) | Ngữ nghĩa phong phú hơn | Phụ thuộc Search lúc phục vụ. Khó tái lập offline vì vector không nằm trong manifest. Cần Hải làm thêm API | Để làm hướng mở rộng, bàn với Hải |
| **B. Đặc trưng thuộc tính từ snapshot** | Không vượt ranh giới service, không tốn chi phí, giải thích được, tái lập được hoàn toàn | Bỏ qua mô tả văn bản | **Chọn** |
| C. Recommendation tự sinh embedding | Độc lập | Trùng việc với Search, tốn phí API, phải lưu thêm bảng vector | Loại |

**Biểu diễn sản phẩm** là một tập đặc trưng có tiền tố:

```
F(i) = {type:<productType>, cat:<categorySlug>, brand:<brandSlug>, gender:<gender>,
        sport:<code> (mỗi môn một phần tử), color:<colorFamily> (mỗi màu một phần tử), price:<khoảng giá>}
```

Khoảng giá chia theo tứ phân vị giá thấp nhất của các sản phẩm trong tập ứng viên (4 khoảng). Tính các ngưỡng từ ứng viên, không dùng dữ liệu hành vi, nên không gây rò dữ liệu.

**Độ tương đồng item–item:** Jaccard có trọng số theo nhóm đặc trưng

```
sim(i, j) = Σ_g α_g · J(F_g(i), F_g(j)) / Σ_g α_g,     J(A, B) = |A ∩ B| / |A ∪ B|  (J = 0 nếu cả hai rỗng)
```

Trọng số mặc định chốt trước: `sport = 3, gender = 2, type = 1, cat = 1, brand = 1, price = 1, color = 0,5`. Môn thể thao và giới tính được ưu tiên vì đó là hai tiêu chí khách lọc nhiều nhất với trang phục thể thao.

**Vấn đề "giống quá" ở ngữ cảnh FBT:** khách xem áo chạy bộ thường cần mua **kèm** quần, tất hoặc giày chạy bộ, chứ không phải một chiếc áo chạy bộ khác. Vì vậy có hai biến thể, chọn trên DEV:

- `content-similar`: dùng `sim(i, j)` như trên.
- `content-complement`: dùng `sim(i, j)` nhưng **bỏ nhóm `type` và `cat`**, và chỉ giữ ứng viên có `productType` **khác** sản phẩm nguồn. Kết quả là sản phẩm cùng môn, cùng giới tính, khác loại.

**Hồ sơ người dùng (HOME):** `p_u(f) = Σ_{e} w(e) · decay(e) · 1[f ∈ F(i_e)]`, chuẩn hóa về tổng bằng 1. Điểm của ứng viên j là `Σ_{f ∈ F(j)} p_u(f) / |F(j)|`. Sản phẩm user đã mua trong train bị loại khỏi danh sách.

### 5.5. Hybrid theo tầng (đề xuất cho phục vụ thật)

```
FBT/CART: association rules → content-complement → popularity
HOME:     user-profile content (nếu user warm) → popularity
```

Tầng sau chỉ lấp các vị trí còn trống. Mỗi item ghi `reason_code` theo tầng đã sinh ra nó: `ASSOCIATION_RULE`, `SIMILAR_CONTENT`, `POPULARITY`. Đây là mở rộng trực tiếp của `RecommendationService.recommend` hiện tại (đang là luật → popularity).

Hybrid trộn điểm (`α · content + (1 − α) · popularity`, α ∈ {0,25; 0,5; 0,75} chọn trên DEV) là phần **tùy chọn**. Chỉ làm nếu hybrid theo tầng không vượt được thuật toán đơn lẻ tốt nhất.

### 5.6. Lý do không dùng collaborative filtering

Catalog khoảng 36 sản phẩm, vài trăm user, dữ liệu thưa và phần lớn là synthetic. Matrix factorization hay ALS dễ overfit và không giải quyết được cold-start (`plan.md` 8.3). Có thể nhắc tới như hướng phát triển khi có dữ liệu thật đủ lớn.

---

## 6. Bộ lọc nghiệp vụ

Áp dụng giống nhau cho mọi thuật toán, cả khi phục vụ thật lẫn khi đánh giá:

1. `status = 'ACTIVE'`.
2. `available_quantity > 0`.
3. Bỏ sản phẩm nguồn (FBT, CART).
4. HOME: bỏ sản phẩm user đã mua trong train. Sản phẩm thể thao ít khi mua lặp lại trong 8 tuần, và gợi ý lại đồ đã mua làm Precision cao giả tạo.
5. Lấy dư ứng viên (`3K`) trước khi lọc để còn đủ K.

Khi đánh giá offline, bước 1–2 được thay bằng **tập ứng viên đóng băng** (mục 7.3), không đọc `catalog_products` hiện tại.

---

## 7. Protocol đánh giá offline

### 7.1. Dữ liệu

- Dữ liệu chính: **synthetic** do `scripts/generate_synthetic_interactions.py` sinh ở Giai đoạn 4. Có seed cố định, khoảng 8 tuần, chỉ dùng sản phẩm có trong catalog thật. Tham số bộ sinh chốt và commit **trước khi** chạy bất kỳ mô hình nào.
- Dữ liệu `REAL` và `DEMO` hiện quá ít. Chỉ dùng để thống kê mô tả (số sự kiện, CTR), không dùng để kết luận thuật toán nào tốt hơn.
- Đánh giá chạy **trên file** (`evidence/recommendation/`), không ghi dữ liệu synthetic vào Supabase dùng chung nếu nhóm chưa đồng ý.

**Yêu cầu với bộ sinh để so sánh công bằng.** Nếu khách ảo chỉ mua theo thuộc tính persona, content-based được lợi sẵn. Nếu chỉ mua theo cặp cố định, luật kết hợp được lợi sẵn. Vì vậy bộ sinh phải có cả ba cơ chế, với tỷ lệ chốt trước:

| Cơ chế | Mô tả | Thuật toán có lợi |
|---|---|---|
| Sở thích persona | Chọn sản phẩm khớp môn, giới tính, khoảng giá của persona | Content |
| Mua kèm bổ trợ | Khi mua loại X, có xác suất mua kèm loại Y cùng môn (ví dụ giày → tất, áo → quần) | Luật kết hợp, content-complement |
| Độ phổ biến | Một số sản phẩm "hot" được mọi persona chọn nhiều hơn | Popularity |
| Nhiễu | 20–30% sự kiện chọn ngẫu nhiên đều trên catalog | Không thuật toán nào |

Tỷ lệ và bảng cặp bổ trợ được ghi trong manifest. Ở phần giới hạn phải nói rõ: bộ sinh và content-based cùng dùng thuộc tính sản phẩm.

### 7.2. Chia theo thời gian

```
|-------- TRAIN: tuần 1–6 --------|-- DEV: tuần 7 --|-- TEST: tuần 8 --|
```

- Tuần tính theo UTC, nửa mở: tuần `n` là `[t0 + 7(n−1) ngày, t0 + 7n ngày)`, trong đó `t0` là mốc bắt đầu ghi trong manifest.
- **Chọn cấu hình:** huấn luyện trên tuần 1–6, đo trên tuần 7 (DEV).
- **Báo cáo:** huấn luyện lại cấu hình đã chọn trên tuần 1–7, đo trên tuần 8 (TEST), **chạy một lần**. Mọi thuật toán, kể cả baseline, đều theo cùng quy trình này.
- Mỗi lần chạy lưu `train_until`, `test_from`, `test_until` vào manifest và vào `recommendation_models.training_cutoff_at`.
- Unit test bắt buộc: không thuật toán nào dùng sự kiện có `occurred_at ≥ train_until`.

### 7.3. Tập ứng viên

- C = các sản phẩm `ACTIVE` và còn hàng tại `test_from`. Đóng băng thành danh sách `product_id` đã sắp xếp, lưu SHA-256 vào manifest.
- Mọi thuật toán xếp hạng **trên cùng C**. Sản phẩm liên quan không thuộc C bị bỏ khỏi R. Số lượng bị bỏ được báo cáo; với synthetic thì số này phải bằng 0.

### 7.4. Tác vụ và nhãn

**Tác vụ A — FBT/CART (đánh giá chính, trả lời RQ1–RQ3).** Đơn vị đánh giá là **đơn hàng trong cửa sổ test có ít nhất 2 sản phẩm**:

- Leave-one-in: với đơn có tập sản phẩm `B`, mỗi sản phẩm `s ∈ B` lần lượt làm nguồn, và `R = B \ {s}`. Một đơn có |B| sản phẩm sinh ra |B| trường hợp.
- Kết quả được tính trung bình theo trường hợp, sau đó trung bình theo đơn, để đơn nhiều sản phẩm không lấn át. Báo cáo cả hai con số.
- Nhãn nhị phân, vì mọi sản phẩm trong giỏ đều là `PURCHASE`.
- Tác vụ CART (dùng `B` thiếu một sản phẩm làm nguồn, sản phẩm còn lại làm R) chỉ chạy bổ sung nếu kịp, không thuộc bảng chính.

**Tác vụ B — HOME (trả lời RQ4, chỉ làm nếu làm ngữ cảnh HOME).** Đơn vị là **user** có ít nhất một sự kiện trong cửa sổ test:

- `R_u` = sản phẩm user `ADD_TO_CART` hoặc `PURCHASE` trong cửa sổ test.
- Bỏ user có `R_u` rỗng (chỉ xem, không thêm giỏ). Số user bị bỏ được báo cáo.
- NDCG dùng nhãn nhiều mức: `PURCHASE = 2`, `ADD_TO_CART = 1`; một sản phẩm có nhiều sự kiện thì lấy mức cao nhất. `VIEW` **không** phải sản phẩm liên quan, nên có gain 0. Cách này để Precision, Recall và NDCG dùng cùng một tập R.

### 7.5. Warm và cold

| Tác vụ | Warm | Cold |
|---|---|---|
| A (FBT) | Sản phẩm nguồn có trong ít nhất một giỏ train | Sản phẩm nguồn chưa có trong giỏ train nào |
| B (HOME) | User có ít nhất 1 sự kiện trọng số > 0 trong train | User không có sự kiện nào trong train. Mọi thuật toán cá nhân hóa đều lùi về popularity, nên chỉ báo cáo để minh họa |

Báo cáo số liệu riêng cho warm, cold và toàn bộ. Nếu một nhóm có ít hơn 30 trường hợp thì ghi chú "không đủ để kết luận".

### 7.6. Metric

K ∈ {5, 10}. Với C khoảng 36 sản phẩm, K = 10 đã là khoảng 28% catalog, nên **K = 5 là con số chính** và K = 10 là bổ sung.

| Metric | Định nghĩa |
|---|---|
| Precision@K | `|L_K ∩ R| / K` |
| Recall@K | `|L_K ∩ R| / |R|` |
| Hit Rate@K | `1` nếu `|L_K ∩ R| ≥ 1`, ngược lại `0` |
| NDCG@K | `DCG@K / IDCG@K`, với `DCG@K = Σ_{i=1..K} gain_i / log2(i + 1)` (gain tuyến tính, vị trí 1 chia `log2 2 = 1`) |
| Coverage@K | Số sản phẩm khác nhau xuất hiện trong mọi `L_K` / |C| |

- Định nghĩa DCG ở trên là biến thể thông dụng: gain tuyến tính, chia cho `log2(i + 1)`, giống `sklearn.metrics.ndcg_score`. Bài gốc của Järvelin và Kekäläinen (2002) không chia log ở các vị trí nhỏ hơn cơ số. Báo cáo ghi rõ biến thể đang dùng.
- Nếu thuật toán trả ít hơn K sản phẩm (luật kết hợp thuần), Precision@K vẫn chia cho K. Trả thiếu bị tính là sai.
- Unit test hàm metric bằng ví dụ tính tay ở Mission C.9 (`NDCG@5 = 0,478`).

### 7.7. Chọn cấu hình và kiểm định

- **Metric chọn cấu hình trên DEV:** NDCG@5 của tác vụ A. Hòa thì chọn cấu hình đơn giản hơn.
- Các lưới tham số được chốt ở mục 5. Không thêm cấu hình mới sau khi đã xem kết quả DEV. Nếu buộc phải thêm thì ghi vào mục 12 kèm lý do.
- **Khoảng tin cậy:** bootstrap 1.000 lần theo đơn vị đánh giá (đơn hàng hoặc user), seed cố định, báo cáo khoảng 95%.
- **So sánh hai thuật toán:** paired bootstrap trên hiệu số NDCG@5 theo từng đơn vị. Chỉ nói "A tốt hơn B" khi khoảng 95% của hiệu số không chứa 0.

### 7.8. Kết quả được lưu

File `evidence/recommendation/benchmark-<yyyymmdd>.json`, mỗi lần chạy một file:

```
dataset:   {path, sha256, generator_version, seed, data_source: "SYNTHETIC", t0}
split:     {train_until, dev_from, test_from, test_until}
candidates:{count, sha256}
git_commit, run_at, task, k_values
models:    [{algorithm, version, config, training_cutoff_at, training_event_checksum}]
results:   [{algorithm, segment: all|warm|cold, k, n, precision, recall, hit_rate, ndcg, coverage, ci95: {...}}]
```

Mỗi cấu hình ứng với một dòng `recommendation_models` (Giai đoạn 8). Bảng `evaluation_runs` cần thêm cột Hit Rate, NDCG và Coverage bằng migration `V3__...` (Giai đoạn 9).

---

## 8. Đo lường online (mô tả, không dùng để kết luận)

Khi đã có dữ liệu thật hoặc dữ liệu demo:

- **CTR theo model và theo vị trí:** `RECOMMENDATION_CLICK / RECOMMENDATION_IMPRESSION`, ghép theo `requestId` và vị trí.
- **Tỷ lệ thêm giỏ sau click:** `ADD_TO_CART` cùng sản phẩm trong vòng 30 phút sau click.
- Không có A/B test vì không đủ lưu lượng. Đây là giới hạn được nêu ở mục 10.

---

## 9. Thay đổi code dự kiến

| Giai đoạn | Thay đổi | File |
|---|---|---|
| 4 | Bộ sinh synthetic và manifest | `recommendation-service/scripts/generate_synthetic_interactions.py` |
| 6 | Popularity có trọng số và suy giảm, random | `app/domain.py` |
| 7 | Thêm `gender`, `sportCodes`, `colorFamilies` vào snapshot; Jaccard; content-complement; hồ sơ user | `app/schemas.py`, `app/catalog.py`, `app/domain.py` |
| 7 | Hybrid theo tầng trong `recommend` | `app/service.py` |
| 8 | `replace_rules` ghi `config`, `training_cutoff_at`, `training_event_checksum`; `record_response` ghi `latency_ms`, `user_ref` | `app/repository.py` |
| 9 | Metric HR, NDCG, Coverage; migration V3; script đánh giá | `app/domain.py`, `migrations/V3__*.sql`, `scripts/evaluate_offline.py` |

Hàm thuật toán là **hàm thuần** (nhận dữ liệu, trả danh sách), dùng chung cho service và script đánh giá. Nhờ vậy, con số trong báo cáo đo đúng code đang chạy thật.

---

## 10. Giới hạn

1. Dữ liệu đánh giá chủ yếu là synthetic. Kết quả chứng minh pipeline và phương pháp đánh giá đúng, **chưa** chứng minh hiệu quả kinh doanh.
2. Bộ sinh và content-based cùng dựa trên thuộc tính sản phẩm, nên content-based có thể được đánh giá cao hơn thực tế.
3. Catalog khoảng 36 sản phẩm, nên metric dao động lớn. Đây là lý do có khoảng tin cậy.
4. Đánh giá offline không thay được A/B test online.
5. Đơn bị hủy vẫn được tính là đã mua (mục 4).
6. Bản sao catalog trong Recommendation có độ trễ (eventual consistency). Sales vẫn kiểm tra tồn kho thật khi đặt hàng.
7. Content-based dùng thuộc tính, không dùng mô tả văn bản hay ảnh (cách B, mục 5.4).
8. Catalog hiện không lưu `productType`, `gender`, `sportCodes`, `colorFamily`. Dữ liệu offline lấy các trường này từ manifest import catalog. Content-based chỉ chạy được trên website sau khi Catalog sửa dữ liệu.
9. Để có sản phẩm cold trong TEST, `synthetic-v1` cho 12/36 sản phẩm ra mắt đầu tuần 8. Catalog lúc train chỉ có 24 sản phẩm, và 6 sản phẩm mới ngoài áo đấu đều là unisex.

---

## 11. Các điểm phối hợp đã tự quyết

Không chờ Dũng và Hải review. Các điểm dưới đây được chốt theo hướng **không phụ thuộc service khác**:

| # | Vấn đề | Quyết định |
|---|---|---|
| 1 | Cách lấy độ tương đồng nội dung | **Cách B** (thuộc tính từ snapshot). API "sản phẩm tương tự" của Search (cách A) chỉ ghi là hướng mở rộng, không nằm trong kế hoạch |
| 2 | Chatbot dùng "sản phẩm liên quan" | Ngoài phạm vi tài liệu này. Nếu sau này nối, chatbot gọi API FBT hiện có, không cần API mới |
| 3 | Ghi dữ liệu `SYNTHETIC` vào Supabase dùng chung | **Không ghi.** Toàn bộ đánh giá chạy trên file trong `evidence/recommendation/` |
| 4 | Bảng cặp loại sản phẩm bổ trợ cho bộ sinh | Quyền tự lập dựa trên `productType` và `sportCodes` của catalog thật. Bảng được commit cùng tham số bộ sinh, trước khi chạy mô hình |
| 5 | Hợp đồng sự kiện #3 (`items` là variant ID, Catalog gửi sự kiện mỏng) | Không chặn việc đánh giá. `app/enrichment.py` đã nhận được cả variant ID lẫn product ID, và worker tự gọi `index-document` khi sự kiện không kèm `product` |

---

## 12. Nhật ký thay đổi

| Ngày | Phiên bản | Thay đổi |
|---|---|---|
| 17/11/2026 | 0.1 | Bản nháp đầu tiên |
| 17/11/2026 | 1.0 | Chốt, bỏ vòng review; mục 11 chuyển từ câu hỏi thành quyết định |
| 17/11/2026 | 1.1 | Thêm giới hạn 8–9 sau khi sinh `synthetic-v1` (không đổi protocol) |
