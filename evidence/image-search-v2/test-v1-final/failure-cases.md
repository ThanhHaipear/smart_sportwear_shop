# HAI-08 Image Search Failure Analysis

## Kết luận

TEST v1 không đạt acceptance. Báo cáo này chỉ phân tích `ranked-results.json` đã khóa; không gọi lại endpoint, không đổi threshold và không dùng TEST v1 để tune.

Nguyên nhân chính là tổ hợp của ba vấn đề: threshold chọn từ DEV synthetic không chuyển được sang ảnh thật; ranking theo category còn yếu; và một phần TEST dùng category nguồn rộng nên chủ thể/nhãn relevance chưa đủ sát sản phẩm catalog.

## Tổng hợp định lượng

| Chỉ số | Kết quả | Diễn giải |
|---|---:|---|
| Acceptance Recall@5 | 1.67% | Sau threshold đã khóa |
| Acceptance category accuracy | 4.00% | Sau threshold đã khóa |
| Raw top-1 relevant | 59.00% | Chẩn đoán trước threshold |
| Raw top-5 hit | 79.00% | Có ít nhất một relevant product trong top 5 |
| Raw top-10 hit | 84.00% | Có ít nhất một relevant product trong top 10 |
| Raw top-1 category | 67.00% | Category của kết quả đầu tiên |
| Positive vượt threshold ở top 1 | 4/100 | Threshold `0.71233916` |
| Unknown rejection | 100.00% | 20/20 unknown bị loại |

Positive top-score median là `0.59598`, P95 là `0.70537`, vẫn thấp hơn threshold `0.71233916`; unknown top-score lớn nhất là `0.60379`. Threshold giải thích phần lớn retrieval failure, nhưng raw top-5 hit 79% và raw category 67% cho thấy chỉ hạ threshold vẫn không đạt mục tiêu.

## Theo nhóm

| Nhóm | Raw top-1 relevant | Raw hit@5 | Raw hit@10 | Raw category | Top-1 vượt threshold |
|---|---:|---:|---:|---:|---:|
| tops | 8/20 | 18/20 | 18/20 | 14/20 | 2/20 |
| bottoms | 12/20 | 14/20 | 17/20 | 14/20 | 0/20 |
| uniforms | 16/20 | 20/20 | 20/20 | 16/20 | 0/20 |
| shoes | 10/20 | 13/20 | 13/20 | 10/20 | 1/20 |
| accessories | 13/20 | 14/20 | 16/20 | 13/20 | 1/20 |

Uniforms có raw hit@5 tốt nhất nhưng gần như toàn bộ bị threshold loại. Shoes và accessories có ranking yếu hơn; bottoms có nhiều ảnh người/toàn cảnh nên tín hiệu quần bị phân tán. Tops lẫn ảnh sản phẩm, người mặc và cảnh sự kiện. Contact-sheet review cũng phát hiện một số query không lấy sản phẩm làm chủ thể chính.

## Confusion matrix raw top-1

Hàng là nhóm kỳ vọng, cột là category của top-1 trước threshold.

| Expected \ Predicted | tops | bottoms | uniforms | shoes | accessories |
|---|---:|---:|---:|---:|---:|
| tops | 14 | 3 | 3 | 0 | 0 |
| bottoms | 2 | 14 | 4 | 0 | 0 |
| uniforms | 3 | 0 | 16 | 0 | 1 |
| shoes | 4 | 5 | 1 | 10 | 0 |
| accessories | 4 | 0 | 3 | 0 | 13 |

## Failure taxonomy

| Loại lỗi | Số query | Ý nghĩa |
|---|---:|---|
| `LOCKED_THRESHOLD_REJECTED_TOP1` | 96 | Top-1 không vượt threshold DEV đã khóa. |
| `NO_RELEVANT_PRODUCT_IN_RAW_TOP5` | 21 | Không có relevant product trong năm kết quả thô đầu. |
| `RELEVANT_RESULT_BELOW_THRESHOLD` | 77 | Có relevant product trong top 5 nhưng bị threshold loại. |
| `TOP1_CATEGORY_CONFUSION` | 33 | Top-1 thuộc category khác nhóm kỳ vọng. |

## Case đại diện

| Query | Nhóm/nguồn | Expected | Top-1 | Score | Relevant rank đầu | Nhận xét |
|---|---|---|---|---:|---:|---|
| `test-004` | tops / Sweatshirts | Áo chạy bộ dài tay Uni Cloud, Áo giữ nhiệt bóng đá Uni Core, Áo khoác chạy bộ nữ Violet Air, … | Bộ bóng đá nam Black Gold (uniforms) | 0.52550 | 13 | LOCKED_THRESHOLD_REJECTED_TOP1, TOP1_CATEGORY_CONFUSION, NO_RELEVANT_PRODUCT_IN_RAW_TOP5 |
| `test-013` | tops / Sports T-shirts | Áo chạy bộ nam Aero Navy, Áo tank chạy bộ nữ Coral Flow, Áo chạy bộ dài tay Uni Cloud, … | Áo chạy bộ nam Aero Navy (tops) | 0.68811 | 1 | LOCKED_THRESHOLD_REJECTED_TOP1, RELEVANT_RESULT_BELOW_THRESHOLD |
| `test-039` | bottoms / Shorts (sports clothing) | Quần short chạy bộ nam Swift Black, Quần bóng đá nam Royal Kick, Quần tập bóng đá nữ Black Drive | Quần legging chạy bộ nữ Navy Flex (bottoms) | 0.58482 | 8 | LOCKED_THRESHOLD_REJECTED_TOP1, NO_RELEVANT_PRODUCT_IN_RAW_TOP5 |
| `test-022` | bottoms / Sweatpants | Quần jogger thể thao Uni Motion, Quần track chạy bộ Uni Forest | Quần jogger thể thao Uni Motion (bottoms) | 0.69980 | 1 | LOCKED_THRESHOLD_REJECTED_TOP1, RELEVANT_RESULT_BELOW_THRESHOLD |
| `test-046` | uniforms / Association football players | Bộ bóng đá nam Crimson Unity, Bộ bóng đá nữ Royal Wave, Bộ bóng đá Uni Forest Line, … | Bộ bóng đá nam Crimson Unity (uniforms) | 0.67579 | 1 | LOCKED_THRESHOLD_REJECTED_TOP1, RELEVANT_RESULT_BELOW_THRESHOLD |
| `test-067` | shoes / Association football boots | Giày đá bóng nam Turf Shadow, Giày đá bóng nữ Turf Spark, Giày futsal Uni Red Court | Quần jogger thể thao Uni Motion (bottoms) | 0.50264 | N/A | LOCKED_THRESHOLD_REJECTED_TOP1, TOP1_CATEGORY_CONFUSION, NO_RELEVANT_PRODUCT_IN_RAW_TOP5 |
| `test-065` | shoes / Running shoes | Giày chạy bộ nam Cobalt Ride, Giày chạy bộ nữ Coral Cloud, Giày chạy bộ Uni Pure Step | Giày chạy bộ nam Cobalt Ride (shoes) | 0.69941 | 1 | LOCKED_THRESHOLD_REJECTED_TOP1, RELEVANT_RESULT_BELOW_THRESHOLD |
| `test-086` | accessories / Shin guards | Ốp bảo vệ ống đồng Red Guard | Bộ bóng đá nam Crimson Unity (uniforms) | 0.58193 | 8 | LOCKED_THRESHOLD_REJECTED_TOP1, TOP1_CATEGORY_CONFUSION, NO_RELEVANT_PRODUCT_IN_RAW_TOP5 |
| `test-088` | accessories / Water bottles | Bình nước thể thao Black Flow 750ml | Bình nước thể thao Black Flow 750ml (accessories) | 0.71124 | 1 | LOCKED_THRESHOLD_REJECTED_TOP1, RELEVANT_RESULT_BELOW_THRESHOLD |

## Review hình ảnh và nhãn

- Tops: `test-003`, `test-010`, `test-011`, `test-014` và `test-015` là cảnh có nhiều người/vật thể hoặc áo chiếm diện tích nhỏ; `test-001`, `test-002`, `test-004`, `test-013` gần với ảnh sản phẩm hơn.
- Bottoms: nhiều query là toàn thân hoặc hoạt động thể thao (`test-021`, `test-024`, `test-026`, `test-030`–`test-040`), khiến quần không phải chủ thể duy nhất; một số crop eo/quần (`test-023`, `test-033`, `test-035`) lại khác mạnh ảnh catalog nền sạch.
- Uniforms: ảnh bóng rổ, bóng chày, bóng chuyền và cheerleading được gán relevant tới catalog đồng phục bóng đá. Đây là category-level substitute hợp protocol hiện tại nhưng tạo ground truth rộng và không đồng nhất về môn thể thao.
- Shoes: có ảnh giày sản phẩm rõ (`test-064`, `test-068`, `test-069`, `test-071`–`test-075`, `test-078`–`test-080`) nhưng cũng có cảnh lịch sử/cửa hàng/người mang giày nhỏ (`test-061`, `test-063`, `test-067`, `test-070`, `test-076`, `test-077`).
- Accessories: vật thể thường nhỏ hoặc ở trên người; bình nước và wristband có hình dáng gần nhiều vật dụng phổ thông. Nhãn subtype tốt hơn category-level nhưng catalog chỉ có một sản phẩm relevant cho mỗi subtype.
- Color: N/A. TEST v1 không có expected color labels nên HAI-08.4 không thể tính confusion màu một cách hợp lệ; không suy đoán màu hậu nghiệm.

## Raw candidates và grouped Commerce

Ranked output đã lưu chứa kết quả sau khi gom theo product và mỗi query có tối đa một dòng/product; không có raw image-level candidates. Vì vậy có thể xác nhận invariant không trùng product, nhưng không thể tính quality delta raw-vs-grouped trên TEST v1 mà không gọi lại endpoint hoặc bổ sung telemetry hậu nghiệm. HAI-08 ghi nhận mục này là `N/A_NOT_RECORDED`; runner v2 phải lưu cả hai tầng trước khi mở TEST v2.

## So sánh baseline

| Run | Model/dataset | Recall@5 | Category | Kết luận |
|---|---|---:|---:|---|
| Voyage Phase-0 | `voyage-multimodal-3.5`, 4 alternate catalog views, 2 category | 100% | 100% | Feasibility-only, catalog-adjacent, không so trực tiếp |
| Legacy Phase-10 | 100 synthetic query, chỉ tops/bottoms/shoes | 100% | 100% | Model không được ghi trong report; không có uniforms/accessories/unknown |
| SigLIP2 DEV v1 | 50 catalog-derived positive + 5 unknown | 100% | 100% | Tuning-only, synthetic |
| SigLIP2 TEST v1 | 100 ảnh độc lập + 20 unknown | 1.67% | 4% | Final acceptance FAILED |

Các con số legacy không chứng minh Voyage tốt hơn SigLIP2 vì khác catalog, ground truth, số mẫu và mức độc lập. Không chạy Voyage hậu nghiệm trên TEST v1 để chọn model.

## Synthetic so với ảnh độc lập

DEV v1 biến đổi trực tiếp ảnh catalog nên giữ hình dáng, bố cục và phong cách nền của catalog; TEST v1 có người mặc, nhiều vật thể, nền phức tạp, subject nhỏ, ảnh lịch sử và nén camera. Chênh lệch 100% → 1.67% Recall@5 là bằng chứng DEV v1 đánh giá transformation robustness chứ không đại diện domain generalization.

## Đề xuất không sửa TEST v1

1. Giữ TEST v1 và kết quả FAILED bất biến; public rollout tiếp tục tắt.
2. Tạo DEV v2 từ ảnh thực độc lập, cân bằng query-focus/context và có nhãn sport/subtype rõ; dùng DEV v2 để thử threshold, crop/segmentation và category-aware retrieval.
3. Bổ sung runner v2 lưu raw image candidates trước grouping và grouped product results để đo ablation hợp lệ.
4. Rà lại taxonomy uniforms theo môn thể thao; thêm nhãn `queryFocus` và `visualDifficulty`; chỉ gán color khi reviewer đồng thuận.
5. Sau khi khóa pipeline bằng DEV v2, tạo TEST v2 mới, độc lập và khóa protocol trước thu thập; không chạy lại TEST v1 như một acceptance mới.
6. Chỉ mở rollout khi TEST version mới đạt Recall@5, category accuracy, leakage và latency gates.

## Trạng thái HAI-08

HAI-08.1, HAI-08.3, HAI-08.5, HAI-08.6, HAI-08.7 và HAI-08.8 hoàn tất. HAI-08.2 được kết luận `N/A_NOT_RECORDED` có bằng chứng; HAI-08.4 là `N/A_MISSING_EXPECTED_COLOR_LABELS`. Không có phép đo nào được bịa hoặc suy ra từ việc chạy lại TEST.
