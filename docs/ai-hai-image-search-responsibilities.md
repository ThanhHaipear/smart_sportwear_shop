# Kế hoạch và tiến độ AI của Lê Thanh Hải

> Cập nhật trạng thái hiện hành ngày 15/11/2026. HAI-01 đến HAI-10 bên dưới
> lưu bằng chứng của pipeline SigLIP2 V2 trước khi hệ thống chuyển sang kiến
> trúc microservice hợp nhất. Các mục đó không tự động chứng minh pipeline
> `services/search-service` hiện tại đã được cutover hoặc đủ điều kiện rollout.

## 1. Mục tiêu cuối cùng

Hải phụ trách nhánh **dữ liệu hình ảnh và tìm kiếm sản phẩm bằng hình ảnh**:

```text
Ảnh catalog có nguồn, quyền sử dụng và checksum
→ Cloudinary versioned original cho asset Cloudinary
→ Catalog giữ URL nguồn, `public_id`/asset version khi có và provenance có thể tái lập
→ SigLIP2 image embedding 768 chiều
→ pgvector trong `ai_search.image_embeddings`
→ exact cosine search trong pilot, chưa dùng ANN
→ gom kết quả theo sản phẩm
→ Search API nội bộ trả candidate
→ Catalog/Gateway lọc và bổ sung dữ liệu Commerce hiện thời
→ Storefront hiển thị kết quả
→ đánh giá trên DEV/TEST bằng Precision@K và Recall@K
```

Sau mỗi lần hoàn thành một mục, đổi `[ ]` thành `[x]`, cập nhật trạng thái và gắn đường dẫn đến bằng chứng tương ứng.

## 2. Quy ước trạng thái

| Trạng thái | Ý nghĩa |
|---|---|
| `DONE` | Đã có code/dữ liệu và có bằng chứng kiểm tra |
| `HISTORICAL_DONE` | Đã hoàn thành trên kiến trúc cũ; phải verify lại sau cutover |
| `VERIFY` | Đã có nhưng cần chạy lại trên commit và môi trường hiện tại |
| `IN PROGRESS` | Đang thực hiện |
| `TODO` | Chưa bắt đầu |
| `BLOCKED` | Chưa thể tiếp tục vì thiếu đầu vào hoặc quyết định |
| `NO-GO` | Đã có kết quả nhưng chưa đạt quality gate để rollout |

Không dùng phần trăm hoàn thành để thay cho acceptance gate. Một task có thể đã
hoàn thành về code/evidence nhưng toàn bộ tính năng vẫn ở trạng thái `NO-GO`.

## 3. Tổng quan tiến độ

| Mã | Hạng mục | Trạng thái hiện hành | Ưu tiên | Phụ thuộc |
|---|---|---|---:|---|
| HAI-01 | Kiểm kê và khóa catalog ảnh V2 | `DONE` | P0 | Không |
| HAI-02 | Xác nhận image embedding V2 trên pipeline cũ | `HISTORICAL_DONE / VERIFY` | P0 | HAI-01 |
| HAI-03 | API SigLIP2 V2 trên pipeline cũ | `HISTORICAL_DONE / VERIFY` | P0 | HAI-02 |
| HAI-04 | Test API/repository trên pipeline cũ | `HISTORICAL_DONE / VERIFY` | P0 | HAI-03 |
| HAI-05 | Xây dựng và khóa DEV v1, DEV v2.2 | `DONE` | P0 | HAI-01 |
| HAI-06 | Xây dựng và khóa TEST v1, TEST v2 | `DONE` | P0 | HAI-01, HAI-05 |
| HAI-07 | Runner và benchmark TEST v2 | `DONE / NO-GO` | P0 | HAI-03, HAI-05, HAI-06 |
| HAI-08 | Phân tích lỗi và baseline | `DONE` | P1 | HAI-07 |
| HAI-09 | Hardening và rollback drill lịch sử | `HISTORICAL_DONE`; rollout public `BLOCKED` | P1 | HAI-03, HAI-04 |
| HAI-10 | Báo cáo tổng kết | `UPDATE REQUIRED` | P1 | HAI-07, HAI-08 |
| HAI-11 | Cutover hoàn chỉnh sang `services/search-service` và `ai_search` | `IN PROGRESS` | P0 | HAI-01, HAI-02 |
| HAI-12 | Chuẩn hóa metadata ảnh trong Catalog hiện tại | `VERIFY` | P0 | HAI-11 |
| HAI-13 | Chứng minh exact cosine, không ANN trong pilot | `TODO` | P0 | HAI-11 |
| HAI-14 | Xây DEV v3 và khóa cấu hình mới | `TODO` | P0 | HAI-11, HAI-12, HAI-13 |
| HAI-15 | Tạo và chạy TEST v3 độc lập | `BLOCKED` | P0 | HAI-14 |
| HAI-16 | Rollout public có kiểm soát | `BLOCKED / NO-GO` | P0 | HAI-15 |

## 4. Tài sản đã có và điều kiện tái sử dụng

| Thành phần | Trạng thái | Bằng chứng hiện tại | Việc còn phải làm trên kiến trúc hiện tại |
|---|---|---|---|
| Catalog pilot và World Cup | `DONE` | 36 sản phẩm, 178 variant, 81 ảnh; manifest/checksum lịch sử đã khóa | Đối chiếu lại với schema `catalog` hiện tại |
| Metadata Cloudinary lịch sử | `HISTORICAL_DONE / VERIFY` | Evidence cũ có `public_id`, asset version, URL, checksum và provenance | Catalog hiện chỉ lưu `secure_url`; phải khôi phục metadata hoặc ghi rõ chính sách URL/hash thay thế |
| Image embeddings hiện hành | `VERIFY` | Live audit 15/11/2026 có `81/81 READY` trong `ai_search.image_embeddings` | Chứng minh worker/backfill hiện tại có thể tái tạo đủ 81 vector |
| SigLIP2 local | `DONE` | `google/siglip2-base-patch16-224`, revision cố định, 768 chiều, L2-normalize | Giữ đúng revision/preprocessing cho cả document và query |
| Worker/indexing cũ | `HISTORICAL_DONE` | Có retry, stale detection, checksum và idempotency evidence | Sửa Catalog snapshot contract và chạy event/backfill thật trên service mới |
| Exact cosine cũ | `HISTORICAL_DONE` | Hàm exact search trong `ai_retrieval` | `ai_search` đang có HNSW; phải loại mâu thuẫn và lưu `EXPLAIN ANALYZE` |
| Storefront visual search | `DONE` về UI | Có upload/camera/crop/loading/error/result dialog | Public API đang trả 503; chỉ bật sau TEST v3 |
| DEV v2.2 / TEST v2 | `DONE / NO-GO` | DEV đạt gate; TEST v2 Recall@5 `73,75%`, category `79%` | Không retune TEST v2; chỉ cải tiến bằng DEV v3 |
| Voyage | `REFERENCE` | Pipeline lịch sử | Không dùng làm model chuẩn của Hải |

## 5. Thứ tự thực hiện từ trạng thái hiện tại

| Bước | Công việc | Kết quả phải có |
|---:|---|---|
| 1 | HAI-11 sửa Catalog index-document contract | Snapshot có product, version, variants, images và metadata cần thiết |
| 2 | HAI-11 chạy worker/backfill trên kiến trúc mới | `36/36` documents, `178/178` facets, `81/81` image vectors được tái tạo idempotent |
| 3 | HAI-12 quyết định metadata ảnh | Catalog lưu đủ metadata hoặc có chính sách URL/hash/provenance thay thế được kiểm chứng |
| 4 | HAI-13 xác minh exact cosine | Không dùng ANN trong pilot; có query plan và benchmark latency |
| 5 | Chạy contract/integration/E2E hiện hành | Search, Catalog, Gateway và Storefront cùng pass; public flag vẫn tắt |
| 6 | HAI-14 xây và tune trên DEV v3 | Khóa model revision, preprocessing, reranking, threshold và candidate limit |
| 7 | Review/freeze TEST v3 độc lập | Đủ provenance/license, không leakage, có sign-off trước benchmark |
| 8 | HAI-15 chạy TEST v3 đúng một lần | Recall@5 `>=80%`, category `>=90%`, failure `0%`, p95 `<=3s` |
| 9 | Cập nhật HAI-10 | Báo cáo phản ánh `ai_search`, TEST v2 và kết quả TEST v3 |
| 10 | HAI-16 staged rollout | Internal/shadow trước; public chỉ bật khi mọi gate đạt |

## 6. Bảng tiến độ chi tiết

Các mục HAI-01 đến HAI-10 trong phần này là hồ sơ thực hiện lịch sử. Trạng thái
hiện hành phải đọc theo bảng tổng quan và HAI-11 đến HAI-16; không dùng một dòng
`DONE` lịch sử để bỏ qua cutover hoặc quality gate mới.

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

**Trạng thái:** `HISTORICAL_DONE / VERIFY CURRENT ARCHITECTURE`

### Lệnh chạy

```powershell
.\scripts\maintenance\backfill-product-embeddings.ps1 -Modality image -CoverageOnly
.\scripts\maintenance\backfill-product-embeddings.ps1 -Modality image -Verify
.\scripts\maintenance\backfill-product-embeddings.ps1 -Modality image -Verify
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

**Trạng thái:** `HISTORICAL_DONE / VERIFY CURRENT ARCHITECTURE`

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

**Trạng thái:** `HISTORICAL_DONE / VERIFY CURRENT ARCHITECTURE`

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

### HAI-09 Hardening và rollback drill

**Trạng thái:** `HISTORICAL_DONE`; public rollout `BLOCKED / NO-GO`

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [x] | HAI-09.1 | Feature flag mặc định tắt trước acceptance | Không bật ngoài ý muốn |
| [x] | HAI-09.2 | Internal token chỉ ở server | Frontend không thấy token |
| [x] | HAI-09.3 | Rate limit tại Spring | Test đạt |
| [x] | HAI-09.4 | Byte/MIME/pixel/timeout guard | Test đạt |
| [x] | HAI-09.5 | Xóa query image sau xử lý | Không vượt retention |
| [x] | HAI-09.6 | Audit log | Không có ảnh/token/secret/PII |
| [x] | HAI-09.7 | Readiness model/DB/coverage | Phản ánh đúng trạng thái |
| [x] | HAI-09.8 | Viết rollout checklist | Có precondition và smoke test |
| [x] | HAI-09.9 | Thử rollback feature/model | Rollback thành công |
| [x] | HAI-09.10 | Bảo vệ model/vector được evaluation tham chiếu | Không xóa artifact cần tái lập |

**Bằng chứng hardening (25/10/2026):** repository visual search đã được đồng bộ hoàn toàn sang schema V2 `ai_retrieval`; migration V8 bổ sung retry/job correlation và usage telemetry không lưu query image, token, secret hoặc user ID. Readiness fail-closed khi model sai revision, coverage không đủ hoặc truy vấn coverage lỗi. Migration V1–V8 và repository read/write đã được kiểm thử trên PostgreSQL 17.11 + pgvector tạm thời; `V2OneCMigrationTest` 5/5 và repository integration 1/1. Trước deploy đã tạo backup custom-format, kiểm tra checksum/TOC và restore thành công trên PostgreSQL 17 + pgvector; Flyway sau đó áp dụng V8 thành công, schema `public` ở version `8`. Smoke read-only sau deploy đạt: readiness `200/complete`, coverage `81/81 READY`, SigLIP2 ACTIVE và các endpoint jobs/usage/operations đều HTTP 200; RabbitMQ local unavailable nhưng không ảnh hưởng readiness. Evidence deploy: `evidence/image-search-v2/hai-09-v8-deployment-2026-10-25.json`. HAI-09.6 đã bổ sung audit append-only cho toàn bộ admin mutation, ghi cùng transaction với model/job/outbox, dùng payload allowlist và không lưu ảnh, URL ảnh, token, secret, IP, user-agent hoặc user identity; visual service đạt 120 passed/2 skipped, migration contract 5/5 và repository integration 1/1. Evidence audit: `evidence/image-search-v2/hai-09-admin-audit-2026-10-25.json`. HAI-09.9 đã drill feature rollback qua hai Spring context bật/tắt và model rollback trên PostgreSQL/pgvector tạm: image search bị đóng bằng 503 trong khi catalog vẫn 200, SigLIP2 được khôi phục làm model ACTIVE duy nhất, cả hai bộ vector và audit được giữ, Flyway vẫn ở v8. Evidence rollback: `evidence/image-search-v2/hai-09-rollback-drill-2026-10-25.json`. Rollout public tiếp tục tắt do TEST v1 không đạt acceptance.

### HAI-10 Hoàn thiện nội dung báo cáo

**Trạng thái:** `UPDATE REQUIRED` — báo cáo hiện dừng ở TEST v1 và kiến trúc `ai_retrieval`

| Xong | ID | Nội dung | Bằng chứng cần dùng |
|---:|---|---|---|
| [x] | HAI-10.1 | Mục tiêu và phạm vi image search | Bảng phân công, kiến trúc V2 |
| [x] | HAI-10.2 | Nguồn ảnh, license và provenance | Catalog manifests |
| [x] | HAI-10.3 | Chuẩn hóa ảnh | Kích thước, checksum, Cloudinary |
| [x] | HAI-10.4 | Kiến trúc pipeline | Cloudinary → SigLIP2 → pgvector → Spring → UI |
| [x] | HAI-10.5 | Lý do chọn SigLIP2 768 chiều | Model registry/config |
| [x] | HAI-10.6 | Lý do dùng exact cosine | Quy mô catalog/benchmark |
| [x] | HAI-10.7 | Versioning và idempotency | Model/image hash/job report |
| [x] | HAI-10.8 | DEV/TEST và chống leakage | Manifest/checksum/source group |
| [x] | HAI-10.9 | Công thức và protocol metric | Evaluation runner |
| [x] | HAI-10.10 | Kết quả tổng thể/theo nhóm | Final metrics |
| [x] | HAI-10.11 | Failure-case analysis | `failure-cases.md` |
| [x] | HAI-10.12 | Hạn chế và hướng mở rộng | Synthetic/reference/real photo |
| [x] | HAI-10.13 | Vận hành và rollback | Runbook đã thử |

**Bằng chứng báo cáo lịch sử (25/10/2026, xác nhận đóng review 02/11/2026):** `docs/ai-hai-image-search-final-report.md` đã tổng hợp đầy đủ pipeline `ai_retrieval` và kết quả TEST v1 tại thời điểm đó. Bản lịch sử giữ nguyên kết luận TEST v1 FAILED (Recall@5 `1,67%`, category accuracy `4%`) và yêu cầu public flag tiếp tục tắt. HAI-10 chỉ được chuyển lại `DONE` sau khi báo cáo được cập nhật với kiến trúc `ai_search`, kết quả TEST v2 và trạng thái TEST v3 mới nhất.

### Cải tiến sau HAI-10: DEV v2 và ranking

**Trạng thái:** `TEST V2 FAILED — NO-GO` — DEV v2.2 đã đạt gate, nhưng final TEST v2 độc lập không đạt hai ngưỡng chất lượng bắt buộc; public rollout tiếp tục tắt.

- DEV v2.2 `image-search-dev-v2:2.2.0` đã khóa với 60 ảnh Commons thực tế, cân bằng 10 ảnh cho mỗi nhóm áo/quần/đồng phục/giày/phụ kiện/unknown. Ba case còn mơ hồ về query-focus đã được thay và toàn bộ contact sheet được review lại.
- Builder kiểm tra license/provenance, SHA-256, dHash với 256 ảnh catalog/DEV v1/TEST v1 và cấm trùng `sourceGroupCode` với TEST v1. Contact sheets đã được review; các false category của Commons được loại theo page ID.
- Supabase live đã khôi phục ngày 03/11/2026. Flyway V8, 36/36 sản phẩm và 81/81 ảnh ACTIVE; embedding đạt 81/81 READY.
- Job `493cdb82-6eed-40a0-b8d7-88c146206d3c` đã backfill `color_signature`; coverage cuối đạt 81/81, outbox và toàn bộ visual-search queue/retry/DLQ đều sạch.
- Ranking dùng subtype prompt bank của chính SigLIP2, có prototype `unknown`; DEV ablation xác nhận color rerank làm giảm subtype retrieval nên khóa `color weight = 0`, `category weight = 0,60`, candidate limit `20` và threshold `0,755936`. Không dùng nhãn TEST hoặc thêm model ngoài.
- Baseline DEV v2: Recall@5 `11,43%`, category accuracy `22%`, unknown rejection `100%`, p95 `985,58 ms`.
- DEV v2.2 locked đạt Recall@5 `84,83%`, category accuracy `94%`, top-5 accuracy `94%`, unknown rejection `100%`, request failure `0%`, p95 `802,07 ms`; đã vượt gate DEV `80%/90%`.
- Python suite hiện đạt `127 passed, 2 skipped`; DEV v2.2 validator `PASS`.
- TEST v2 `2.0.0` đã freeze đủ 120 ảnh (20 mỗi nhóm), provenance/license đầy đủ, duplicate/leakage validator `PASS` với 316 ảnh tham chiếu. Dũng đã review độc lập và Hải chuyển xác nhận sign-off trước benchmark; commit freeze là `fc07163`.
- Final TEST v2 đã chạy đúng một lần trên 120/120 query với cấu hình DEV v2.2 đã khóa. Acceptance `FAILED`: Recall@5 `73,75%` < `80%`, category accuracy `79%` < `90%`; unknown rejection `75%`, request failure `0%`, p95 `1.381,07 ms`.
- Phân tích offline cho thấy raw hit@5 `86%`, raw hit@10 `89%`, raw top-1 category `83%`; accessories và tops là hai nhóm category yếu nhất. Không retune threshold/ranking và không chạy lại TEST v2. Public flag tiếp tục tắt.
- Evidence: `evidence/image-search-v2/supabase-live-audit-2026-11-03.json`, `evidence/image-search-v2/dev-v2.2-locked-2026-11-03/`, `datasets/image-search/test-v2/` và `evidence/image-search-v2/test-v2-final/`.

### HAI-11 Cutover sang Search microservice hiện tại

**Trạng thái:** `IN PROGRESS`

Audit read-only ngày 15/11/2026 xác nhận Catalog có 36 sản phẩm ACTIVE, 178
variant ACTIVE và 81 ảnh; `ai_search` có 36 documents, 178 facets và 81 image
embeddings `READY`. Tuy nhiên đây là dữ liệu được copy từ read model cũ; chưa
phải bằng chứng worker hiện tại có thể rebuild index. `processed_events=0` và
`indexing_jobs=0`.

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [x] | HAI-11.1 | Audit count Catalog và `ai_search` ở chế độ read-only | Count hiện tại được ghi rõ, không lộ secret |
| [ ] | HAI-11.2 | Sửa `GET /internal/v1/products/{id}/index-document` | Trả đúng contract version hóa gồm product, source version, variants, images và metadata |
| [ ] | HAI-11.3 | Thêm contract test Catalog ↔ Search | JSON thật từ Catalog được `CatalogIndexDocument` validate thành công |
| [ ] | HAI-11.4 | Chạy backfill dry-run qua Catalog API | Report đủ 36 product, 178 facet, 81 ảnh; không ghi DB |
| [ ] | HAI-11.5 | Chạy backfill có kiểm soát và lặp lại | 81/81 READY; lần hai hash-skip/idempotent |
| [ ] | HAI-11.6 | Phát và consume event Catalog thật | `processed_events` tăng đúng, không duplicate và retry/DLQ sạch |
| [ ] | HAI-11.7 | E2E internal image search trên `ai_search` | Query thật trả candidate không trùng product, model/revision đúng |

Không bật public feature trong HAI-11. Mọi thao tác ghi Supabase phải có dry-run,
backup/rollback và phê duyệt riêng.

### HAI-12 Chuẩn hóa metadata ảnh hiện tại

**Trạng thái:** `VERIFY`

Catalog hiện chỉ persist `secure_url`, `alt_text`, thứ tự và primary flag;
`publicId` từ upload không được lưu và index document chưa trả danh sách ảnh.

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [ ] | HAI-12.1 | Audit host/source của 81 ảnh và đối chiếu manifest | Phân biệt Cloudinary, local và nguồn khác; URL hợp lệ |
| [ ] | HAI-12.2 | Chốt schema metadata | Lưu `public_id`, asset version, dimensions, checksum, provenance khi áp dụng |
| [ ] | HAI-12.3 | Backfill metadata idempotent | Không thay ảnh hiển thị; report missing/mismatch rõ ràng |
| [ ] | HAI-12.4 | Bổ sung metadata vào Catalog snapshot | Worker có đủ dữ liệu để tải và xác minh đúng asset |

### HAI-13 Xác minh exact cosine trong pilot

**Trạng thái:** `TODO`

`ai_search.image_embeddings` hiện có HNSW index dù kế hoạch gốc yêu cầu exact
cosine và chưa cần ANN. Không được chỉ dựa vào toán tử `<=>` để kết luận query
là exact.

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [ ] | HAI-13.1 | Lưu `EXPLAIN (ANALYZE, BUFFERS)` trên dữ liệu hiện tại | Xác định rõ sequential exact hay HNSW ANN |
| [ ] | HAI-13.2 | Thiết kế migration tương thích để không dùng HNSW cho pilot | Có rollback; không xóa vector |
| [ ] | HAI-13.3 | Benchmark exact cosine | Ranking deterministic, p95 `<=3s`, Recall không bị sai số ANN |
| [ ] | HAI-13.4 | Khóa tie-break và grouping theo product | Cùng input/config cho cùng thứ tự |

### HAI-14 DEV v3

**Trạng thái:** `TODO`

- Không dùng TEST v2 để chọn model, threshold, prompt hoặc reranking.
- Dùng ảnh DEV mới có provenance, tập trung `accessories`, `tops`, unknown và
  các failure slice của TEST v2.
- Giữ SigLIP2 pretrained local làm baseline bắt buộc; chưa fine-tune trong pilot.
- Chỉ thử preprocessing, crop/region, category consensus và catalog coverage trên DEV.
- Khóa model revision, preprocessing, candidate limit, threshold và ranking trước TEST v3.

### HAI-15 TEST v3 độc lập

**Trạng thái:** `BLOCKED` bởi HAI-14

- Thu thập TEST mới độc lập với catalog, DEV và TEST v1/v2.
- Kiểm tra license, provenance, SHA-256, near duplicate và source-group leakage.
- Có reviewer/sign-off trước khi chạy benchmark.
- Chạy final đúng một lần với config DEV v3 đã khóa.
- Gate: Recall@5 `>=80%`, category accuracy `>=90%`, leakage `0`, request
  failure `0%`, p95 `<=3s`; unknown rejection phải được báo cáo và không thấp
  hơn ngưỡng đã khóa trên DEV.

### HAI-16 Rollout public

**Trạng thái:** `BLOCKED / NO-GO`

Chỉ bắt đầu khi HAI-11 đến HAI-15 hoàn tất. Thứ tự rollout là internal smoke,
shadow, cohort nhỏ, theo dõi quality/latency/error/queue/DLQ, rồi mới mở rộng.
Rollback bằng feature flag/routing; không xóa vector, dataset hoặc evidence khi
xảy ra sự cố.

## 7. Artifact phải bàn giao

```text
datasets/
└── image-search/
    ├── dev-v1/
    ├── test-v1/
    ├── dev-v2/
    ├── test-v2/
    ├── dev-v3/              # tạo khi bắt đầu HAI-14
    └── test-v3/             # chỉ tạo sau khi DEV v3 đã khóa

evidence/
└── image-search-v2/
    ├── catalog-pilot-validation.json
    ├── catalog-world-cup-validation.json
    ├── coverage-before.json
    ├── coverage-after.json
    ├── embedding-rerun-report.json
    ├── test-v1-final/
    ├── dev-v2.2-locked-2026-11-03/
    ├── test-v2-final/
    ├── cutover-ai-search/    # HAI-11 đến HAI-13
    └── test-v3-final/        # chỉ sinh sau sign-off
```

Binary ảnh lớn có thể nằm ngoài Git. Repository phải giữ manifest, checksum, nguồn, license và hướng dẫn tái tạo hoặc tải lại hợp lệ.

Bản báo cáo HAI-10 lịch sử nằm tại `docs/ai-hai-image-search-final-report.md` và đang ở trạng thái cần cập nhật. Báo cáo TEST v1 cấp benchmark vẫn được giữ riêng tại `evidence/image-search-v2/test-v1-final/final-report.md`; TEST v2 ở `evidence/image-search-v2/test-v2-final/` là bằng chứng chất lượng mới nhất đã khóa.

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
| 25/10/2026 | Sửa blocker schema và readiness HAI-09 | Loại SQL `visual_search` cũ; V8 telemetry/job metadata; readiness kiểm tra model + coverage; migration và repository integration đạt | `V8__harden_visual_search_operations.sql`; `test_v2_repository_integration.py` | Cần backup/restore verification và deploy có kiểm soát lên Supabase dùng chung | Backup, deploy V8 và smoke admin endpoints |
| 25/10/2026 | Backup, deploy V8 và smoke Supabase HAI-09 | Restore backup thành công; Flyway ở v8; readiness complete; coverage 81/81; model ACTIVE; jobs/usage/operations HTTP 200 | `evidence/image-search-v2/hai-09-v8-deployment-2026-10-25.json` | RabbitMQ local unavailable; public rollout vẫn bị chặn do TEST v1 failed acceptance | Hoàn tất audit log HAI-09.6 và rollback drill HAI-09.9 |
| 25/10/2026 | Hoàn tất audit log HAI-09.6 | Sáu admin mutation ghi audit append-only; transaction-safe; payload allowlist; không ảnh/token/secret/PII; 120 Python tests + migration/integration đạt | `evidence/image-search-v2/hai-09-admin-audit-2026-10-25.json` | Internal token xác thực service, không định danh cá nhân vận hành và không được ghi log | Thực hiện rollback drill HAI-09.9 |
| 25/10/2026 | Hoàn tất rollback drill HAI-09.9 | Feature on/off 6/6; rollback tắt trả image search 503 nhưng catalog 200; model candidate → SigLIP2 thành công; vector/audit giữ nguyên; Flyway v8 | `evidence/image-search-v2/hai-09-rollback-drill-2026-10-25.json` | Drill dùng môi trường tạm; public production vẫn phải tắt vì TEST v1 failed acceptance | Chuyển sang HAI-10 hoàn thiện báo cáo |
| 25/10/2026 | Hoàn thiện nội dung báo cáo HAI-10 | Đủ HAI-10.1–10.13; kết luận TEST v1 FAILED được giữ nguyên; evidence và điều kiện rollout/rollback được liên kết | `docs/ai-hai-image-search-final-report.md` | Review tổng kết đã được Hải xác nhận chốt ngày 02/11/2026 | Đóng HAI-10; chuyển sang DEV v2 |
| 02/11/2026 | Xây DEV v2 và bổ sung color/category reranking | 60 ảnh thực tế, 10/nhóm; validator và visual review đạt; 123 Python tests đạt | `datasets/image-search/dev-v2/`; `visual-search-service/app/persistence/repository.py` | Supabase project ref cũ không còn DNS/pooler tenant; chưa thể benchmark/tune live | Cập nhật connection hợp lệ, chạy audit read-only rồi benchmark DEV v2 |
| 03/11/2026 | Khôi phục live, backfill màu và benchmark DEV v2 | Supabase V8; 81/81 READY + color; zero-shot/unknown tăng Recall@5 11,43% → 45,52%, category 22% → 66%; 125 tests đạt | `supabase-live-audit-2026-11-03.json`; `dev-v2-benchmark-zero-shot-unknown-2026-11-03/` | DEV vẫn dưới gate 80%/90%; chưa được khóa threshold và chưa tạo TEST v2 | Tiếp tục cải thiện bottoms/accessories/category và relevance judgment hoàn toàn trên DEV v2 |
| 03/11/2026 | Review query-focus và nâng DEV lên v2.1 | Loại ảnh sai/incidental/near-duplicate; relevance theo subtype; subtype prompts đạt Recall@5 63,50%, category 76%, top-5 78%, unknown 90%, p95 926,27 ms | `datasets/image-search/dev-v2/`; `dev-v2.1-subtype-prompts-2026-11-03/` | Vẫn dưới gate tổng 80%/90%; bottoms Recall@5 55%, category 60% | Giữ TEST v2 bị khóa; nghiên cứu region-aware classification hoặc catalog/category coverage trên DEV |
| 03/11/2026 | Khóa DEV v2.2 và tạo TEST v2 candidate | DEV đạt Recall@5 84,83%, category 94%, unknown 100%, p95 802,07 ms; 127 tests đạt. TEST v2 đủ 120 case, validator PASS, đối chiếu 316 ảnh tham chiếu | `dev-v2.2-locked-2026-11-03/`; `datasets/image-search/test-v2/` | TEST v2 đang `REVIEW_REQUIRED`, sign-off độc lập `PENDING`; chưa chạy final benchmark | Thành viên khác review/sign-off, freeze checksum rồi chạy TEST v2 đúng một lần |
| 03/11/2026 | Freeze và chạy final TEST v2 đúng một lần | Dũng sign-off; 120/120 request; Recall@5 73,75%, category 79%, unknown 75%, request failure 0%, p95 1.381,07 ms; acceptance FAILED | `datasets/image-search/test-v2/`; `evidence/image-search-v2/test-v2-final/` | Recall và category dưới gate; TEST v2 không được retune hoặc chạy lại | Giữ public tắt; phân tích offline và chỉ cải tiến trên DEV v3 trước khi tạo TEST v3 độc lập |
| 15/11/2026 | Re-audit sau cutover microservice | Catalog có 36 product, 178 variant, 81 ảnh; `ai_search` có 36 documents, 178 facets, 81 image vectors READY; Search test 11/11 pass | Live read-only audit; `services/search-service`; file kế hoạch này | Catalog snapshot contract chưa khớp Search client, worker chưa có processed event/job, HNSW mâu thuẫn exact pilot, public vẫn tắt | Thực hiện HAI-11 đến HAI-13 trước khi tạo DEV v3 |

## 9. Definition of Done hiện hành

### Bằng chứng lịch sử đã hoàn thành

- [x] Catalog snapshot lịch sử đã khóa và 81/81 ảnh có vector SigLIP2 READY.
- [x] DEV v1, TEST v1, DEV v2.2 và TEST v2 có manifest/labels/checksum.
- [x] Runner, ranked output, metric và failure analysis có thể tái lập.
- [x] TEST v2 đã chạy đúng một final run và kết luận `FAILED / NO-GO` được giữ nguyên.
- [x] Feature/model rollback lịch sử đã được drill.

### Điều kiện còn thiếu trước public rollout

- [ ] Catalog index-document contract khớp Search client và có integration test.
- [ ] Worker/backfill hiện tại tái tạo idempotent 36 documents, 178 facets và 81 image vectors.
- [ ] Metadata ảnh/Cloudinary hiện tại được persist hoặc có chính sách thay thế rõ ràng.
- [ ] Exact cosine được chứng minh bằng query plan; không dùng ANN trong pilot.
- [ ] Internal API, Catalog/Gateway và Storefront E2E pass trên `ai_search`.
- [ ] DEV v3 được khóa mà không dùng nhãn TEST v2 để tune.
- [ ] TEST v3 độc lập đạt Recall@5, category, leakage, failure và latency gate.
- [ ] Báo cáo HAI-10 được cập nhật theo kiến trúc hiện tại và kết quả mới nhất.
- [ ] Staged rollout và rollback smoke đạt trên môi trường acceptance.

Không cần fine-tuning hoặc ANN trong pilot. Chỉ xem xét ANN khi `EXPLAIN
ANALYZE` và benchmark chứng minh exact cosine không đáp ứng latency ở quy mô dữ
liệu thực tế; khi đó phải version hóa cấu hình và đánh giá lại quality delta.
