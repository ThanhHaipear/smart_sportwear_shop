# Kế hoạch và tiến độ AI của Lê Thanh Hải

> Cập nhật ngày 19/11/2026. Phần chi tiết HAI-01 đến HAI-10 lưu hồ sơ
> SigLIP2 V2 trước khi chuyển sang microservice. Bảng tổng quan và HAI-11
> đến HAI-16 ghi trạng thái của kiến trúc hiện hành.

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
| HAI-10 | Báo cáo tổng kết | `UPDATED` với kết quả TEST v4 | P1 | HAI-07, HAI-08 |
| HAI-11 | Cutover hoàn chỉnh sang `services/search-service` và `ai_search` | `DONE` | P0 | HAI-01, HAI-02 |
| HAI-12 | Chuẩn hóa metadata ảnh trong Catalog hiện tại | `DONE` | P0 | HAI-11 |
| HAI-13 | Chứng minh exact cosine, không ANN trong pilot | `DONE` | P0 | HAI-11 |
| HAI-14 | Xây DEV v3 và khóa cấu hình mới | `DONE / FROZEN` | P0 | HAI-11, HAI-12, HAI-13 |
| HAI-15 | Tạo và chạy TEST v3 độc lập | `DONE / NO-GO` | P0 | HAI-14 |
| HAI-16 | Rollout public có kiểm soát | Nghiệm thu FE/BE local đạt, chờ mở thử nghiệm nhóm nhỏ | P0 | HAI-15, TEST v4 |

## 4. Tài sản đã có và điều kiện tái sử dụng

| Thành phần | Trạng thái | Bằng chứng hiện tại | Việc còn phải làm trên kiến trúc hiện tại |
|---|---|---|---|
| Catalog pilot và World Cup | `DONE` | 36 sản phẩm, 178 variant, 81 ảnh; manifest/checksum lịch sử đã khóa | Đối chiếu lại với schema `catalog` hiện tại |
| Metadata Cloudinary | `DONE` | Catalog V6 có metadata gốc cho 81/81 ảnh; checksum khớp Cloudinary và hồ sơ import | Lưu metadata khi có nguồn xác minh được |
| Image embeddings hiện hành | `DONE` | HAI-11 backfill tái tạo `81/81 READY`; query thật xác nhận vector khớp SigLIP2 runtime | Giữ model/revision và checksum trong các lần backfill sau |
| SigLIP2 local | `DONE` | `google/siglip2-base-patch16-224`, revision cố định, 768 chiều, L2-normalize | Giữ đúng revision/preprocessing cho cả document và query |
| Worker/indexing hiện hành | `DONE` | HAI-11 xác nhận contract, event, retry/DLQ, duplicate, backfill và idempotency trên service mới | Theo dõi production-like soak trước rollout |
| Exact cosine hiện hành | `DONE` | Query plan quét toàn bộ vector READY của sản phẩm ACTIVE. Search V3 đã bỏ HNSW ảnh | Đo lại khi model, cấu hình hoặc quy mô catalog thay đổi |
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
| 8 | HAI-15 chạy TEST v3 đúng một lần | Recall@5 `>=80%`, category `>=90%`, unknown rejection `>=80%`, leakage `0`, failure `0%`, p95 `<=3s` |
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

**Trạng thái:** `DONE`

Audit read-only ngày 15/11/2026 xác nhận Catalog có 36 sản phẩm ACTIVE, 178
variant ACTIVE và 81 ảnh; `ai_search` có 36 documents, 178 facets và 81 image
embeddings `READY`. HAI-11.5 đã rebuild toàn bộ index và xác minh lần hai
`36 SKIPPED`; HAI-11.6 đã publish/consume một Catalog event thật, đưa
`processed_events` từ 0 lên 1 và giữ queue/retry/DLQ sạch. HAI-11.7 đã sửa
fallback phân loại cho dữ liệu Catalog legacy, backfill lại `36 UPDATED`, xác nhận
lượt hai `36 SKIPPED`, rồi chạy internal image query thật với 10/10 product duy nhất,
top-1 đúng nhãn và model/revision SigLIP2 đã khóa.

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [x] | HAI-11.1 | Audit count Catalog và `ai_search` ở chế độ read-only | Count hiện tại được ghi rõ, không lộ secret |
| [x] | HAI-11.2 | Sửa `GET /internal/v1/products/{id}/index-document` | Trả đúng contract version hóa gồm product, source version, variants, images và metadata |
| [x] | HAI-11.3 | Thêm contract test Catalog ↔ Search | JSON thật từ Catalog được `CatalogIndexDocument` validate thành công |
| [x] | HAI-11.4 | Chạy backfill dry-run qua Catalog API | Report đủ 36 product, 178 facet, 81 ảnh; không ghi DB |
| [x] | HAI-11.5 | Chạy backfill có kiểm soát và lặp lại | 81/81 READY; lần hai hash-skip/idempotent |
| [x] | HAI-11.6 | Phát và consume event Catalog thật | `processed_events` tăng đúng, không duplicate và retry/DLQ sạch |
| [x] | HAI-11.7 | E2E internal image search trên `ai_search` | Query thật trả candidate không trùng product, model/revision đúng |

Không bật public feature trong HAI-11. Mọi thao tác ghi Supabase phải có dry-run,
backup/rollback và phê duyệt riêng.

### HAI-12 Chuẩn hóa metadata ảnh hiện tại

**Trạng thái:** `DONE`

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [x] | HAI-12.1 | Audit host/source của 81 ảnh và đối chiếu manifest | 81 URL Cloudinary có version, 81/81 SHA-256 ảnh gốc khớp hồ sơ import |
| [x] | HAI-12.2 | Chốt schema metadata | Migration Catalog V6 thêm metadata nullable, hỗ trợ ảnh cũ và ảnh chỉ có URL |
| [x] | HAI-12.3 | Backfill metadata idempotent | PostgreSQL local và Supabase cập nhật 81 rồi 0; 81 ảnh vẫn giữ URL và cờ ảnh chính |
| [x] | HAI-12.4 | Bổ sung metadata vào Catalog snapshot | Contract v1 bổ sung trường tùy chọn, Search kiểm tra checksum ảnh gốc trước khi chuẩn hóa |

Catalog lưu SHA-256 của ảnh gốc. Search lưu SHA-256 của ảnh sau chuẩn hóa để
kiểm tra idempotency. Bật lại indexing ảnh cần chạy đối chiếu trước khi phục vụ
truy vấn. Khi visual search tắt, reindex text/facet giữ nguyên các vector ảnh
hiện có. Khi indexing ảnh bật, snapshot có danh sách ảnh rỗng vẫn dọn vector cũ.

Ngày 18/11/2026, V6 và backfill được áp dụng trên Supabase trong cùng transaction.
Kiểm tra sau commit xác nhận 81/81 ảnh đủ metadata. Checksum của 81 vector READY
trong `ai_search` giữ nguyên. Bằng chứng và thông tin backup nằm tại
`evidence/image-search-v2/cutover-ai-search/hai-12-catalog-image-metadata-2026-11-18.json`.

Chạy `python scripts/maintenance/backfill-catalog-image-metadata.py` với
`DATABASE_URL` trỏ đến Catalog PostgreSQL. Dry-run chỉ đọc và kiểm tra ID ảnh,
ID sản phẩm, URL và các metadata đã lưu. Sau khi có backup và phê duyệt ghi,
áp dụng Catalog V6 rồi chạy script với `--execute`. Kiểm tra lượt lặp báo
`updated: 0` và `alreadyComplete: 81`. Nếu phát hiện metadata khác hồ sơ import,
script dừng trước khi ghi. Khi quay lại bản Catalog cũ, có thể giữ các cột
nullable của V6. Public visual search tiếp tục tắt trong HAI-12.

### HAI-13 Xác minh exact cosine trong pilot

**Trạng thái:** `DONE`

| Xong | ID | Việc cần làm | Điều kiện đạt |
|---:|---|---|---|
| [x] | HAI-13.1 | Lưu `EXPLAIN (ANALYZE, BUFFERS)` trên dữ liệu hiện tại | Quét 81 vector READY, dùng `Seq Scan` ở toàn bộ 36 probe |
| [x] | HAI-13.2 | Thiết kế migration tương thích để không dùng HNSW cho pilot | Search V3 chỉ bỏ HNSW ảnh. Backup có 81 vector và định nghĩa index. README Search có câu lệnh khôi phục |
| [x] | HAI-13.3 | Benchmark exact cosine | Xếp hạng khớp phép tính vét cạn độc lập ở 36/36 probe. p95 API nội bộ là 822 ms, đạt gate `<=3s` |
| [x] | HAI-13.4 | Khóa tie-break và grouping theo product | Chọn một ảnh gần nhất mỗi product và phá hòa theo ID. 30/30 truy vấn DEV chạy lặp giữ nguyên thứ tự |

Search V3 được áp dụng trên Supabase ngày 18/11/2026. Checksum và số lượng của
81 vector ảnh READY giữ nguyên. HNSW của text search vẫn hiện diện. Benchmark
gọi API Search nội bộ qua HTTP loopback với SigLIP2 CPU và 30 ảnh của DEV v2.2,
mỗi ảnh hai lần sau một request warmup. Public visual search tiếp tục tắt.
Xem `evidence/image-search-v2/cutover-ai-search/hai-13-exact-image-search-2026-11-18.json`
để biết query plan, cấu hình và kết quả latency.

### HAI-14 DEV v3

**Trạng thái:** `DONE / FROZEN`

- Không dùng TEST v2 để chọn model, threshold, prompt hoặc reranking.
- DEV v3 đã khóa 60 ảnh thật có nguồn và giấy phép. Bộ ảnh có 20 áo,
  8 phụ kiện, 14 ảnh ngoài catalog, 4 quần, 7 đồng phục và 7 giày.
- Bộ dữ liệu có 40 ảnh calibration và 20 ảnh DEV-check. Validator đã đối chiếu
  436 ảnh tham chiếu. Không có trùng SHA-256, Commons page ID hoặc source group.
- Giữ SigLIP2 pretrained local làm baseline bắt buộc; chưa fine-tune trong pilot.
- Chỉ thử preprocessing, crop/region, category consensus và catalog coverage trên DEV.
- Khóa model revision, preprocessing, candidate limit, threshold và ranking trước TEST v3.

Manifest, nhãn và provenance nằm trong `datasets/image-search/dev-v3/`.
Công cụ `scripts.evaluate_dev_v3` của Search so sánh cấu hình trên cùng snapshot
81 vector exact cosine. Các cấu hình thử được chọn trên phần calibration.

Cấu hình ứng viên dùng SigLIP2 trên toàn ảnh, candidate limit 20, category
weight `0.60`, similarity threshold `0.755936` và quy tắc loại ảnh khi xác suất
`unknown` bằng hoặc lớn hơn xác suất cao nhất của các nhóm có trong catalog.
Trên calibration, ứng viên đạt Recall@5 **80,65%**, category accuracy **93,55%**
và unknown rejection **100%**. Trên DEV-check, Recall@5 **78,89%** và category
accuracy **86,67%** dưới các mốc 80% và 90%. Unknown rejection tăng từ **20%**
lên **100%** trên năm ảnh unknown của DEV-check.

Giữ cấu hình ở trạng thái ứng viên. Hai lỗi phân nhóm cần xử lý trên DEV là
áo tập bị xếp vào đồng phục và ốp bảo vệ ống đồng bị xếp dưới giày. Phụ kiện
hiện thiên về bình nước, với 5/8 ảnh, nên độ bao phủ phụ kiện cần được mở rộng.
Ở vòng này, cấu hình chưa được chốt cho HAI-15.
Bằng chứng nằm tại
`evidence/image-search-v2/cutover-ai-search/hai-14-dev-v3-evaluation-2026-11-18.json`.

#### DEV v3.1 bổ sung

Đã khóa thêm 29 ảnh có nguồn và giấy phép trong
`datasets/image-search/dev-v3-supplement/`. Có 10 ảnh bổ sung cho calibration và
19 ảnh kiểm tra mới, gồm 14 ảnh có sản phẩm tương ứng và 5 ảnh ngoài catalog.
Validator đối chiếu với 496 ảnh tham chiếu, trong đó có toàn bộ DEV v3.0.
Khoảng cách dHash nhỏ nhất là 13. Manifest và nhãn DEV v3.0 giữ nguyên.

Ứng viên dùng đồng thuận cosine giữa toàn ảnh và các crop sửa cả bốn lỗi phân
nhóm đã biết của DEV v3.0. Trên 19 ảnh kiểm tra mới, ứng viên đạt Recall@5
**67,86%**, category accuracy **85,71%** và unknown rejection **40%**. Phụ kiện
đạt 5/5 về phân nhóm và có sản phẩm đúng trong top 5 ở cả năm ca. Hai điểm yếu
còn lại là retrieval đúng kiểu áo tập và từ chối ba ảnh ngoài catalog.

Vòng chẩn đoán theo subtype áo trong catalog và các mô tả đồ vật ngoài catalog
đạt Recall@5 **78,57%**, category accuracy **85,71%** và unknown rejection
**80%** trên cùng tập kiểm tra. Kết hợp thêm tín hiệu từ crop nâng unknown
rejection lên 100% nhưng category accuracy giảm còn 71,43%. Hai vòng chẩn đoán
dùng lại tập kiểm tra DEV v3.1 sau khi đã xem kết quả vòng đầu.

Các ứng viên của v3.1 ở dưới ngưỡng khóa. Kết quả xác định điểm cần cải thiện
là khả năng khớp ảnh áo tập có người mặc với hai mẫu áo khoác trong catalog. Xem
`evidence/image-search-v2/cutover-ai-search/hai-14-dev-v3-followup-2026-11-18.json`
để đối chiếu cấu hình và từng ca.

#### DEV v3.2 mở rộng góc nhìn catalog

Đã tạo bốn ảnh tham chiếu ImageGen cho hai thiết kế pilot Violet Air và Orange
Pace. Mỗi mẫu có góc chính diện và góc nghiêng cận thân áo. Ảnh tổng hợp chỉ
được đưa vào snapshot catalog thử nghiệm. Bộ kiểm tra có 20 ảnh Commons độc
lập với catalog, gồm sáu ảnh áo khoác, chín ảnh đối chứng có sản phẩm tương ứng
và năm ảnh ngoài catalog. Validator đối chiếu 529 ảnh tham chiếu.

So sánh 81 với 85 vector bằng cùng SigLIP2 revision và cấu hình đã chọn trong
DEV v3.1. Recall@5 toàn bộ tăng **87,78% → 94,44%**. Recall@5 riêng nhóm áo
khoác tăng **83,33% → 100%**. Độ chính xác nhóm và unknown rejection đều đạt
**100%** trên bộ kiểm tra này. Các chỉ số nhóm đối chứng giữ nguyên.

Cấu hình HAI-14 đã khóa tại
`datasets/image-search/dev-v3-catalog-coverage/locked-profile.json`.
Snapshot 85 vector được lưu cục bộ. Bằng chứng A/B nằm tại
`evidence/image-search-v2/cutover-ai-search/hai-14-catalog-coverage-2026-11-18.json`.
HAI-15 sẽ dùng đúng snapshot và cấu hình đã khóa để đánh giá TEST độc lập.
Chưa triển khai snapshot lên Supabase hoặc runtime thường trực.

### HAI-15 TEST v3 độc lập

**Trạng thái:** `DONE / NO-GO`

- Thu thập TEST mới độc lập với catalog, DEV và TEST v1/v2.
- Kiểm tra license, provenance, SHA-256, near duplicate và source-group leakage.
- Có reviewer/sign-off trước khi chạy benchmark.
- Chạy final đúng một lần với config DEV v3 đã khóa.
- Gate: Recall@5 `>=80%`, category accuracy `>=90%`, leakage `0`, request
  failure `0%`, p95 `<=3s`; unknown rejection phải được báo cáo và không thấp
  hơn ngưỡng đã khóa trên DEV.

Ngày 18/11/2026, bộ ứng viên gồm 90 ảnh Wikimedia Commons thuộc đủ sáu nhóm,
có nhãn và hồ sơ nguồn/giấy phép. Kiểm tra SHA-256, trang nguồn, chuỗi nguồn
và dHash đã qua với 549 ảnh tham chiếu. Phụ kiện có 10 ảnh, là nhóm ít nhất.
Ngày 19/11/2026, người dùng chuyển xác nhận duyệt của Quyền (QuyenNDD). Bộ 90
ảnh được khóa theo đúng checksum trong [hồ sơ duyệt](../datasets/image-search/test-v3/review-approval.json).
Lần chạy chính thức qua API nội bộ với snapshot cục bộ 85 vector đạt Recall@5
**83,82%**, 0 lỗi request, 0 ca rò rỉ chưa xử lý và p95 **290,794 ms**. Phân loại
đúng nhóm **88,24%** và từ chối ảnh ngoài catalog **72,73%** dưới ngưỡng
**90%** và **80%**. [Báo cáo TEST v3](../evidence/image-search-v3/test-v3-final/final-report.md)

API Search nội bộ qua HTTP loopback đã trả đúng thứ tự top-5 cho 20/20 ảnh
DEV, với 0 request lỗi và p95 388,847 ms trên snapshot cục bộ 85 vector.
Kết quả này xác nhận đường chạy benchmark và cấu hình HAI-14 trên DEV.
[Bằng chứng preflight](../evidence/image-search-v3/hai-15-dev-preflight-2026-11-18/metrics.json)

### HAI-16 Rollout public

**Trạng thái:** TEST v4 `PASS`. Đã xác minh luồng Storefront → Gateway → Catalog → Search trên loopback.

Chỉ bắt đầu rollout khi benchmark độc lập đạt tất cả các ngưỡng nghiệm thu.
TEST v4 đã đạt các ngưỡng trên snapshot cục bộ 85 vector. Thứ tự rollout là internal smoke,
shadow, cohort nhỏ, theo dõi quality/latency/error/queue/DLQ, rồi mới mở rộng.
Rollback bằng feature flag/routing; không xóa vector, dataset hoặc evidence khi
xảy ra sự cố.

Search kiểm tra 81 vector index theo checksum đã nghiệm thu, ghép 4 vector
tham chiếu và trả ảnh hiện hành của sản phẩm. Catalog ưu tiên sản phẩm có tồn
kho, giữ thứ tự Search trong từng nhóm. Kiểm tra trên DB chỉ đọc cho top 5
khớp **24/24 ảnh DEV**, p95 **748,407 ms**. Kiểm thử trình duyệt trả HTTP 200
và hiển thị đúng 4 thẻ kết quả. Cờ công khai mặc định tắt. [Bằng chứng tích hợp](../evidence/image-search-v4/visual-pilot-integration-2026-11-20.json)

Ngày 21/11/2026, chạy shadow và rollback bằng các service loopback cô lập
và Supabase chỉ đọc. Trước khi tắt cờ và sau khi bật lại, cả hai lượt đều
khớp top-5 cho 24/24 ảnh DEV và có 0 lỗi trên 48 request mỗi lượt. Gateway
đạt p95 lần lượt 871,061 ms và 854,448 ms. Khi tắt cờ Catalog, API ảnh
qua Gateway trả 503, trang sản phẩm trả 200 và Search nội bộ vẫn sẵn sàng.
[Bằng chứng diễn tập](../evidence/image-search-v4/hai-16-local-shadow-rollback-2026-11-21.json) · [Lệnh chạy trên staging](../services/search-service/README.md#shadow-và-rollback-trên-staging)

Cùng ngày, lặp lại shadow và rollback với RabbitMQ Docker riêng, Search worker
thật và Supabase chỉ đọc. Hai lượt khớp top-5 cho 24/24 ảnh, mỗi lượt 0 lỗi
trên 48 request. Gateway đạt p95 1.063,756 ms trước rollback và 913,834 ms
sau phục hồi. Hàng đợi chính có một consumer. Cả năm hàng đợi chính, retry
và DLQ đều có 0 message ở các điểm đo trước/sau. Fixture riêng xác nhận
worker đưa lỗi lần đầu vào retry 30 giây và lỗi đã hết lượt thử vào DLQ.
[Bằng chứng RabbitMQ](../evidence/image-search-v4/hai-16-staging-broker-2026-11-21.json)

Hải xác định môi trường nghiệm thu hiện tại là FE/BE local với Supabase và
SePay bên ngoài. Đợt nghiệm thu chạy phiên Storefront, Gateway, Catalog,
Search và RabbitMQ cô lập với Supabase chỉ đọc. Trước và sau rollback, cả
hai lượt khớp top-5 cho 24/24 ảnh DEV, mỗi lượt 0 lỗi trên 48 request.
Gateway đạt p95 853,021 ms và 989,035 ms. Trên trình duyệt, ảnh sản phẩm
trả 4 thẻ và ảnh ngoài catalog trả 0 kết quả ở cả hai lượt. Khi tắt cờ,
nút tìm ảnh ẩn, API ảnh trả 503 và trang sản phẩm vẫn trả 200. Cả năm
hàng đợi trống ở các điểm đo và trình duyệt ghi nhận 0 request thanh toán.
[Bằng chứng nghiệm thu FE/BE](../evidence/image-search-v4/hai-16-local-e2e-acceptance-2026-11-21.json)

#### DEV v4 sau TEST v3

Đã đo 16 cấu hình trên 50 ảnh calibration và đối chiếu trên 59 ảnh DEV-check
có sẵn. Đã bổ sung 20 ảnh DEV mới gồm tám bộ bóng đá và mười hai ảnh ngoài
catalog. Trên ảnh mới, bổ sung cả hai bộ mô tả tăng phân nhóm đúng đồng phục
từ **5/8 lên 7/8** và tăng từ chối ảnh ngoài catalog từ **7/12 lên 12/12**.
Trên DEV-check đủ nhóm, cấu hình kết hợp đạt **88,64%** độ chính xác phân nhóm,
dưới mốc **90%**. Cấu hình DEV v4 đang ở giai đoạn thử nghiệm. Vòng tiếp theo
cần đối chứng thêm với áo và phụ kiện trên dữ liệu DEV mới.
[Báo cáo DEV v4](../evidence/image-search-v4/dev-report-2026-11-19.md)

DEV v4.1 đã bổ sung 42 ảnh phủ sáu nhóm, chia 18 ảnh calibration và 24 ảnh
kiểm tra. Runner chọn tham số trên 68 ảnh calibration, gồm dữ liệu DEV đã có.
Cấu hình dùng đầy đủ hai bộ mô tả đạt Recall@5 **84,58%**, phân nhóm
**85,00%** và từ chối ảnh ngoài catalog **4/4** trên bộ kiểm tra mới.
Cấu hình được chọn từ calibration đạt lần lượt **75,83%**, **70,00%** và
**3/4**. Chưa khóa cấu hình. Ba lỗi phân nhóm còn lại khi dùng đầy đủ mô tả
liên quan đến áo tập và quần đùi bóng đá bị nhầm với nhóm trang phục khác.
[Báo cáo DEV v4.1](../evidence/image-search-v4/dev-balanced-report-2026-11-19.md)

DEV v4.2 ngày 20/11/2026 thử thêm tín hiệu cho áo/quần chụp riêng và các loại phụ kiện.
Cấu hình được chọn trên 171 ảnh DEV cũ đạt Recall@5 **86,25%**, phân nhóm
**90%** và từ chối ảnh ngoài catalog **4/4** trên 24 ảnh DEV mới.
Cấu hình đã khóa cho TEST v4 độc lập. Sau xác nhận duyệt của Dũng, lần chạy
chính thức trên 100 ảnh đạt Recall@5 **87,92%**, phân nhóm **93,75%**,
từ chối ảnh ngoài catalog **95%**, 0 lỗi request và p95 **241,561 ms**.
HAI-16 đã qua benchmark và nghiệm thu FE/BE local, gồm shadow, rollback
và RabbitMQ. Bước tiếp theo là mở thử nghiệm có kiểm soát cho nhóm nhỏ.
[Báo cáo DEV v4.2](../evidence/image-search-v4/dev-garment-report-2026-11-20.md) ·
[Báo cáo TEST v4](../evidence/image-search-v4/test-v4-final/final-report.md)

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
├── image-search-v3/
│   └── test-v3-final/        # xác nhận, kết quả và báo cáo TEST v3
└── image-search-v2/
    ├── catalog-pilot-validation.json
    ├── catalog-world-cup-validation.json
    ├── coverage-before.json
    ├── coverage-after.json
    ├── embedding-rerun-report.json
    ├── test-v1-final/
    ├── dev-v2.2-locked-2026-11-03/
    ├── test-v2-final/
    └── cutover-ai-search/    # HAI-11 đến HAI-14
```

Binary ảnh lớn có thể nằm ngoài Git. Repository phải giữ manifest, checksum, nguồn, license và hướng dẫn tái tạo hoặc tải lại hợp lệ.

Báo cáo HAI-10 đã bổ sung kết quả theo kiến trúc hiện hành và liên kết lần chạy
TEST v3. Kết quả TEST v1 và TEST v2 nằm trong các thư mục evidence tương ứng.

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
| 15/11/2026 | Hoàn thành contract và dry-run HAI-11.2–11.4 | Catalog trả snapshot đầy đủ; contract dùng chung được cả Java/Python kiểm tra; dry-run qua Catalog API đạt 36/36 product, 178 variant, 81 ảnh, 0 lỗi | `contracts/examples/catalog-index-document-v1.json`; `evidence/image-search-v2/cutover-ai-search/hai-11-catalog-dry-run-2026-11-15.json` | Launcher ban đầu bật event relay khi RabbitMQ không chạy và làm tăng `attempts` của outbox; đã dừng, không tự ý sửa outbox; lượt xác minh sau ép relay tắt | Xin phê duyệt riêng trước HAI-11.5 vì bước đó ghi `ai_search` |
| 15/11/2026 | Hoàn thành backfill và idempotency HAI-11.5 | Lượt force tái tạo `36 UPDATED`; coverage sau chạy là 36 documents, 178 facets, 81/81 image vectors READY; lượt hai xác minh nội dung ảnh rồi trả `36 SKIPPED`, checksum và `updated_at` không đổi | `evidence/image-search-v2/cutover-ai-search/hai-11-live-backfill-2026-11-15.json`; restore point ngoài Git `backups/hai-11/ai-search-pre-backfill-20261115-110540.dump` | Cần cấu hình `CLOUDINARY_CLOUD_NAME=dfpgvlovs` trong runtime; Catalog relay đã tắt và outbox không tăng thêm | Thực hiện HAI-11.6 với RabbitMQ và event test có kiểm soát |
| 15/11/2026 | Hoàn thành Catalog event HAI-11.6 | Chuẩn hóa `catalog-event-v1`; 38 legacy outbox được giữ và chuyển `SUPERSEDED`; event thật `PUBLISHED` với attempts 0; `processed_events` 0→1; duplicate không tạo hàng mới; retry/DLQ test đạt và mọi Search queue kết thúc ở 0 | `evidence/image-search-v2/cutover-ai-search/hai-11-event-consumer-2026-11-15.json`; Catalog backup ngoài Git `backups/hai-11/catalog-pre-event-v5-20261115-155747.dump` | Đã sửa relay gửi JSON UTF-8 thay vì Java serialization; exchange test cô lập và process tạm đã được dọn | Thực hiện HAI-11.7 internal image-search E2E |
| 15/11/2026 | Hoàn thành internal image-search E2E HAI-11.7 | Phát hiện `product_type` null làm rerank hạ score xuống 40%; bổ sung fallback Search-owned, backfill `36 UPDATED`, lần hai `36 SKIPPED`; query DEV v2 trả HTTP 200, 10 candidate/10 product duy nhất, top-1 đúng, similarity 0,831216 | `evidence/image-search-v2/cutover-ai-search/hai-11-internal-image-e2e-2026-11-15.json` | Vector DB khớp SigLIP2 runtime cosine 1,0; không phải fake vector; public flag và relay vẫn tắt, toàn bộ process tạm đã dọn | Bắt đầu HAI-12 audit metadata ảnh hiện tại |

## 9. Definition of Done hiện hành

### Bằng chứng lịch sử đã hoàn thành

- [x] Catalog snapshot lịch sử đã khóa và 81/81 ảnh có vector SigLIP2 READY.
- [x] DEV v1, TEST v1, DEV v2.2 và TEST v2 có manifest/labels/checksum.
- [x] Runner, ranked output, metric và failure analysis có thể tái lập.
- [x] TEST v2 đã chạy đúng một final run và kết luận `FAILED / NO-GO` được giữ nguyên.
- [x] Feature/model rollback lịch sử đã được drill.

### Điều kiện còn thiếu trước public rollout

- [x] Catalog index-document contract khớp Search client và có integration test.
- [x] Worker/backfill hiện tại tái tạo idempotent 36 documents, 178 facets và 81 image vectors.
- [x] Metadata ảnh/Cloudinary của 81 ảnh pilot và World Cup đã được persist và đối chiếu checksum.
- [x] Exact cosine được chứng minh bằng query plan và phép tính đối chiếu. HNSW ảnh đã được bỏ khỏi pilot.
- [x] Luồng Search, Catalog, Gateway và Storefront đã qua kiểm tra loopback trên `ai_search`.
- [x] DEV v3 và cấu hình catalog 85 vector đã khóa trước TEST v3.
- [x] TEST v4 độc lập đạt các ngưỡng nghiệm thu sau kết quả NO-GO của TEST v3.
- [x] Báo cáo HAI-10 được cập nhật theo kiến trúc hiện tại và kết quả TEST v4.
- [x] Shadow, rollback và RabbitMQ/DLQ đạt trên môi trường kiểm tra cục bộ cô lập.
- [x] Xác minh shadow, RabbitMQ và rollback qua FE/BE local theo cấu hình Hải xác nhận.
- [ ] Mở thử nghiệm cho nhóm nhỏ và theo dõi chất lượng, độ trễ, lỗi và DLQ.

Không cần fine-tuning hoặc ANN trong pilot. Chỉ xem xét ANN khi `EXPLAIN
ANALYZE` và benchmark chứng minh exact cosine không đáp ứng latency ở quy mô dữ
liệu thực tế; khi đó phải version hóa cấu hình và đánh giá lại quality delta.
