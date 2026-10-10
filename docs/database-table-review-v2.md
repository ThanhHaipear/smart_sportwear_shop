# Review từng bảng database V2

**Ngày review:** 09/10/2026
**Xác nhận:** Chủ dự án đã duyệt kết quả review ngày 09/10/2026.
**Phạm vi:** toàn bộ bảng baseline 1A–1D và ranh giới đợt 2.
**Nguồn:** `database-design-v2.md`, `database-implementation-plan.md`, `plan.md`, entity/service/test và migration legacy trong lịch sử Git (chỉ đối chiếu, không tái sử dụng làm baseline).

## 1. Ý nghĩa trạng thái

- **Đạt thiết kế:** đã chốt mục đích, owner, cột chính, PK/FK/UQ/CHECK, vòng đời, index và test cần có; vẫn phải viết migration và kiểm thử local.
- **Để sau:** không tạo trong baseline; chỉ mở lại khi điều kiện nghiệp vụ ghi trong kế hoạch xuất hiện.
- Không bảng nào được coi là **Đã kiểm thử** cho tới khi migration V2 chạy từ database sạch và test tương ứng đạt.

## 2. Kết luận chung

1. Spring Boot tự quản lý Identity/JWT/Google login; Supabase chỉ là PostgreSQL, frontend không truy cập DB trực tiếp.
2. Dùng một database vật lý, tách write owner bằng schema/role: `public`, `ai_retrieval`, `evaluation`, `assistant`.
3. UUID, `timestamptz` UTC, tiền `numeric(14,2)`, code uppercase ổn định; không dùng PostgreSQL ENUM.
4. FK giao dịch dùng `RESTRICT/NO ACTION`; chỉ dữ liệu phụ thuộc thuần như token, address, cart item và chat child được cascade.
5. Dữ liệu lịch sử/audit/event/movement là append-only. Mọi retry có khóa idempotency hoặc unique business key.
6. JSON phải có schema/version và được validate tại service; quy tắc liên bảng/tổng hợp dùng transaction lock cùng trigger/service có integration test.
7. Supabase Free lưu metadata/vector, không lưu binary ảnh. Exact vector search là baseline; chưa tạo ANN index.
8. Database cũ đã trắng; không data migration, không chạy migration legacy. Baseline seed taxonomy/config và nhập catalog mới bằng manifest/checksum.

## 3. Review 1A — Identity, taxonomy và catalog

### 3.1. Identity (`public`, owner `commerce_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `users` | Email trim/lowercase + functional UQ; role/status CHECK; LOCAL dùng BCrypt, GOOGLE để hash NULL; `DEACTIVATED`/`anonymized_at`; không hard-delete khi có giao dịch | Đăng ký đồng thời/case email; lock/deactivate; anonymize không đổi snapshot order | Đạt thiết kế |
| `user_identities` | Lưu `GOOGLE` + Google `sub`; UQ provider/subject và user/provider; không lưu OAuth token; tạo cùng transaction với user | Login lại khi email Google đổi; Google login đồng thời; không tự merge LOCAL/GOOGLE | Đạt thiết kế |
| `addresses` | Tên địa giới NN, code tùy chọn ở pilot, district nullable; một default/user bằng partial UQ; order dùng snapshot | Set-default đồng thời; ownership `(id,user_id)`; sửa/xóa không đổi order cũ | Đạt thiết kế |
| `refresh_tokens` | Chỉ lưu SHA-256 `char(64)`; rotation/revoke; UQ hash; index user/expiry; cleanup có hạn | Refresh đồng thời/reuse/expired/revoked; logout/reset/lock revoke; cleanup idempotent | Đạt thiết kế |
| `password_reset_tokens` | SHA-256 `char(64)`, one-time atomic claim, TTL; chỉ LOCAL ACTIVE được phát token | Unknown/Google cùng response; reset đồng thời/hết hạn/dùng lại; revoke refresh sau reset | Đạt thiết kế |

### 3.2. Taxonomy (`public`, owner `commerce_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `categories` | Code/slug UQ, parent self-FK RESTRICT, status/ordering; chặn self-parent và chu trình sâu | Seed lặp; duplicate code/slug; chu trình 2+ cấp; query cây | Đạt thiết kế |
| `brands` | Code/slug UQ, soft status; seed `NO_BRAND` để giữ `products.brand_id` NN | Seed lặp; duplicate; không xóa brand đang dùng | Đạt thiết kế |
| `size_systems` | Code UQ; seed `APPAREL_INT`, `SHOES_EU`, `ONE_SIZE`; soft status | Seed lặp; code bất biến; FK RESTRICT | Đạt thiết kế |
| `sizes` | FK size system; UQ system/code; `sort_order >= 0` | Sai hệ size; duplicate trong cùng hệ; thứ tự hiển thị | Đạt thiết kế |
| `product_types` | Code UQ; default size system NN; seed 5 loại B01 | Type thiếu hệ size; code trùng; không xóa khi đã dùng | Đạt thiết kế |
| `sports` | Code UQ; seed `FOOTBALL`, `RUNNING`; soft status | Seed lặp; code trùng; inactive không phá lịch sử | Đạt thiết kế |
| `use_cases` | Code UQ, tên/status/timestamps theo convention lookup | Seed lặp; code trùng; FK RESTRICT | Đạt thiết kế |
| `materials` | Code UQ, tên/status/timestamps theo convention lookup | Seed lặp; code trùng; FK RESTRICT | Đạt thiết kế |
| `technologies` | Code UQ, tên/status/timestamps theo convention lookup | Seed lặp; code trùng; FK RESTRICT | Đạt thiết kế |
| `colors` | Code UQ, family code NN, hex regex nếu có; soft status | Hex sai; family mapping; color đã dùng không được xóa | Đạt thiết kế |

### 3.3. Catalog, SKU, ảnh và dataset (`public`, owner `commerce_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `catalog_datasets` | UQ name/version; manifest SHA-256/source/license; `DRAFT→VALIDATED→FROZEN`; FROZEN bất biến | Freeze rồi sửa bị chặn; checksum/version trùng; import retry | Đạt thiết kế |
| `products` | FK taxonomy RESTRICT; gender chỉ MEN/WOMEN/UNISEX; slug UQ; status/data source CHECK; data source bất biến | Publish thiếu variant/ảnh; slug trùng; data source đổi; soft deactivate | Đạt thiết kế |
| `catalog_dataset_items` | PK dataset/product; UQ dataset/source record; checksum normalized | Item sai dataset/product; duplicate source; FROZEN không đổi | Đạt thiết kế |
| `product_sports` | PK kép và hai FK RESTRICT | Gắn trùng; sport/product không tồn tại | Đạt thiết kế |
| `product_use_cases` | PK kép và hai FK RESTRICT | Gắn trùng; use case/product không tồn tại | Đạt thiết kế |
| `product_materials` | PK kép; percentage NULL hoặc `(0,100]`; tổng phần trăm nếu khai đủ kiểm ở service | Phần trăm sai; duplicate; tổng vượt 100 | Đạt thiết kế |
| `product_technologies` | PK kép và hai FK RESTRICT | Gắn trùng; technology/product không tồn tại | Đạt thiết kế |
| `product_variants` | SKU UQ; UQ product/size/color; composite UQ product/id; giá/tồn/reserved/reorder CHECK; size phải đúng hệ product | SKU/combo trùng; size sai hệ; reserved vượt stock; giá/tồn âm; concurrent update | Đạt thiết kế |
| `product_images` | Cloudinary metadata/version/checksum/source/license; composite FK product/variant; primary partial UQ; màu phải khớp variant | Hai primary đồng thời; ảnh sai product/color; checksum/license thiếu; delete event idempotent | Đạt thiết kế |

### 3.4. Kết quả triển khai và review migration 1A (10/10/2026)

- DDL: `backend/src/main/resources/db/v2-migration/V1__create_1a_identity_taxonomy_catalog.sql`, tạo đủ 24 bảng của 1A; không dùng migration legacy.
- Seed: `V2__seed_1a_taxonomy.sql`, gồm 5 loại sản phẩm, `FOOTBALL`/`RUNNING`, hệ size quần áo/giày/phụ kiện và không có `KIDS`.
- Kiểm thử: `V2OneAMigrationTest` dùng Flyway `clean → migrate → validate → migrate lại` và Hibernate `validate` toàn bộ entity 1A, đạt **5/5 test** trên PostgreSQL local 18.4. `ProductImageServiceTest` đạt thêm **14/14 test** cho upload, URL khai báo, metadata và provenance ảnh.
- Các ràng buộc DB đã kiểm tra: credential LOCAL/GOOGLE; email và địa chỉ mặc định duy nhất; category không chu trình; variant đúng hệ size; sản phẩm ACTIVE cần SKU và ảnh chính đủ metadata; ảnh phải khớp màu variant; `data_source` bất biến; dataset `FROZEN` bất biến.
- Kết luận migration: **Đã kiểm thử local, đã chuyển entity/service và đã triển khai vertical slice 1A lên Supabase** bằng Flyway V2 ngày 10/10/2026. Runtime mặc định đã chuyển sang location V2; migration legacy chỉ còn trong lịch sử Git.

## 4. Review 1B — Commerce Core (`public`, owner `commerce_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `carts` | XOR `user_id`/`session_id`; một cart hiện hành mỗi identity; cascade khi user bị xóa trước giao dịch | Hai cart đồng thời; guest merge; không cho cả hai identity | Đạt thiết kế |
| `cart_items` | UQ cart/variant; quantity dương; giá luôn đọc lại khi checkout | Add/update đồng thời; variant hết hàng/inactive; cascade theo cart | Đạt thiết kế |
| `orders` | User FK RESTRICT; snapshot địa chỉ; tiền NN/nonnegative và công thức tổng; status/method/source CHECK | Snapshot bất biến; transition; hủy đồng thời; data source bất biến | Đạt thiết kế |
| `order_items` | Snapshot bắt buộc; composite FK product/variant; quantity/tổng/discount/net/return counters CHECK | Variant sai product; tổng tiền sai; pending+returned vượt mua | Đạt thiết kế |
| `order_status_history` | Append-only; actor/reason/time; index order/time | Mỗi đổi trạng thái có history; không update/delete; retry không ghi trùng | Đạt thiết kế |
| `payments` | Composite UQ id/order; provider/ref UQ; amount/status/time CHECK; tối đa một attempt active/order/provider | COD/VNPay lifecycle; hai attempt active; callback muộn; lock order→payment | Đạt thiết kế |
| `payment_events` | Provider event key UQ toàn provider; payload redacted; append-only | Callback lặp/mâu thuẫn/sai chữ ký/sai số tiền; không lưu secret | Đạt thiết kế |
| `returns` | Composite UQ id/order; một return mở/order; cửa sổ 7 ngày; status CHECK | Hai request đồng thời; ngoài hạn; hủy/reject nhả pending | Đạt thiết kế |
| `return_items` | Composite FK về return/order item cùng order; quantity/condition/resolution/refund/restock CHECK | Return item sai order; trả vượt mua; restock sai condition; đồng phục thiếu bộ | Đạt thiết kế |
| `refunds` | Payment bắt buộc cùng order; return tùy chọn cùng order; active UQ theo nguồn; tiền dương và không vượt paid | Refund hủy trùng; return refund trùng; partial/full; callback/retry | Đạt thiết kế |
| `inventory_transactions` | Append-only; UQ idempotency; before/after NN; delta đúng type; manual actor/reason bắt buộc | Hai checkout; reserve/deduct/release/restock lặp; không âm/đụng reserved | Đạt thiết kế |
| `site_settings` | Key PK; JSON typed, không secret; update có actor/time | Sai value type; concurrent update; secret bị từ chối | Đạt thiết kế |
| `audit_logs` | Append-only, redacted, index entity/time và actor/time; retention theo loại | Không PII/token/payment raw; không update/delete | Đạt thiết kế |
| `integration_outbox` | UQ idempotency key; aggregate version; status/attempt/time CHECK; claim bằng lock/skip locked | Commit nghiệp vụ cùng event; publish retry; hai publisher; event đảo thứ tự | Đạt thiết kế |
| `user_interactions` | Append-only; ít nhất user/anonymous; event/source/data source CHECK; không PII metadata | Retention/anonymize; event trùng; timestamp/candidate leakage recommendation | Đạt thiết kế |

`shipping_methods` và `shipments` thuộc **đợt 2 — Để sau**; baseline cập nhật trạng thái trực tiếp trên `orders` và giữ `shipping_fee` snapshot.

### 4.1. Kết quả triển khai và review migration 1B (10/10/2026)

- Runtime migration: chuỗi legacy và migration cầu nối `V56` đã được loại khỏi runtime sau khi Supabase trắng sử dụng baseline V2; ứng dụng mặc định chỉ đọc `classpath:db/v2-migration`.
- DDL: `backend/src/main/resources/db/v2-migration/V3__create_1b_commerce_core.sql`, tạo đủ 15 bảng Commerce Core và các FK/UQ/CHECK/index/trigger đã chốt; fact log, payment event, history, inventory, audit, outbox và interaction được bảo vệ append-only theo phạm vi phù hợp.
- Runtime: entity/service đã ánh xạ schema 1B; COD có payment `UNPAID` từ checkout và `PAID` khi giao; VNPay callback được ghi idempotent với payload redacted; hủy đi thẳng `CANCELLED` và tạo refund độc lập; partial/full refund đồng bộ `PARTIALLY_REFUNDED`/`REFUNDED`; return và inventory có row lock, counter và idempotency key.
- Giao diện: admin/storefront không còn sử dụng `CANCELLATION_REQUESTED` hoặc `CANCELLATION_APPROVED`; đơn đã hủy và refund được theo dõi riêng.
- Kiểm thử: Flyway `clean → migrate → validate → migrate lại`, Hibernate schema validation và negative test constraint/idempotency/append-only đạt **5/5 test 1B** trên PostgreSQL local 18.4. Bộ xác nhận gồm 1A, 1B và financial policy đạt **13/13 test**; backend compile và TypeScript typecheck của admin/storefront đều đạt.
- Kiểm thử integration: `OrderIntegrationTest` 26/26, `PaymentIntegrationTest` 23/23, `InventoryIntegrationTest` 16/16 và `ReturnRefundIntegrationTest` 15/15; tổng **80/80** trên PostgreSQL Testcontainers. Các ca bao phủ checkout cạnh tranh, callback lặp/muộn, hủy đồng thời, giữ/trừ/nhả/hoàn tồn, trả hàng và hoàn tiền.
- Kết luận: **migration, entity/service và integration/concurrency Commerce 1B đã hoàn thành local và baseline 1A–1B đã triển khai lên Supabase**. Ba migration V2 `V1 → V3` được áp dụng thành công trên PostgreSQL 17.11 và `flyway validate` đạt ngày 10/10/2026. Runtime mặc định đã chuyển sang location V2; các module ngoài baseline phải được migrate hoặc loại khỏi runtime trước khi nghiệm thu toàn bộ ứng dụng.
- Kiểm tra sau khi bỏ migration legacy: Flyway tạo thành công schema V2 đến `v3` trên PostgreSQL Testcontainers, sau đó Hibernate chủ động dừng ở entity ngoài baseline `association_rule_rebuild_logs`. Đây là chốt an toàn đúng thiết kế: không tự tái tạo bảng legacy; cần quyết định loại module recommendation cũ khỏi runtime hoặc ánh xạ nó vào 1C trước khi chạy toàn bộ Spring context.

## 5. Review 1C — AI Retrieval (`ai_retrieval`, owner `ai_retrieval_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `product_search_documents` | Read model theo product; source version chống event cũ; GIN search vector; không là nguồn giá/tồn | Event lặp/đảo thứ tự; inactive product; rebuild cùng kết quả | Đạt thiết kế |
| `search_variant_facets` | Read model theo variant để filter size/màu/giá cùng SKU; hydrate lại Commerce | Facet stale; filter chéo SKU bị chặn; source version | Đạt thiết kế |
| `embedding_models` | Registry modality/model revision/dimension/metric/preprocess; một active/modality; immutable khi đã dùng | Sai dimension/modality; activate hai model; đổi preprocess tạo version | Đạt thiết kế |
| `product_text_embeddings` | PK product/model; vector(1024), document hash/status; BGE-M3 normalized | Hash stale; dimension sai; retry lỗi; exact cosine | Đạt thiết kế |
| `product_image_embeddings` | PK image/model; vector(768), Cloudinary asset version/hash; SigLIP2 normalized | URL/checksum/content type sai; ảnh thay/xóa; dimension sai | Đạt thiết kế |
| `knowledge_documents` | Code/version UQ; source/checksum/effective/status; version bất biến | Hai version active; source đổi; deactivate không phá evidence cũ | Đạt thiết kế |
| `knowledge_chunks` | UQ document/chunk index; content hash; FK cascade/restrict theo lifecycle version | Chunk order/hash; re-chunk tạo document version mới | Đạt thiết kế |
| `knowledge_embeddings` | PK chunk/model; vector(1024), content hash; chỉ READY khi khớp | Hash stale; model sai; exact retrieval | Đạt thiết kế |
| `query_prompt_versions` | Task/version/hash UQ; prompt/schema/few-shot immutable | Prompt sửa tại chỗ bị chặn; JSON Schema invalid; active version | Đạt thiết kế |
| `query_analysis_logs` | Request UQ; chỉ hash query vận hành; parser/model/schema/validity/latency/cost; TTL 30 ngày | Fallback rule; timeout/schema/taxonomy invalid; cleanup | Đạt thiết kế |
| `indexing_jobs` | Trạng thái/count/time/error; model/job type; theo dõi batch | Count nhất quán; resume/retry; job terminal bất biến | Đạt thiết kế |
| `indexing_job_items` | UQ job/entity; status/attempt/error; FK job | Retry một item; hai worker; tổng job khớp item | Đạt thiết kế |
| `processed_events` | PK consumer/event; ghi cùng transaction read model | Consumer crash/retry; event duplicate/đảo thứ tự | Đạt thiết kế |
| `recommendation_models` | Algorithm/version UQ; config/training cutoff/checksum; version bất biến | Không dùng future event; activate đúng model; popularity baseline | Đạt thiết kế |
| `recommendation_requests` | Logical pseudonymous user/context/model; TTL 30 ngày | Không PII; cleanup; model/context hợp lệ | Đạt thiết kế |
| `recommendation_items` | PK request/rank; UQ request/product; score/reason | Rank trùng; product trùng; thứ tự ổn định | Đạt thiết kế |
| `usage_events` | Provider/model/operation/count/tokens/pixels/cost/latency/success; TTL 90 ngày | Giá trị âm; không PII; aggregation và cleanup | Đạt thiết kế |

## 6. Review 1D — Assistant (`assistant`, owner `assistant_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `assistant_prompt_versions` | Task/version/hash UQ; prompt/tool policy/schema immutable | Prompt sửa tại chỗ; schema/tool policy invalid; version active | Đạt thiết kế |
| `chat_sessions` | XOR user ref/anonymous; status/time/expiry CHECK; TTL 90 ngày | Hai identity bị chặn; activity gia hạn; cleanup cascade | Đạt thiết kế |
| `chat_messages` | FK session cascade; composite UQ id/session; role/content/prompt/model/request metadata | Message sai session; retry request; role/prompt consistency | Đạt thiết kế |
| `conversation_summaries` | PK session; composite FK covered message cùng session | Trỏ message session khác; rebuild summary; cascade | Đạt thiết kế |
| `message_evidence` | PK message/rank; logical document/chunk/product refs + hash/version/score | Evidence sai message; rank trùng; nguồn cũ vẫn truy vết được | Đạt thiết kế |
| `assistant_feedback` | Một feedback/message; rating CHECK; cascade; aggregate ẩn danh giữ riêng | Feedback trùng/sai rating; session cleanup | Đạt thiết kế |
| `tool_execution_logs` | Composite FK message/session; UQ request/tool; redacted result; TTL 30 ngày | Retry tool; message sai session; secret/PII không được lưu | Đạt thiết kế |

## 7. Review 1D — Evaluation (`evaluation`, owner `ai_retrieval_app`)

| Bảng | Kết luận review | Kiểm thử bắt buộc | Trạng thái |
|---|---|---|---|
| `datasets` | Name/version/split UQ; task/split/status/manifest/protocol; FROZEN bất biến | DEV/TEST tách; checksum; freeze rồi sửa bị chặn | Đạt thiết kế |
| `prompt_artifacts` | Snapshot prompt bất biến, owner/task/hash UQ; không FK xuyên schema | Copy đúng hash/version; artifact đã dùng không sửa | Đạt thiết kế |
| `evaluation_cases` | FK dataset + composite UQ id/dataset; trường theo task; judgment completeness | Case sai task fields; image độc lập; COMPLETE có mẫu số hợp lệ | Đạt thiết kế |
| `relevance_labels` | PK case/target; grade 0–3; reviewer/time; target logical | Grade sai; label trùng; chưa xét không mặc định grade 0 | Đạt thiết kế |
| `recommendation_splits` | Một split/dataset; temporal ordering; interaction/candidate manifest checksum | Leakage tương lai; candidate thay đổi; checksum sai | Đạt thiết kế |
| `evaluation_runs` | Composite UQ id/dataset; model/prompt/config/commit/seed/retention/status/count/cost | Run thiếu artifact; status/time/count sai; DEV/TEST retention | Đạt thiết kế |
| `evaluation_results` | Có dataset_id; composite FK run/case cùng dataset; toàn bộ rank/output/answer/evidence/tool trace | Result case khác dataset bị chặn; rank trùng; tính lại metric | Đạt thiết kế |
| `evaluation_metrics` | FK run cascade; UQ metric/k/slice; sample/k CHECK | Metric trùng; sample count sai; không publish run chưa xong | Đạt thiết kế |

## 8. Đợt 2 — không chặn baseline

| Nhóm/bảng | Điều kiện mở lại | Trạng thái |
|---|---|---|
| `shipping_methods`, `shipments` | Có carrier, tracking hoặc nhiều lần giao thật | Để sau |
| `warehouses`, `inventory_balances` | Có kho vật lý thứ hai và kế hoạch chuyển số dư | Để sau |
| `stock_alerts`, aggregate/materialized view báo cáo | Có quy trình xác nhận cảnh báo hoặc số liệu `EXPLAIN` chứng minh cần | Để sau |
| Wishlist, review, promotion, coupon, combo, banner/CMS | Nghiệp vụ được duyệt và có owner/test/retention riêng | Để sau |

## 9. Điều kiện đóng task review

Review thiết kế từng bảng đã hoàn thành và được Chủ dự án xác nhận ngày 09/10/2026. Trước khi chuyển trạng thái sang **Đã kiểm thử**, nhóm còn phải:

1. Ghi người thực hiện + người review cho migration trong task/PR triển khai.
2. Vẽ/cập nhật ERD từ data dictionary đã chốt.
3. Chuyển ràng buộc “trigger hoặc service” thành lựa chọn cụ thể trong migration/code; ưu tiên DB cho tính nhất quán liên hàng/liên bảng quan trọng.
4. Viết baseline migration theo 1A → 1B → 1C → 1D và không chạy migration legacy.
5. Chạy migration từ PostgreSQL local sạch; chạy negative/concurrency/idempotency/retention test đã liệt kê.
6. Chỉ sau khi review kết quả test và rollback plan mới chạy một lần lên Supabase demo.
