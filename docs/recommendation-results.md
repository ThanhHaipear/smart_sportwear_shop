# Kết quả đánh giá offline của Recommendation

> **Tự sinh** bởi `recommendation-service/scripts/render_results_table.py`, không sửa tay.
> Nguồn: `evidence/recommendation/synthetic-v1-test-final-20261117T082418Z.json` (SHA-256 `f96b513b375e9ed6…`), split `TEST`, dữ liệu `synthetic-v1` (synthetic), commit `b4f44506`.
> Huấn luyện trên dữ liệu trước `2026-10-20T00:00:00Z`, đánh giá `2026-10-20T00:00:00Z` → `2026-10-27T00:00:00Z`, 36 sản phẩm ứng viên.
> Protocol: `docs/recommendation-design.md` mục 7; cấu hình chọn trên DEV, TEST chạy một lần.

Ký hiệu: P = Precision, R = Recall, HR = Hit Rate; NDCG gain tuyến tính, chia `log2(i + 1)`. CI 95% là bootstrap 1.000 lần theo đơn hàng (FBT) hoặc user (HOME).

## 1. Thường mua cùng (FREQUENTLY_BOUGHT_TOGETHER)

Mỗi sản phẩm của một đơn có ≥ 2 sản phẩm trong tuần đánh giá làm nguồn một lần; đáp án là các sản phẩm còn lại của đơn. 145 ca: 106 warm (nguồn đã có trong giỏ train), 39 cold (sản phẩm mới).

### 1.1. Tất cả (145 ca)

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |
|---|---|---|---|---|---|---|---|---|---|
| Random | 0,072 | 0,191 | 0,338 | 0,130 (0,095–0,165) | 0,057 | 0,314 | 0,490 | 0,174 | 1,000 |
| Popularity | 0,099 | 0,297 | 0,379 | 0,230 (0,154–0,316) | 0,083 | 0,494 | 0,662 | 0,300 | 0,167 |
| Luật kết hợp (thuần) | 0,113 | 0,325 | 0,428 | 0,270 (0,191–0,361) | 0,061 | 0,349 | 0,455 | 0,280 | 0,639 |
| Luật kết hợp → popularity | 0,131 | 0,378 | 0,497 | 0,309 (0,230–0,397) | 0,095 | 0,560 | 0,738 | 0,374 | 0,639 |
| Content (complement) | 0,143 | 0,404 | 0,572 | 0,272 (0,214–0,334) | 0,110 | 0,621 | 0,786 | 0,351 | 1,000 |
| **Hybrid** luật → content → popularity | 0,163 | 0,466 | 0,621 | 0,350 (0,280–0,428) | 0,117 | 0,668 | 0,807 | 0,423 | 0,972 |

### 1.2. Warm (106 ca)

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |
|---|---|---|---|---|---|---|---|---|---|
| Random | 0,072 | 0,201 | 0,340 | 0,133 (0,096–0,175) | 0,058 | 0,330 | 0,500 | 0,179 | 1,000 |
| Popularity | 0,119 | 0,355 | 0,453 | 0,273 (0,180–0,365) | 0,086 | 0,503 | 0,660 | 0,326 | 0,167 |
| Luật kết hợp (thuần) | 0,155 | 0,445 | 0,585 | 0,370 (0,281–0,473) | 0,083 | 0,478 | 0,623 | 0,383 | 0,639 |
| Luật kết hợp → popularity | 0,162 | 0,467 | 0,613 | 0,381 (0,293–0,484) | 0,103 | 0,593 | 0,764 | 0,427 | 0,639 |
| Content (complement) | 0,138 | 0,388 | 0,538 | 0,278 (0,199–0,361) | 0,110 | 0,631 | 0,774 | 0,366 | 0,972 |
| **Hybrid** luật → content → popularity | 0,164 | 0,473 | 0,604 | 0,384 (0,291–0,490) | 0,120 | 0,696 | 0,802 | 0,464 | 0,833 |

### 1.3. Cold (39 ca)

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |
|---|---|---|---|---|---|---|---|---|---|
| Random | 0,072 | 0,165 | 0,333 | 0,119 (0,065–0,177) | 0,054 | 0,271 | 0,462 | 0,158 | 1,000 |
| Popularity | 0,046 | 0,137 | 0,179 | 0,114 (0,034–0,221) | 0,074 | 0,470 | 0,667 | 0,230 | 0,139 |
| Luật kết hợp (thuần) | 0,000 | 0,000 | 0,000 | 0,000 (0,000–0,000) | 0,000 | 0,000 | 0,000 | 0,000 | 0,000 |
| Luật kết hợp → popularity | 0,046 | 0,137 | 0,179 | 0,114 (0,034–0,221) | 0,074 | 0,470 | 0,667 | 0,230 | 0,139 |
| Content (complement) | 0,159 | 0,447 | 0,667 | 0,257 (0,182–0,322) | 0,108 | 0,592 | 0,821 | 0,311 | 0,667 |
| **Hybrid** luật → content → popularity | 0,159 | 0,447 | 0,667 | 0,257 (0,182–0,322) | 0,108 | 0,592 | 0,821 | 0,311 | 0,667 |

## 2. Gợi ý cho bạn (HOME)

Đáp án: sản phẩm user thêm giỏ (1) hoặc mua (2) trong tuần đánh giá. 156 user: 117 warm, 39 cold (chưa có lịch sử).

### 2.1. Tất cả (156 user)

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |
|---|---|---|---|---|---|---|---|---|---|
| Random | 0,050 | 0,130 | 0,244 | 0,084 (0,058–0,114) | 0,048 | 0,259 | 0,429 | 0,132 | 1,000 |
| Popularity | 0,081 | 0,204 | 0,301 | 0,157 (0,114–0,202) | 0,076 | 0,382 | 0,558 | 0,225 | 0,250 |
| Content (hồ sơ user) | 0,094 | 0,239 | 0,365 | 0,176 (0,135–0,221) | 0,070 | 0,377 | 0,487 | 0,225 | 1,000 |
| **Hybrid** hồ sơ → popularity | 0,109 | 0,283 | 0,429 | 0,206 (0,166–0,250) | 0,089 | 0,477 | 0,641 | 0,277 | 1,000 |

### 2.2. Warm (117 user)

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |
|---|---|---|---|---|---|---|---|---|---|
| Random | 0,056 | 0,148 | 0,274 | 0,097 (0,065–0,136) | 0,054 | 0,296 | 0,479 | 0,151 | 1,000 |
| Popularity | 0,087 | 0,214 | 0,316 | 0,168 (0,116–0,219) | 0,075 | 0,376 | 0,538 | 0,231 | 0,250 |
| Content (hồ sơ user) | 0,125 | 0,318 | 0,487 | 0,235 (0,182–0,286) | 0,093 | 0,503 | 0,650 | 0,300 | 1,000 |
| **Hybrid** hồ sơ → popularity | 0,125 | 0,318 | 0,487 | 0,235 (0,182–0,286) | 0,093 | 0,503 | 0,650 | 0,300 | 1,000 |

### 2.3. Cold (39 user)

| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |
|---|---|---|---|---|---|---|---|---|---|
| Random | 0,031 | 0,074 | 0,154 | 0,045 (0,015–0,086) | 0,031 | 0,146 | 0,282 | 0,071 | 1,000 |
| Popularity | 0,061 | 0,176 | 0,256 | 0,122 (0,051–0,206) | 0,077 | 0,401 | 0,615 | 0,208 | 0,139 |
| Content (hồ sơ user) | 0,000 | 0,000 | 0,000 | 0,000 (0,000–0,000) | 0,000 | 0,000 | 0,000 | 0,000 | 0,000 |
| **Hybrid** hồ sơ → popularity | 0,061 | 0,176 | 0,256 | 0,122 (0,051–0,206) | 0,077 | 0,401 | 0,615 | 0,208 | 0,139 |

## 3. So sánh đăng ký trước (NDCG@5, paired bootstrap)

| Mã | Câu hỏi | Tác vụ / nhóm | Trái − Phải | Hiệu | CI 95% | Kết luận |
|---|---|---|---|---|---|---|
| C1 | RQ1 | FBT / warm (n=106) | Luật kết hợp (thuần) − Popularity | 0,097 | 0,013 – 0,182 | Trái tốt hơn |
| C2 | RQ1 | FBT / tất cả (n=145) | Luật kết hợp → popularity − Popularity | 0,079 | 0,021 – 0,138 | Trái tốt hơn |
| C3 | RQ2 | FBT / cold (n=39) | Content (complement) − Popularity | 0,143 | -0,003 – 0,267 | Chưa có khác biệt có ý nghĩa |
| C4 | RQ2 | FBT / cold (n=39) | Hybrid luật → content → popularity − Luật kết hợp → popularity | 0,143 | -0,003 – 0,267 | Chưa có khác biệt có ý nghĩa |
| C5 | RQ3 | FBT / tất cả (n=145) | Hybrid luật → content → popularity − Luật kết hợp (thuần) | 0,079 | 0,051 – 0,112 | Trái tốt hơn |
| C6 | RQ3 | FBT / tất cả (n=145) | Hybrid luật → content → popularity − Content (complement) | 0,077 | 0,015 – 0,146 | Trái tốt hơn |
| C7 | RQ3 | FBT / tất cả (n=145) | Hybrid luật → content → popularity − Popularity | 0,119 | 0,041 – 0,196 | Trái tốt hơn |
| C8 | RQ4 | HOME / warm (n=117) | Content (hồ sơ user) − Popularity | 0,067 | 0,007 – 0,130 | Trái tốt hơn |
| C9 | RQ4 | HOME / tất cả (n=156) | Hybrid hồ sơ → popularity − Popularity | 0,050 | 0,000 – 0,097 | Trái tốt hơn |

## 4. Giới hạn

- Dữ liệu hành vi là synthetic; bộ sinh và content-based cùng dựa trên thuộc tính sản phẩm.
- Catalog nhỏ (36 sản phẩm), nhóm cold chỉ 39 ca: khoảng tin cậy rộng.
- 9 so sánh không hiệu chỉnh đa so sánh.
- Đánh giá offline, chưa có A/B test online.
