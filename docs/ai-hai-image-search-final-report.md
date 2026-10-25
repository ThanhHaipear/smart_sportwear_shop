# Báo cáo tổng kết image search V2 (HAI-10)

**Ngày chốt nội dung:** 25/10/2026  
**Catalog snapshot:** `image-catalog-v2-2026-10-23`  
**TEST:** `image-search-test-v1:1.0.0` (`FROZEN`)  
**Quyết định nghiệm thu:** **FAILED — chưa bật public rollout**

## Tóm tắt điều hành

Image search V2 đã hoàn thiện pipeline kỹ thuật từ ảnh đầu vào đến kết quả sản phẩm, dữ liệu catalog đã được index đủ `81/81` ảnh bằng SigLIP2, Flyway đã ở version `8`, các kiểm tra readiness/coverage/admin và rollback đều đạt. Tuy nhiên, benchmark độc lập TEST v1 không đạt hai ngưỡng chất lượng bắt buộc: Recall@5 chỉ `1,67%` so với mục tiêu `>= 80%`, category accuracy chỉ `4%` so với mục tiêu `>= 90%`. Unknown rejection đạt `100%`, request failure `0%` và p95 latency `670,52 ms`.

Kết quả TEST không được retune hoặc chạy lại. `VISUAL_SEARCH_ENABLED=false` phải tiếp tục được giữ ở public backend. Bước cải tiến tiếp theo là xây dựng DEV v2 bằng ảnh thật gần miền sử dụng, cải thiện ranking/category signal, chọn lại threshold hoàn toàn trên DEV v2, sau đó tạo TEST v2 độc lập mới.

## 1. Mục tiêu và phạm vi (HAI-10.1)

Mục tiêu là cho phép người dùng tải ảnh JPEG/PNG/WebP để tìm các sản phẩm thể thao có hình ảnh tương tự trong catalog hiện hành. Phạm vi V2 gồm:

- nhận, kiểm tra an toàn và chuẩn hóa ảnh truy vấn;
- tạo embedding ảnh bằng một model đã đăng ký và khóa version;
- tìm kiếm cosine trên embedding ảnh catalog;
- gom kết quả theo sản phẩm, sau đó để Spring áp dụng trạng thái commerce hiện thời, category, gender, giá và tồn kho;
- theo dõi coverage, job, usage, audit; hỗ trợ feature/model rollback;
- đánh giá bằng DEV để tuning và TEST độc lập để nghiệm thu.

Phạm vi không bao gồm nhận dạng chính xác SKU từ mọi góc chụp, huấn luyện/fine-tune model, hoặc bật tính năng public khi TEST chưa đạt acceptance.

## 2. Nguồn ảnh, license và provenance (HAI-10.2)

### Catalog

Catalog được cố định bằng snapshot `image-catalog-v2-2026-10-23`, SHA-256 `421349750aaa44ef15b16448cf64fd8f34cd08a0f11cd578466f2de9aa3e0de0`. Snapshot tại thời điểm xác minh có 36 sản phẩm active, 178 biến thể active và 81 ảnh catalog. Mỗi item lưu source URI/public ID, checksum và thông tin phiên bản nguồn để có thể truy vết và index lại.

### DEV v1

DEV v1 có 55 truy vấn: 50 positive, chia đều 10 mẫu cho `tops`, `bottoms`, `uniforms`, `shoes`, `accessories`, và 5 unknown. Positive được tạo bằng biến đổi xác định từ ảnh catalog nên chỉ được dùng để chọn threshold và kiểm tra pipeline, không phải bằng chứng về khả năng tổng quát hóa.

### TEST v1

TEST v1 có 120 ảnh độc lập: 100 positive (20 ảnh cho mỗi nhóm) và 20 unknown. Toàn bộ 120 trường hợp có judgment hoàn chỉnh, 120 SHA-256 duy nhất, không có exact duplicate, thuộc 107 source group và tối đa 2 query/source group.

Phân bố license:

| License | Số ảnh |
|---|---:|
| CC BY | 33 |
| CC BY-SA | 70 |
| CC0 | 9 |
| Public domain | 8 |

Mỗi ảnh TEST có nguồn, attribution và license cho phép sử dụng. Dũng đã review labels, provenance/license và duplicate report, sau đó phê duyệt khóa dataset để chạy benchmark cuối. Giới hạn quan trọng: relevance của TEST là theo product/subcategory phù hợp, không khẳng định ảnh truy vấn và catalog là cùng một sản phẩm vật lý.

## 3. Chuẩn hóa và bảo vệ ảnh (HAI-10.3)

Pipeline chấp nhận JPEG, PNG và WebP, tối đa 5 MiB và 16 triệu pixel decoded. MIME khai báo phải khớp định dạng thực. Ảnh được áp dụng EXIF transpose, chuyển RGB, giữ tỉ lệ và thu nhỏ trong khung tối đa `1024 × 1024`, sau đó mã hóa JPEG quality 88 có tối ưu hóa. SHA-256 được tính trên bytes đã chuẩn hóa nên cùng một đầu vào sinh cùng khóa nội dung.

Với ảnh catalog, downloader chỉ cho phép `res.cloudinary.com` và `cdn.shopify.com`, kiểm tra redirect, DNS/public IP, peer IP, MIME, byte limit và timeout. Query image được xử lý trong bộ nhớ và không được lưu (`store_query_images=false`). SigLIP2 `AutoProcessor` thực hiện bước resize/normalization cuối theo preprocessing version đã khóa.

## 4. Kiến trúc pipeline (HAI-10.4)

Luồng index:

```text
Catalog/Cloudinary/Shopify CDN
  -> transactional outbox -> RabbitMQ
  -> download guard + deterministic normalization
  -> SigLIP2 document embedding (768-D, L2)
  -> ai_retrieval.product_image_embeddings (pgvector)
```

Luồng query:

```text
UI -> Spring public API (feature flag/rate limit/MIME/size)
   -> FastAPI internal API (token + image guard)
   -> SigLIP2 query embedding
   -> exact cosine search in PostgreSQL/pgvector
   -> group/deduplicate by product
   -> Spring enriches and filters live commerce data
   -> UI results
```

RabbitMQ phục vụ đồng bộ index bất đồng bộ; query path không phụ thuộc RabbitMQ. Readiness của query phụ thuộc model identity, database và coverage, vì vậy RabbitMQ local unavailable không làm sai trạng thái query readiness.

## 5. Lựa chọn SigLIP2 768 chiều (HAI-10.5)

Model active là `google/siglip2-base-patch16-224`, revision `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`, provider `hugging_face_local`, preprocessing `siglip2-autoprocessor-original-v1`, vector 768 chiều và L2 normalization.

Lựa chọn này phù hợp vì model có image encoder dùng được cho query/document cùng không gian vector, chạy local nên không gửi ảnh người dùng tới API embedding bên ngoài và loại bỏ chi phí theo request. Revision, preprocessing và dimensions được khóa ở config lẫn model registry; service fail-fast nếu cấu hình không khớp active index. Kết quả TEST cho thấy lựa chọn model hiện tại mới chứng minh được tính khả thi kỹ thuật, chưa chứng minh đủ chất lượng cho ảnh thật độc lập.

## 6. Exact cosine search (HAI-10.6)

Embedding được L2-normalize và so bằng pgvector cosine distance `<=>`; similarity được tính là `1 - distance`. SQL sắp xếp thêm theo ID để phá tie xác định. Pilot chỉ có 81 image embeddings, nên exact scan có các ưu điểm:

- không có sai số recall do ANN;
- deterministic và dễ tái lập benchmark;
- không cần lifecycle/rebuild/tuning HNSW hoặc IVFFlat;
- độ trễ TEST p95 `670,52 ms`, thấp hơn ngưỡng `3.000 ms`.

Chỉ cân nhắc ANN sau khi catalog tăng đáng kể và benchmark/`EXPLAIN ANALYZE` trên dữ liệu thật chứng minh exact scan không còn đáp ứng SLO. Khi đó phải đo lại quality delta giữa exact và ANN.

## 7. Versioning và idempotency (HAI-10.7)

Model identity gồm modality, provider, model name/version, dimensions, distance metric, normalization và preprocessing version. Identity của model đã có vector là immutable; thay model phải tạo model version mới. Embedding ảnh dùng khóa chính `(image_id, model_id)` và lưu `source_public_id`, `source_asset_version`, `image_hash`, status và thời điểm embed.

Consumer ghi processed event trong cùng transaction với cập nhật read model. Event giao lại được bỏ qua; ảnh có `READY` embedding và normalized hash không đổi được hash-skip. Job item duy nhất theo `(job_id, entity_type, entity_id)`, retry có giới hạn và DLQ. Dataset/labels/catalog snapshot/benchmark config đều có version và checksum để khóa đúng đầu vào đã đánh giá.

## 8. DEV/TEST và chống leakage (HAI-10.8)

DEV v1 chỉ dùng cho tuning threshold. TEST v1 được khóa trước lần benchmark cuối và không dùng để chọn threshold, model hoặc preprocessing. Threshold `0.71233916` được grid-search trên DEV với mục tiêu tối đa trung bình Recall@5 và unknown rejection, yêu cầu Recall@5 DEV tối thiểu 80%, tie deterministic.

TEST được kiểm tra với 136 reference assets từ catalog và DEV bằng:

- exact duplicate: SHA-256;
- near duplicate: dHash 64-bit;
- reject khi Hamming distance `<= 6`;
- khoảng cách gần nhất nhỏ nhất của ảnh được chấp nhận là `10`;
- unresolved duplicate/leakage: `0`.

Runner cấm tuning threshold khi split là TEST. Benchmark TEST cuối được chạy đúng một lần sau sign-off; phân tích failure sau đó chỉ đọc ranked output đã khóa, không gọi lại service.

## 9. Metric và protocol (HAI-10.9)

Với query positive `q`, tập relevant `R_q`, và danh sách kết quả đã qua locked threshold `L_q`:

- `Precision@K(q) = |R_q ∩ topK(L_q)| / K`;
- `Recall@K(q) = |R_q ∩ topK(L_q)| / |R_q|`;
- báo cáo Precision/Recall là macro-average trên các positive query;
- `Top-K accuracy = số positive query có ít nhất một relevant trong top K / số positive query`;
- `Category accuracy = số positive query có top-1 accepted cùng nhóm mong đợi / số positive query`;
- `Unknown rejection = số unknown query không có accepted result / số unknown query`;
- `Request failure = số request lỗi / tổng số request`;
- p50/p95 dùng nearest-rank trên latency end-to-end của FastAPI, preprocessing, embedding và exact vector search.

Relevance grade `>= 2` được xem là relevant. Candidate limit là 20. Acceptance TEST yêu cầu tối thiểu 100 positive, đủ năm nhóm, 20 unknown, Recall@5 `>= 80%`, category accuracy `>= 90%`, p95 `<= 3.000 ms` và không có request failure. Color accuracy là `N/A` vì TEST cố ý không gán expected color khi màu không rõ ràng cho mọi ảnh.

## 10. Kết quả benchmark (HAI-10.10)

### Tổng thể TEST v1

| Metric | Kết quả | Ngưỡng | Đánh giá |
|---|---:|---:|---|
| Recall@1 | 1,33% | — | Tham khảo |
| Recall@5 | **1,67%** | >= 80% | **FAIL** |
| Recall@10 | 1,67% | — | Tham khảo |
| Precision@1 | 2,00% | — | Tham khảo |
| Precision@5 | 0,60% | — | Tham khảo |
| Precision@10 | 0,30% | — | Tham khảo |
| Top-1 / Top-5 accuracy | 2,00% / 2,00% | — | Tham khảo |
| Category accuracy | **4,00%** | >= 90% | **FAIL** |
| Unknown rejection | 100,00% | — | Đạt hành vi mong muốn |
| Request failure | 0,00% | 0% | PASS |
| Latency p50 / p95 | 623,03 / 670,52 ms | p95 <= 3.000 ms | PASS |

### Theo nhóm, sau locked threshold

| Nhóm | Recall@5 | Category accuracy | Failure@5 |
|---|---:|---:|---:|
| Tops | 0,00% | 10,00% | 100,00% |
| Bottoms | 0,00% | 0,00% | 100,00% |
| Uniforms | 0,00% | 0,00% | 100,00% |
| Shoes | 3,33% | 5,00% | 95,00% |
| Accessories | 5,00% | 5,00% | 95,00% |

DEV v1 đạt Recall@1/5/10, category accuracy và unknown rejection đều 100%, nhưng đó là dữ liệu synthetic/catalog-derived và chỉ có giá trị tuning. Khoảng cách lớn giữa DEV và TEST là bằng chứng domain shift, không phải lý do để thay kết luận TEST.

## 11. Failure-case analysis và baseline (HAI-10.11)

Phân tích offline trên ranked output trước threshold cho thấy raw top-1 relevant `59%`, raw hit@5 `79%`, raw hit@10 `84%` và raw top-1 category `67%`. Trong 100 positive query, chỉ 4 top result vượt threshold DEV. Phân bố top score của positive có p50 `0,59598`, p95 `0,70537`, thấp hơn threshold `0,71233916`; unknown có p95 `0,58984` và max `0,60379`.

Taxonomy lỗi:

- 96/100 query bị locked threshold loại top-1;
- 77 query có relevant result nhưng dưới threshold;
- 33 query nhầm category ở raw top-1;
- 21 query không có relevant product trong raw top 5.

Nguyên nhân chính là domain shift giữa DEV synthetic sinh từ ảnh catalog và TEST ảnh thật độc lập có nền, người mặc, crop, góc chụp và tỷ lệ vật thể khác nhau. Threshold DEV quá cao đối với phân bố TEST, nhưng chỉ hạ threshold vẫn không đủ: raw hit@5 chỉ `79%` và raw category chỉ `67%`, vẫn dưới acceptance `80%` và `90%`.

Các baseline trước đây không được dùng để thay kết luận: Voyage phase 0 chỉ có 4 alternate catalog views; legacy phase 10 có 100 mẫu synthetic nhưng chỉ ba nhóm; DEV v1 có 55 mẫu catalog-derived. Cả ba không tương đương TEST độc lập. Raw-image-candidate so với grouped-product delta và color accuracy là `N/A` vì run đã khóa không lưu raw image candidates riêng và TEST không có expected color labels.

## 12. Hạn chế và hướng mở rộng (HAI-10.12)

Hạn chế hiện tại:

- catalog nhỏ và có ít góc nhìn trên mỗi sản phẩm;
- DEV synthetic không đại diện ảnh thật;
- TEST đánh giá relevance theo subcategory/category, chưa có exact-product identity;
- model chưa được fine-tune cho catalog thể thao của dự án;
- chưa có reranker/category-aware retrieval;
- thiếu telemetry raw-vs-grouped và expected color trong run đã khóa;
- benchmark chạy trên một máy Windows CPU, chưa đại diện mọi môi trường triển khai.

Kế hoạch DEV/TEST v2:

1. Thu thập DEV v2 từ ảnh thật có license/provenance, cân bằng nền, người mặc, crop, góc nhìn và kích thước vật thể.
2. Tăng nhiều góc catalog hoặc document embedding giàu ngữ nghĩa; thử category-aware retrieval/reranking trên DEV.
3. So sánh preprocessing/crop và model candidate bằng cùng DEV protocol; tuyệt đối không tuning trên TEST v1.
4. Chọn threshold mới trên DEV v2 với ràng buộc recall/category/unknown rõ ràng.
5. Bổ sung telemetry raw image candidates, grouped candidates và color labels khi có thể gán chắc chắn.
6. Tạo TEST v2 độc lập, chạy duplicate/leakage review và sign-off trước đúng một final run.
7. Chỉ bật public khi TEST mới đạt toàn bộ gate và rollback drill vẫn pass.

## 13. Vận hành, rollout và rollback (HAI-10.13)

Schema Supabase đã được backup, kiểm tra TOC/checksum và restore thử trước khi deploy V8. Flyway hiện ở version `8`. Smoke read-only sau deploy đạt:

- `/health/ready`: HTTP 200, coverage `complete`;
- `/admin/coverage`: `81/81 READY`;
- `/admin/models`: SigLIP2 ACTIVE;
- `/admin/jobs`, `/admin/usage`, `/admin/operations`: HTTP 200.

Audit append-only bao phủ sáu admin mutation, ghi cùng transaction với thay đổi model/job/outbox và chỉ dùng payload allowlist; không lưu ảnh, image URL, token, secret, IP, user-agent hoặc user identity. Rollback drill xác nhận feature-off trả 503 cho image search nhưng catalog vẫn 200; model candidate có thể activate rồi rollback về SigLIP2, chỉ còn một ACTIVE model và toàn bộ vector/audit được giữ.

Rollout decision tại thời điểm chốt báo cáo là **NO-GO**. Public backend tiếp tục `VISUAL_SEARCH_ENABLED=false`. Chỉ thay đổi quyết định sau một benchmark độc lập mới đạt acceptance; không được bật chỉ dựa trên readiness hoặc DEV metrics.

## Evidence và khả năng tái lập

| Nội dung | Artifact |
|---|---|
| DEV validation | [`dev-v1-validation-2026-10-24.json`](../evidence/image-search-v2/dev-v1-validation-2026-10-24.json) |
| DEV config/metrics/ranking | [`dev-v1-benchmark-2026-10-24`](../evidence/image-search-v2/dev-v1-benchmark-2026-10-24/) |
| TEST validation/sign-off/leakage | [`test-v1-validation-2026-10-24.json`](../evidence/image-search-v2/test-v1-validation-2026-10-24.json) |
| TEST config/metrics/ranking | [`test-v1-final`](../evidence/image-search-v2/test-v1-final/) |
| Failure analysis | [`failure-analysis.json`](../evidence/image-search-v2/test-v1-final/failure-analysis.json), [`failure-cases.md`](../evidence/image-search-v2/test-v1-final/failure-cases.md) |
| V2 verification | [`hai-04-verification-2026-10-24.json`](../evidence/image-search-v2/hai-04-verification-2026-10-24.json) |
| V8 deployment | [`hai-09-v8-deployment-2026-10-25.json`](../evidence/image-search-v2/hai-09-v8-deployment-2026-10-25.json) |
| Audit verification | [`hai-09-admin-audit-2026-10-25.json`](../evidence/image-search-v2/hai-09-admin-audit-2026-10-25.json) |
| Rollback drill | [`hai-09-rollback-drill-2026-10-25.json`](../evidence/image-search-v2/hai-09-rollback-drill-2026-10-25.json) |

## Trạng thái review

- Dataset TEST v1, labels, provenance/license và duplicate report: **đã được Dũng review và đồng ý khóa trước benchmark cuối**.
- Nội dung HAI-10.1–HAI-10.13: **đã hoàn thiện, chờ review độc lập cuối**.
- Gate “một thành viên khác đã review dataset, code, metric và báo cáo”: **chưa đóng**, vì xác nhận hiện có chưa bao gồm bản báo cáo tổng kết này.

