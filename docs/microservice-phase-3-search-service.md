# Phase 3 - Unified search-service

## Kết luận

Ngày 10/11/2026, phần triển khai kiến trúc Phase 3 đã hoàn tất nhưng exit gate
chất lượng retrieval vẫn **BLOCKED**. Không đổi Supabase production và không bật
public rollout khi bộ TEST độc lập chưa đạt ngưỡng đã khóa.

## Sản phẩm bàn giao

- `services/search-service`: FastAPI độc lập, Docker image và cấu hình riêng.
- API hợp nhất: text/hybrid `POST /internal/v1/search`, image
  `POST /internal/v1/search/image`; giữ alias text cũ trong cửa sổ cut-over.
- Schema/role migration riêng `ai_search`, chỉ chứa document, facet, embedding,
  processed event và indexing job dẫn xuất.
- Catalog snapshot contract
  `GET /internal/v1/products/{id}/index-document`, bảo vệ bằng service token.
- Consumer hợp nhất `search.indexing`, retry 30s/5m/1h, DLQ và idempotency.
- Backfill dry-run mặc định, evaluation runner Recall/MRR/NDCG, health,
  readiness, metrics, correlation ID và runbook rollback.
- Spring image client chuyển sang `/internal/v1/search/image`; product search mặc
  định chuyển từ cổng chatbot `8002` sang search service `8090`.

## Ranh giới dữ liệu

Kiểm tra tĩnh xác nhận source của service mới không có SQL đọc/ghi trực tiếp
`products`, `product_images`, `product_variants`, `categories` hoặc `brands`.
Indexer chỉ lấy snapshot version hóa qua Catalog API sau khi nhận event. Runtime
role `search_app` được cấp quyền trên `ai_search` và bị thu hồi quyền schema
`public`. `ai_retrieval` được giữ nguyên, không rename/drop, để rollback release
cũ và đối chiếu trong giai đoạn cut-over.

## Bằng chứng kiểm thử

| Gate | Kết quả |
|---|---|
| Search service unit/contract | `7 passed` |
| Backend snapshot + image client contract | `12 passed` |
| Backend package | `BUILD SUCCESS` |
| RabbitMQ definitions | JSON hợp lệ, có main/retry/DLQ cho `search.indexing` |
| Direct catalog-table SQL trong service mới | `0` |
| Product hybrid baseline trước cut-over | `PASSED`: exact Recall@5 100%, semantic Recall@5 93,33%, p95 cold 7 ms; phải chạy shadow evaluation lại trên `ai_search` ở staging |
| Image TEST v2 | `FAILED`: Recall@5 73,75% (<80%), category 79% (<90%), p95 1.381,07 ms |

Nguồn quality gate: `evidence/product-hybrid-search/benchmark-final-runtime.json`
và `evidence/image-search-v2/test-v2-final/metrics.json`. Báo cáo product hybrid
là baseline của implementation trước cut-over, không phải bằng chứng staging cho
schema mới. TEST image v2 đã khóa và không được dùng để tune hoặc chạy lại tùy ý.

Full backend suite đã được gọi nhưng môi trường hiện tại không truy cập được
Docker named pipe và không có các URL database migration test, nên các test
Testcontainers không thể khởi tạo. Đây không được tính là gate đạt; các test
contract không cần container nêu trên vẫn đạt `12/12`.

## Rollout và rollback

1. Apply migration bằng migration role, kiểm tra `search_app` không ghi được
   ngoài `ai_search` trên môi trường staging.
2. Chạy backfill từ danh sách product ID qua Catalog API, đối chiếu count/hash.
3. Import/cập nhật RabbitMQ definitions (volume RabbitMQ hiện hữu không tự nạp
   lại file definitions), rồi bật worker, shadow text/image response và theo dõi queue/DLQ.
4. Chỉ chuyển traffic public sau khi một TEST độc lập mới đạt quality gate.
5. Rollback bằng cách trả `PRODUCT_SEARCH_SERVICE_URL` về chatbot cũ và
   `VISUAL_SEARCH_SERVICE_URL` về visual service cũ; không xóa `ai_search`,
   outbox, retry hoặc DLQ trong sự cố.

Chạy implementation gate:

```powershell
.\scripts\verify-microservice-phase-3.ps1
```
