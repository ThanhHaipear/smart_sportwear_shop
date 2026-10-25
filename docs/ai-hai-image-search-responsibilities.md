# Bảng tiến độ AI của Lê Thanh Hải

## 1. Mục tiêu cuối cùng

Hải phụ trách nhánh **dữ liệu hình ảnh và tìm kiếm sản phẩm bằng hình ảnh**:

```text
Ảnh catalog có nguồn và checksum
→ Cloudinary versioned original
→ SigLIP2 image embedding 768 chiều
→ pgvector trong schema ai_retrieval
→ exact cosine search
→ gom kết quả theo sản phẩm
→ Spring Boot lọc và bổ sung dữ liệu Commerce
→ Storefront hiển thị kết quả
→ đánh giá trên DEV/TEST bằng Precision@K và Recall@K
```

Sau mỗi lần hoàn thành một mục, đổi `[ ]` thành `[x]`, cập nhật trạng thái và gắn đường dẫn đến bằng chứng tương ứng.

## 2. Quy ước trạng thái

| Trạng thái | Ý nghĩa |
|---|---|
| `DONE` | Đã có code/dữ liệu và có bằng chứng kiểm tra |
| `VERIFY` | Đã có nhưng cần chạy lại trên commit và môi trường hiện tại |
| `IN PROGRESS` | Đang thực hiện |
| `TODO` | Chưa bắt đầu |
| `BLOCKED` | Chưa thể tiếp tục vì thiếu đầu vào hoặc quyết định |

Phần trăm trong bảng chỉ là ước lượng quản lý công việc, không phải điểm đánh giá học thuật.

## 3. Tổng quan tiến độ

| Mã | Hạng mục | Trạng thái | Tiến độ | Ưu tiên | Phụ thuộc |
|---|---|---:|---:|---:|---|
| HAI-01 | Kiểm kê và khóa catalog ảnh V2 | `DONE` | 100% | P0 | Không |
| HAI-02 | Xác nhận image embedding V2 | `DONE` | 100% | P0 | HAI-01 |
| HAI-03 | Chuyển API tìm ảnh sang SigLIP2 V2 | `DONE` | 100% | P0 | HAI-02 |
| HAI-04 | Kiểm thử API và repository V2 | `DONE` | 100% | P0 | HAI-03 |
| HAI-05 | Xây dựng tập ảnh DEV | `DONE` | 100% | P0 | HAI-01 |
| HAI-06 | Xây dựng tập ảnh TEST độc lập | `DONE` | 100% | P0 | HAI-01, HAI-05 |
| HAI-07 | Viết runner và chạy đánh giá | `DONE` | 100% | P0 | HAI-03, HAI-05, HAI-06 |
| HAI-08 | Phân tích lỗi và so sánh baseline | `DONE` | 100% | P1 | HAI-07 |
| HAI-09 | Hardening, rollout và rollback | `IN PROGRESS` | 80% | P1 | HAI-03, HAI-04 |
| HAI-10 | Hoàn thiện nội dung báo cáo | `TODO` | 20% | P1 | HAI-07, HAI-08 |

## 4. Những phần đã có, không làm lại

| Thành phần | Trạng thái | Bằng chứng hiện tại | Việc Hải còn phải làm |
|---|---|---|---|
| Catalog pilot | `DONE` | 30 sản phẩm, 148 variant, 60 ảnh; dataset `smart-sportswear-pilot:1.1.0` đã `FROZEN` | Chạy lại validation và lưu report |
| Catalog World Cup | `DONE` | 6 sản phẩm, 30 variant, 21 ảnh; dataset `world-cup-2026-official-store-reference:1.0.0` đã `FROZEN` | Chạy lại validation và lưu report |
| Cloudinary metadata | `DONE` | 81 ảnh có `public_id`, asset version, URL, checksum và provenance | Đối chiếu DB với manifest |
| Schema vector ảnh | `DONE` | `ai_retrieval.product_image_embeddings`, vector 768 chiều trong V6 | Không tạo bảng vector mới |
| Model registry | `DONE` | V7 seed `google/siglip2-base-patch16-224` với revision cố định | Model mới phải tạo version mới |
| SigLIP2 local | `DONE` | `Siglip2ImageEmbedder` đã có và L2-normalize vector | Dùng đúng revision cho query embedding |
| Worker backfill | `DONE` | Có retry, stale detection, checksum verification và idempotent skip | Chạy lại và lưu bằng chứng |
| Exact cosine SQL | `DONE` | Có `ai_retrieval.search_product_images_exact(...)` | Tích hợp vào API V2 |
| Embedding coverage | `DONE` | Đã xác minh `81/81 READY`; job `2472dc24-92cd-4a77-a95c-439267f38b2b` chạy lặp và skip đủ 81 | Theo dõi khi catalog đổi |
| Storefront visual search | `DONE` về UI | Có upload/camera/crop/loading/error/result dialog | Kiểm tra lại với API V2 |
| Visual search Voyage | `REFERENCE` | Là pipeline/schema legacy | Không dùng làm nguồn chuẩn V2 |

## 5. Thứ tự thực hiện

| Bước | Công việc | Kết quả phải có |
|---:|---|---|
| 1 | HAI-01 kiểm kê catalog ảnh | Validation report và catalog snapshot checksum |
| 2 | HAI-02 chạy lại embedding verification | `81/81 READY` và idempotency report |
| 3 | HAI-03 chuyển API sang V2 | Image search chạy bằng SigLIP2/`ai_retrieval` |
| 4 | HAI-04 bổ sung test | Unit/integration/frontend QA đạt |
| 5 | HAI-05 tạo DEV | DEV manifest, labels và checksum |
| 6 | Tune trên DEV | Chốt threshold, candidate limit và grouping |
| 7 | HAI-06 thu thập và khóa TEST | TEST độc lập, không leakage |
| 8 | HAI-07 chạy benchmark cuối | Metrics và ranked output |
| 9 | HAI-08 phân tích failure case | Báo cáo lỗi và baseline comparison |
| 10 | HAI-09 và HAI-10 | Runbook, rollback và nội dung báo cáo |

## 6. Bảng tiến độ chi tiết

### HAI-01 Kiểm kê và khóa catalog ảnh V2

**Trạng thái:** `DONE`
**Mục tiêu:** chứng minh catalog dùng cho AI có thể tái lập và không thiếu metadata.

| Xong | ID | Việc cần làm | Đầu ra/Bằng chứng | Điều kiện đạt |
|---:|---|---|---|---|
| [x] | HAI-01.1 | Đối chiếu 60 ảnh pilot với manifest và `assets.sha256` | `evidence/image-search/catalog-validation-2026-10-23.json` | Đủ 60 ảnh, không sai checksum |
| [x] | HAI-01.2 | Đối chiếu 21 ảnh World Cup với manifest/import result | `evidence/image-search/catalog-validation-2026-10-23.json` | Đủ 21 ảnh, không sai checksum |
| [x] | HAI-01.3 | Đối chiếu 81 `product_images` trong DB với manifest | `evidence/image-search/catalog-validation-2026-10-23.json` | ID, version, URL và checksum khớp |
| [x] | HAI-01.4 | Kiểm tra ảnh chính của sản phẩm `ACTIVE` | `evidence/image-search/catalog-validation-2026-10-23.json` | 36/36 sản phẩm có đúng một ảnh chính |
| [x] | HAI-01.5 | Kiểm tra width, height, format, bytes, source và license | `evidence/image-search/catalog-validation-2026-10-23.json` | Không thiếu metadata bắt buộc |
| [x] | HAI-01.6 | Tạo checksum catalog snapshot dùng cho evaluation | Snapshot `image-catalog-v2-2026-10-23` | Có version, Git commit và SHA-256 |
| [x] | HAI-01.7 | Review thủ công mapping product/color | Validation report, mục `manualMappingReview` | Không có ảnh gán nhầm |

**Điều kiện đóng:** 36/36 sản phẩm và 81/81 ảnh hợp lệ, có report máy đọc được và catalog snapshot đã khóa.

### HAI-02 Xác nhận image embedding V2

**Trạng thái:** `DONE`

### Lệnh chạy

```powershell
.\backfill-product-embeddings.ps1 -Modality image -CoverageOnly
.\backfill-product-embeddings.ps1 -Modality image -Verify
.\backfill-product-embeddings.ps1 -Modality image -Verify
```

Lần chạy cuối chứng minh idempotency và phải skip các vector còn hợp lệ.

| Xong | ID | Việc cần làm | Đầu ra/Bằng chứng | Điều kiện đạt |
|---:|---|---|---|---|
| [x] | HAI-02.1 | Chạy coverage trước backfill | `evidence/image-search/embedding-verification-2026-10-23.json` | Ghi total/ready/missing/failed |
| [x] | HAI-02.2 | Xác nhận active model SigLIP2 V7 | Embedding verification report | Đúng revision, 768 chiều, cosine |
| [x] | HAI-02.3 | Chạy image backfill/verify | Job `2472dc24-92cd-4a77-a95c-439267f38b2b` | Không còn lỗi chưa xử lý |
| [x] | HAI-02.4 | Xác nhận coverage cuối | Embedding verification report | `81/81 READY` |
| [x] | HAI-02.5 | Chạy lại kiểm tra idempotency | Job report | 81 item hợp lệ được skip |
| [x] | HAI-02.6 | Kiểm tra norm và dimension | Verification report | Không có vector sai |
| [x] | HAI-02.7 | Kiểm tra image hash/asset version | Verification report | 100% khớp ảnh hiện hành |
| [x] | HAI-02.8 | Test checksum/MIME/kích thước sai | Python test suites | Worker fail an toàn |

**Điều kiện đóng:** coverage 100%, chạy lặp idempotent, report gắn catalog snapshot, model revision và Git commit.

### HAI-03 Chuyển API tìm ảnh sang SigLIP2 V2

**Trạng thái:** `DONE`

### Quyết định cho pilot

- Giữ `POST /api/v1/products/search-by-image` và contract storefront hiện tại.
- Image service tạo query vector bằng đúng SigLIP2 revision V7.
- Repository gọi `ai_retrieval.search_product_images_exact(...)`.
- Spring Boot lọc/enrich giá, tồn và trạng thái Commerce hiện thời.
- Không tạo thêm bảng vector/model registry.
- Voyage chỉ được giữ làm baseline/rollback có nhãn legacy.

| Xong | ID | Việc cần làm | Bằng chứng | Điều kiện đạt |
|---:|---|---|---|---|
| [x] | HAI-03.1 | Audit code path hiện tại | Repository và config đã chuyển; Voyage còn nhãn legacy | Chỉ rõ đoạn còn dùng Voyage/`visual_search.*` |
| [x] | HAI-03.2 | Tạo query embedding bằng SigLIP2 | `test_siglip2_provider.py` và runtime smoke | Vector 768 chiều, normalized |
| [x] | HAI-03.3 | Đọc active model từ registry | `test_v2_repository.py` | Không hard-code model ID |
| [x] | HAI-03.4 | Gọi exact cosine V2 | `siglip2-v2-smoke-2026-10-23.json` | Chỉ trả vector `READY` đúng asset |
| [x] | HAI-03.5 | Gom candidate theo `product_id` | Repository SQL/test | Mỗi product tối đa một lần |
| [x] | HAI-03.6 | Giữ ảnh có similarity cao nhất | Repository SQL/test | Matched image/score đúng |
| [x] | HAI-03.7 | Tạo tie-break ổn định | Repository SQL/test | Cùng input có cùng thứ tự |
| [x] | HAI-03.8 | Spring lọc product/stock | `VisualSearchServiceTest` | Không trả product inactive |
| [x] | HAI-03.9 | Spring enrich giá/tồn/ảnh hiện thời | `VisualSearchServiceTest` | Không dùng Commerce data stale |
| [x] | HAI-03.10 | Giữ filter category/gender/price | `VisualSearchServiceTest` | Hard filter luôn được giữ |
| [x] | HAI-03.11 | Xóa ảnh query tạm | Request xử lý in-memory, không có persistence path | Không nhập ảnh khách vào dataset |
| [x] | HAI-03.12 | Smoke test storefront end-to-end | `npm run smoke:visual-search`; `siglip2-v2-smoke-2026-10-23.json` | Upload trả HTTP 200, render 4 kết quả và top-1 đúng |

**Điều kiện đóng:** endpoint chạy end-to-end bằng SigLIP2 V2, không trùng product và Commerce được hydrate tại Spring.

### HAI-04 Kiểm thử pipeline V2

**Trạng thái:** `DONE`

| Xong | ID | Nhóm test | Ca bắt buộc | Điều kiện đạt |
|---:|---|---|---|---|
| [x] | HAI-04.1 | Image validation | File hỏng, MIME sai, quá byte/pixel, decompression bomb | Từ chối đúng status |
| [x] | HAI-04.2 | Model | Revision sai, thiếu dependency, sai dimension, zero/non-finite vector | Fail-fast rõ ràng |
| [x] | HAI-04.3 | Repository | Active model, exact cosine, stale/failed vector, asset đổi | Đọc đúng schema V2 |
| [x] | HAI-04.4 | Grouping | Nhiều ảnh/product, tie score, candidate limit | Không trùng product |
| [x] | HAI-04.5 | Spring | Inactive, out-of-stock, filter, enrichment | Response đúng hiện thời |
| [x] | HAI-04.6 | Security | Token sai, rate limit, timeout | Không lộ secret/stack trace |
| [x] | HAI-04.7 | Frontend QA | Upload, camera, crop, loading, empty, error, retry, mobile | Không có lỗi chặn demo |
| [x] | HAI-04.8 | Regression | Python, Spring và storefront gates | Các suite liên quan xanh |

**Bằng chứng đóng task (24/10/2026):** `evidence/image-search-v2/hai-04-verification-2026-10-24.json`. Kết quả gồm 114 Python tests với RabbitMQ thật, 20 Spring tests với PostgreSQL/pgvector Testcontainers, storefront lint/typecheck/build, Playwright QA desktop/mobile/camera và smoke end-to-end trên Supabase thật. Feature flag chỉ được bật trong process smoke test; cấu hình mặc định vẫn tắt.

### HAI-05 Xây dựng tập ảnh DEV

**Trạng thái:** `DONE`

| Xong | ID | Việc cần làm | Yêu cầu | Đầu ra |
|---:|---|---|---|---|
| [x] | HAI-05.1 | Tạo DEV v1 | Không sửa catalog frozen | `datasets/image-search/dev-v1/` |
| [x] | HAI-05.2 | Thu thập/tạo ảnh DEV | 40–60 ảnh | Assets có nguồn |
| [x] | HAI-05.3 | Phủ năm nhóm | Áo, quần, đồng phục, giày, phụ kiện | Distribution report |
| [x] | HAI-05.4 | Phủ biến đổi ảnh | Góc, nền, sáng, crop, blur, noise, JPEG | Case metadata |
| [x] | HAI-05.5 | Thêm unknown query | Có ảnh ngoài catalog | Negative labels |
| [x] | HAI-05.6 | Tạo manifest/checksum | ID, SHA-256, nguồn, license | `manifest.json`, `assets.sha256` |
| [x] | HAI-05.7 | Gán `source_group_code` | Không rò rỉ qua split | Manifest review |
| [x] | HAI-05.8 | Gán relevance 0–3 | `grade >= 2` là relevant | `labels.json` |
| [x] | HAI-05.9 | Freeze DEV v1 | Có reviewer/checksum cuối | README `FROZEN` |

**Bằng chứng đóng task (24/10/2026):** `evidence/image-search-v2/dev-v1-validation-2026-10-24.json`. DEV v1 có 55 ảnh, gồm 10 ảnh cho mỗi nhóm áo/quần/đồng phục/giày/phụ kiện và 5 unknown; 55/55 judgment `COMPLETE`, 55 checksum duy nhất và không có exact duplicate. Positive case là biến đổi deterministic từ catalog nên chỉ dùng để tune DEV, tuyệt đối không dùng làm TEST hoặc bằng chứng tổng quát hóa thực tế.

DEV dùng để chọn preprocessing, threshold, candidate limit và grouping; không dùng làm kết quả cuối.

### HAI-06 Xây dựng tập ảnh TEST độc lập

**Trạng thái:** `DONE`

| Xong | ID | Việc cần làm | Yêu cầu | Đầu ra |
|---:|---|---|---|---|
| [x] | HAI-06.1 | Chốt protocol trước khi thu ảnh | Không đổi metric sau khi xem kết quả | `datasets/image-search/test-v1/README.md` |
| [x] | HAI-06.2 | Thu ảnh độc lập | Mục tiêu tối thiểu 100 ảnh | Asset có quyền dùng |
| [x] | HAI-06.3 | Cân bằng năm nhóm | Mục tiêu 20 ảnh/nhóm | Distribution report |
| [x] | HAI-06.4 | Tạo manifest/checksum/license | Đủ metadata/case | Manifest và SHA-256 |
| [x] | HAI-06.5 | Loại exact duplicate | So với catalog/DEV | 0 duplicate |
| [x] | HAI-06.6 | Loại near duplicate | Perceptual hash/embedding + review | Near-duplicate report |
| [x] | HAI-06.7 | Gán product/category/color | Có reviewer | `labels.json` |
| [x] | HAI-06.8 | Hoàn tất candidate judgment | Chỉ `COMPLETE` tính Recall | Judgment report |
| [x] | HAI-06.9 | Freeze TEST v1 | Không tune bằng TEST | Checksum cuối |

TEST không được là ảnh catalog, crop trực tiếp, ảnh synthetic từ catalog hoặc ảnh cùng `source_group_code` với catalog/DEV.

**Bằng chứng đóng task (24/10/2026):** `evidence/image-search-v2/test-v1-validation-2026-10-24.json`. TEST v1 đã `FROZEN` với 120 query độc lập từ Wikimedia Commons: 100 positive cân bằng 20 ảnh cho mỗi nhóm áo/quần/đồng phục/giày/phụ kiện và 20 unknown. Có 120/120 judgment `COMPLETE`, 120 checksum duy nhất, 107 source group (tối đa 2 query/source group), đầy đủ attribution/license; không có exact duplicate và khoảng cách dHash gần nhất được nhận là 10 sau khi đối chiếu 136 ảnh catalog/DEV. Color được báo N/A thay vì đoán. Việc ký duyệt của một thành viên dự án khác vẫn là điều kiện riêng trước khi nộp báo cáo cuối.

### HAI-07 Viết runner và chạy đánh giá

**Trạng thái:** `DONE`

```text
Precision@K = |top-K ∩ relevant| / K
Recall@K    = |top-K ∩ relevant| / |relevant|
```

| Xong | ID | Việc cần làm | Đầu ra | Điều kiện đạt |
|---:|---|---|---|---|
| [x] | HAI-07.1 | Runner đọc dataset đã freeze | CLI/script | Không đọc label ngoài version |
| [x] | HAI-07.2 | Lưu ranked output từng case | `ranked-results.json` | Có ID, rank, score, matched image |
| [x] | HAI-07.3 | Tính Precision@1/5/10 | Metrics JSON | Tính lại được từ ranked output |
| [x] | HAI-07.4 | Tính Recall@1/5/10 | Metrics JSON | Chỉ dùng case `COMPLETE` |
| [x] | HAI-07.5 | Tính top-1/top-5 accuracy | Metrics JSON | Có sample count |
| [x] | HAI-07.6 | Tính category/color accuracy | Metrics JSON | Báo N/A nếu thiếu label |
| [x] | HAI-07.7 | Tính failure/unknown rate | Metrics JSON | Ghi rõ mẫu số |
| [x] | HAI-07.8 | Tính latency P50/P95 | Metrics JSON | Đo end-to-end, ghi phần cứng |
| [x] | HAI-07.9 | Báo cáo theo nhóm sản phẩm | Metric slices | Không chỉ báo trung bình |
| [x] | HAI-07.10 | Lưu config/model/dataset/commit | `benchmark-config.json` | Run tái lập được |
| [x] | HAI-07.11 | Chạy DEV, khóa cấu hình | DEV report | Hoàn tất trước TEST |
| [x] | HAI-07.12 | Chạy TEST cuối | Final report | Không tune lại bằng TEST |

**DEV report (24/10/2026):** `evidence/image-search-v2/dev-v1-benchmark-2026-10-24/`. Runner kiểm tra dataset frozen/checksum và hoàn tất 55/55 request, khóa candidate limit `20` và similarity threshold `0.71233916`. Trên tập synthetic DEV: Recall@1/5/10 và category accuracy đều `1.0`, unknown rejection `1.0`, request failure `0`, latency P50 `607.88 ms`, P95 `707.04 ms`; Precision@1/5/10 lần lượt `1.0/0.2/0.1` vì mỗi query chỉ có một relevant product. Color accuracy là N/A do DEV chưa có expected color label. Các số DEV chỉ dùng để tune, không phải acceptance hoặc bằng chứng tổng quát hóa thực tế.

**TEST report (24/10/2026):** `evidence/image-search-v2/test-v1-final/`. Lượt TEST cuối chạy đúng một lần sau khi Dũng ký duyệt độc lập, dùng candidate limit `20` và threshold DEV đã khóa `0.71233916`; 120/120 request hoàn tất, không có request failure. Acceptance `FAILED`: Recall@5 `1.67%` và category accuracy `4%`, thấp hơn mục tiêu `80%` và `90%`; unknown rejection `100%`, latency P50 `623.03 ms`, P95 `670.52 ms`, leakage `0`. Chẩn đoán trên ranked output đã lưu cho thấy raw top-5 hit trước threshold là `79%` và raw top-1 category match `67%`; domain shift từ DEV synthetic sang ảnh độc lập là nguyên nhân chính, nhưng ranking thô cũng chưa đạt target. Không retune và không chạy lại TEST v1.

### Acceptance target

| Chỉ số | Mục tiêu |
|---|---:|
| Embedding coverage | 100% catalog snapshot |
| Recall@5 | ≥ 80% trên TEST độc lập |
| Category accuracy | ≥ 90% |
| Leakage | 0 case được phát hiện |
| P95 end-to-end | Mục tiêu ≤ 3 giây trên phần cứng được ghi rõ |

Precision@K phải báo baseline trước khi chốt ngưỡng cuối, không đặt ngưỡng sau khi xem TEST.

### HAI-08 Phân tích lỗi và baseline

**Trạng thái:** `DONE`

| Xong | ID | Việc cần làm | Đầu ra |
|---:|---|---|---|
| [x] | HAI-08.1 | So sánh legacy Voyage và SigLIP2 V2 | Comparison table có cảnh báo khác dataset/model |
| [x] | HAI-08.2 | So sánh raw candidates với grouped Commerce results | `N/A_NOT_RECORDED`; xác nhận grouped result không trùng product và bổ sung yêu cầu telemetry cho runner v2 |
| [x] | HAI-08.3 | Phân tích nhầm áo/đồng phục | Failure cases |
| [x] | HAI-08.4 | Phân tích nhầm màu | `N/A_MISSING_EXPECTED_COLOR_LABELS`; không đoán nhãn hậu nghiệm |
| [x] | HAI-08.5 | Phân tích crop/che khuất/nền phức tạp | Failure cases |
| [x] | HAI-08.6 | Phân tích phụ kiện nhỏ | Failure cases |
| [x] | HAI-08.7 | So sánh synthetic catalog với ảnh chụp | Limitations section |
| [x] | HAI-08.8 | Đề xuất cải tiến nhưng không sửa TEST | `failure-cases.md` |

Mỗi failure case phải có query ID, expected products, top results, similarity, category/color và nhận xét nguyên nhân.

**Bằng chứng đóng task (25/10/2026):** `evidence/image-search-v2/test-v1-final/failure-analysis.json` và `failure-cases.md`, sinh offline bằng `visual-search-service/scripts/analyze_test_failures.py` từ ranked output đã khóa, không gọi lại TEST. Raw top-1 relevant `59%`, raw hit@5 `79%`, raw hit@10 `84%`, raw top-1 category `67%`; 96/100 positive top-1 không vượt threshold DEV, 33/100 nhầm category và 21/100 không có relevant product trong raw top 5. Báo cáo có confusion matrix, phân tích theo nhóm, case đại diện, review ảnh/nền/crop/phụ kiện, so sánh Voyage lịch sử và DEV synthetic, cùng kế hoạch DEV/TEST v2. Hai phép đo không có dữ liệu được ghi N/A rõ ràng; TEST v1 không bị retune hoặc chạy lại.

### HAI-09 Hardening, rollout và rollback

**Trạng thái:** `IN PROGRESS`

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [x] | HAI-09.1 | Feature flag mặc định tắt trước acceptance | Không bật ngoài ý muốn |
| [x] | HAI-09.2 | Internal token chỉ ở server | Frontend không thấy token |
| [x] | HAI-09.3 | Rate limit tại Spring | Test đạt |
| [x] | HAI-09.4 | Byte/MIME/pixel/timeout guard | Test đạt |
| [x] | HAI-09.5 | Xóa query image sau xử lý | Không vượt retention |
| [ ] | HAI-09.6 | Audit log | Không có ảnh/token/secret/PII |
| [x] | HAI-09.7 | Readiness model/DB/coverage | Phản ánh đúng trạng thái |
| [x] | HAI-09.8 | Viết rollout checklist | Có precondition và smoke test |
| [ ] | HAI-09.9 | Thử rollback feature/model | Rollback thành công |
| [x] | HAI-09.10 | Bảo vệ model/vector được evaluation tham chiếu | Không xóa artifact cần tái lập |

**Bằng chứng hardening (25/10/2026):** repository visual search đã được đồng bộ hoàn toàn sang schema V2 `ai_retrieval`; migration V8 bổ sung retry/job correlation và usage telemetry không lưu query image, token, secret hoặc user ID. Readiness fail-closed khi model sai revision, coverage không đủ hoặc truy vấn coverage lỗi. Migration V1–V8 và repository read/write đã được kiểm thử trên PostgreSQL 17.11 + pgvector tạm thời; `V2OneCMigrationTest` 5/5, repository integration 1/1 và visual service 118 passed/2 skipped. Smoke read-only Supabase hiện tại xác nhận readiness `ready`, coverage 81/81 và model ACTIVE; jobs/usage fail-closed 503 cho tới khi V8 được deploy. Còn HAI-09.6 audit log cho thao tác admin và HAI-09.9 rollback drill; rollout tiếp tục tắt do TEST v1 không đạt acceptance.

### HAI-10 Hoàn thiện nội dung báo cáo

**Trạng thái:** `TODO`

| Xong | ID | Nội dung | Bằng chứng cần dùng |
|---:|---|---|---|
| [ ] | HAI-10.1 | Mục tiêu và phạm vi image search | Bảng phân công, kiến trúc V2 |
| [ ] | HAI-10.2 | Nguồn ảnh, license và provenance | Catalog manifests |
| [ ] | HAI-10.3 | Chuẩn hóa ảnh | Kích thước, checksum, Cloudinary |
| [ ] | HAI-10.4 | Kiến trúc pipeline | Cloudinary → SigLIP2 → pgvector → Spring → UI |
| [ ] | HAI-10.5 | Lý do chọn SigLIP2 768 chiều | Model registry/config |
| [ ] | HAI-10.6 | Lý do dùng exact cosine | Quy mô catalog/benchmark |
| [ ] | HAI-10.7 | Versioning và idempotency | Model/image hash/job report |
| [ ] | HAI-10.8 | DEV/TEST và chống leakage | Manifest/checksum/source group |
| [ ] | HAI-10.9 | Công thức và protocol metric | Evaluation runner |
| [ ] | HAI-10.10 | Kết quả tổng thể/theo nhóm | Final metrics |
| [ ] | HAI-10.11 | Failure-case analysis | `failure-cases.md` |
| [ ] | HAI-10.12 | Hạn chế và hướng mở rộng | Synthetic/reference/real photo |
| [ ] | HAI-10.13 | Vận hành và rollback | Runbook đã thử |

## 7. Artifact phải bàn giao

```text
datasets/
└── image-search/
    ├── dev-v1/
    │   ├── manifest.json
    │   ├── labels.json
    │   ├── assets.sha256
    │   └── README.md
    └── test-v1/
        ├── manifest.json
        ├── labels.json
        ├── assets.sha256
        └── README.md

evidence/
└── image-search-v2/
    ├── catalog-pilot-validation.json
    ├── catalog-world-cup-validation.json
    ├── coverage-before.json
    ├── coverage-after.json
    ├── embedding-rerun-report.json
    ├── benchmark-config.json
    ├── benchmark-results.json
    ├── ranked-results.json
    ├── failure-cases.md
    └── final-report.md
```

Binary ảnh lớn có thể nằm ngoài Git. Repository phải giữ manifest, checksum, nguồn, license và hướng dẫn tái tạo hoặc tải lại hợp lệ.

## 8. Nhật ký thực hiện

| Ngày | Công việc | Kết quả | Commit/Artifact | Vướng mắc | Bước tiếp theo |
|---|---|---|---|---|---|
| 23/10/2026 | Khôi phục và đối chiếu phạm vi của Hải | Tạo bảng tiến độ đầy đủ | File này | Chưa chạy lại coverage | Bắt đầu HAI-01.1 |
| 23/10/2026 | Hoàn tất catalog và embedding verification | HAI-01/02 đạt; 81/81 READY, idempotency skip 81 | `evidence/image-search/*.json` | Không có mismatch/stale/failed | Chuyển HAI-03 sang VERIFY |
| 23/10/2026 | Chuyển query path và storefront sang SigLIP2 V2 | Public API HTTP 200; storefront render 4 kết quả, top-1 đúng; 5/5 candidate duy nhất | `siglip2-v2-smoke-2026-10-23.json`; `npm run smoke:visual-search` | Không còn vướng mắc trong HAI-03 | Bắt đầu HAI-04 test pipeline V2 |
| 23/10/2026 | Harden storefront, public API và query readiness | FE giữ thứ tự backend, có availability/cancel/retry/states; BE giữ 422/429/503, chặn MIME/size và xử lý timeout; RabbitMQ không chặn query | Storefront build; 17 Spring tests; 101 Python tests | Chưa hoàn tất QA camera/mobile và toàn bộ ma trận HAI-04 | Tiếp tục các ca HAI-04 còn lại |
| 24/10/2026 | Đóng HAI-04 và đồng bộ runtime database | 114 Python tests, 20 Spring tests, build storefront, Playwright desktop/mobile/camera và smoke Supabase thật đều đạt; ba service dùng cùng Supabase | `evidence/image-search-v2/hai-04-verification-2026-10-24.json` | Chưa có DEV v1 | Bắt đầu HAI-05 |
| 24/10/2026 | Tạo và khóa DEV v1 | 55 query, đủ năm nhóm + unknown; manifest/labels/checksum tái tạo được, không exact duplicate | `datasets/image-search/dev-v1/`; `evidence/image-search-v2/dev-v1-validation-2026-10-24.json` | Positive case là catalog-derived, chỉ dùng tune DEV | Tune trên DEV rồi mới thu TEST độc lập |
| 24/10/2026 | Thu thập, QA và khóa TEST v1 độc lập | 120 query; 20/nhóm; 120 judgment COMPLETE; 0 exact/near duplicate chưa xử lý; validator PASS | `datasets/image-search/test-v1/`; `evidence/image-search-v2/test-v1-validation-2026-10-24.json` | Còn ký duyệt của thành viên dự án khác trước benchmark/nộp cuối | Ký duyệt rồi chạy HAI-07.12 đúng một lần |
| 24/10/2026 | Chạy benchmark TEST v1 cuối sau ký duyệt của Dũng | 120/120 request; acceptance FAILED: Recall@5 1.67%, category accuracy 4%; unknown rejection 100%, P95 670.52 ms | `evidence/image-search-v2/test-v1-final/` | Chất lượng ảnh độc lập không đạt; không được retune/chạy lại TEST v1 | Thực hiện HAI-08 và xây DEV/TEST version mới nếu thay đổi pipeline |
| 25/10/2026 | Hoàn tất HAI-08 failure analysis và baseline comparison | Có confusion matrix, taxonomy, 9 case đại diện; raw hit@5 79%, raw category 67%; xác định domain shift và giới hạn ground truth/telemetry | `failure-analysis.json`; `failure-cases.md`; `analyze_test_failures.py` | Raw image candidates và expected color không được lưu nên hai phân tích tương ứng là N/A | Giữ rollout tắt; tiếp tục HAI-09 hardening và lập DEV/TEST v2 |
| 25/10/2026 | Sửa blocker schema và readiness HAI-09 | Loại SQL `visual_search` cũ; V8 telemetry/job metadata; readiness kiểm tra model + coverage; migration và repository integration đạt | `V8__harden_visual_search_operations.sql`; `test_v2_repository_integration.py` | Chưa deploy V8 lên Supabase dùng chung; còn audit log và rollback drill | Review migration, deploy có kiểm soát, smoke admin endpoints, sau đó hoàn tất HAI-09.6/09.9 |
|  |  |  |  |  |  |
|  |  |  |  |  |  |

## 9. Definition of Done tổng

- [x] HAI-01 hoàn thành và catalog snapshot đã khóa.
- [x] HAI-02 hoàn thành với coverage 100% và idempotency report.
- [x] HAI-03 hoàn thành; API dùng SigLIP2 + `ai_retrieval` end-to-end.
- [x] HAI-04 hoàn thành; test và QA liên quan đều đạt.
- [x] HAI-05 DEV v1 đã `FROZEN`.
- [x] HAI-06 TEST v1 độc lập đã `FROZEN` và không leakage.
- [x] HAI-07 benchmark cuối có ranked output và metric tái lập được; acceptance hiện `FAILED`.
- [x] HAI-08 có failure-case analysis và baseline comparison.
- [ ] HAI-09 có rollout/rollback đã thử nghiệm.
- [ ] HAI-10 có nội dung báo cáo hoàn chỉnh.
- [ ] Một thành viên khác đã review dataset, code, metric và báo cáo.

Không cần fine-tuning hoặc ANN trong pilot. Chỉ xem xét ANN khi `EXPLAIN ANALYZE` và benchmark chứng minh exact cosine không đáp ứng latency ở quy mô dữ liệu thực tế.
