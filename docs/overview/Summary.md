# Summary — Tóm tắt đồ án

**Đề tài:** Hệ thống thương mại điện tử trang phục thể thao tích hợp AI (Smart Sportswear Shop).
**Nhóm:** Dũng – Hải – Quyền.
**Cập nhật:** 16/11/2026, theo code nhánh `main` sau khi tách microservice (nghiệm thu local Phase 12 ngày 11/11/2026).

---

## 1. Đồ án làm gì?

Một website bán đồ thể thao (áo, quần, giày, đồng phục bóng đá, phụ kiện cho **bóng đá** và **chạy bộ**) gồm hai phần:

1. **Thương mại điện tử:** khách xem sản phẩm, yêu thích, thêm giỏ, đặt hàng, thanh toán **COD** hoặc **chuyển khoản VietQR** (xác nhận tự động qua SePay), theo dõi và hủy đơn, trả hàng, nhận hoàn tiền. Nhân viên quản lý sản phẩm, kho, đơn hàng, trả hàng, hoàn tiền, báo cáo.
2. **AI:** giúp khách tìm đúng sản phẩm nhanh hơn:
   - Tìm kiếm hybrid (từ khóa + ngữ nghĩa).
   - Tìm kiếm bằng hình ảnh (đã có pipeline, đang tạm tắt trên website).
   - Chatbot tư vấn.
   - Gợi ý sản phẩm "thường mua cùng".
   - Đánh giá bằng số liệu: Precision@K, Recall@K, Hit Rate@K, NDCG@K, MRR.

---

## 2. Kiến trúc và công nghệ

Hệ thống đã chuyển từ **một backend monolith** sang **sáu microservice + một API Gateway**. Mỗi service có schema riêng trên cùng một project Supabase.

```text
Storefront :3000 ─┐
                  ├──> Gateway :8085
Admin      :3001 ─┘       ├── Identity       :8093 ──> schema identity
                           ├── Catalog        :8092 ──> schema catalog
                           ├── Sales          :8094 ──> schema sales
                           ├── Chatbot        :8002 ──> schema chatbot
                           ├── Recommendation :8091 ──> schema recommendation
                           └── Search         :8090 ──> schema ai_search
Redis :6379 (phiên chatbot)   RabbitMQ :5672 (sự kiện, worker)
```

| Thành phần | Công nghệ | Port |
|---|---|---|
| Storefront | Next.js 16, React 19, TypeScript, Tailwind CSS 4 | 3000 |
| Admin | Next.js 15, React 19, TypeScript | 3001 |
| API Gateway | Python FastAPI + HTTPX | 8085 |
| Identity, Catalog, Sales | Spring Boot 3.5, Java 21, JDBC (`JdbcClient`) | 8093, 8092, 8094 |
| Search (+ worker) | Python FastAPI, PostgreSQL full-text + pgvector | 8090 |
| Recommendation (+ worker) | Python FastAPI, psycopg | 8091 |
| Chatbot | Python FastAPI, LangGraph, Claude (mặc định) hoặc OpenAI | 8002 |
| Database | PostgreSQL trên **Supabase**, extension **pgvector** | — |
| Cache, phiên chatbot | Redis (Docker) | 6379 |
| Message queue | RabbitMQ (Docker) | 5672 / 15672 |
| Model AI | **OpenAI `text-embedding-3-small`** (văn bản, 1536 chiều); **SigLIP2** `google/siglip2-base-patch16-224` (ảnh, 768 chiều, chạy local) | — |

Chạy local: `.\scripts\run\start-all-local.cmd` (log ở `logs/local`). Cấu hình chung trong file không commit `.env.phase10.local`.

---

## 3. Chức năng chính

### 3.1. Khách hàng (Storefront)

| Nhóm | Chức năng |
|---|---|
| Tài khoản | Đăng ký, đăng nhập email/mật khẩu hoặc Google, quên và đặt lại mật khẩu, hồ sơ, sổ địa chỉ |
| Sản phẩm | Duyệt theo danh mục, thương hiệu, lọc; xem chi tiết (size, màu, giá, tồn kho theo SKU); đánh giá sản phẩm đã mua; yêu thích |
| Tìm kiếm | Tìm theo từ khóa và gợi ý khi gõ (Catalog). Tìm hybrid nằm ở Search service, hiện dùng qua chatbot |
| Giỏ hàng | Khách chưa đăng nhập vẫn có giỏ (cookie `session_id`) |
| Đặt hàng | Xem trước, đặt hàng, thanh toán **COD** hoặc **VietQR**; hóa đơn; thông báo |
| Sau mua | Lịch sử đơn, hủy đơn, khai báo tài khoản nhận hoàn tiền, yêu cầu trả hàng |
| Gợi ý | Khối "thường mua cùng" ở trang chi tiết sản phẩm |
| Chatbot | Hỏi chính sách, tìm và gợi ý sản phẩm, tư vấn size, tra đơn, thêm giỏ, hủy đơn (có xác nhận) |

### 3.2. Quản trị (Admin)

| Nhóm | Chức năng |
|---|---|
| Sản phẩm | CRUD sản phẩm, variant (SKU), ảnh, danh mục, thương hiệu, duyệt đánh giá |
| Kho | Nhập, xuất, điều chỉnh tồn; sổ kho |
| Đơn hàng | Đổi trạng thái, hủy, in phiếu đóng gói hàng loạt |
| Trả hàng / hoàn tiền | Duyệt, nhận và đánh giá hàng trả, tạo và xác nhận hoàn tiền thủ công |
| Người dùng | Danh sách khách, đổi role và trạng thái |
| Báo cáo | Tổng quan, doanh thu, đơn hàng, sản phẩm, khách hàng, tồn kho |
| Hệ thống | Cấu hình site, mẫu thông báo, audit log |

Gateway chỉ cho role **`ADMIN`** gọi `/api/v1/admin/**`. Các role `SALES_STAFF`, `WAREHOUSE_STAFF` vẫn có trong DB nhưng hiện chưa vào được trang quản trị.

### 3.3. Chức năng AI

| Chức năng | Mô tả ngắn | Người phụ trách | Tình trạng |
|---|---|---|---|
| Hạ tầng index | Catalog ghi outbox → RabbitMQ → Search Worker gọi API Catalog lấy tài liệu, dựng `ai_search` | Chung | ✅ Có |
| Tìm kiếm văn bản hybrid | Full-text + cosine trên pgvector (HNSW), gộp RRF có trọng số; tự về chế độ từ khóa khi không có embedding | Dũng | ✅ Có ở Search service; ô tìm kiếm website vẫn dùng LIKE của Catalog |
| Chatbot | LangGraph 12 node, 9 tool, tri thức 6 file Markdown, lịch sử trong Redis + schema `chatbot` | Dũng | ✅ Có |
| Tìm kiếm bằng ảnh | SigLIP2 local, đồng thuận danh mục, ngưỡng chốt trên bộ DEV v2.2; bộ DEV/TEST trong `datasets/image-search/` | Hải | 🟡 Pipeline có, website đang feature hold |
| Recommendation | Luật kết hợp (support/confidence/lift) từ giỏ đơn hàng + bù bằng popularity; lọc sản phẩm ACTIVE, còn hàng; ghi request/item; API ghi hành vi; API đánh giá P@K, R@K | **Quyền** | 🟡 Đã chạy, còn nhiều việc (xem `Mission.md`) |

---

## 4. Đặc điểm kỹ thuật nổi bật (dùng khi bảo vệ)

- **Database per service:** mỗi service một schema và một role runtime chỉ có quyền trong schema đó. Không có khóa ngoại chéo service; tham chiếu bằng cột `*_ref`. Có permission probe tự động kiểm tra.
- **API Gateway một cửa:** kiểm tra JWT, chuyển `X-User-Id`/`X-User-Role` cho service, chặn `/internal/**`, rate limit, correlation ID xuyên suốt log.
- **Không bán vượt tồn kho:** Catalog khóa variant theo thứ tự UUID, kiểm tra `stock − reserved`, giữ hàng có `idempotencyKey`. Sales bù trừ (nhả hàng, hủy đơn) nếu bước sau lỗi.
- **Transactional outbox + idempotent consumer:** sự kiện ghi cùng transaction với dữ liệu; consumer ghi `processed_events`; retry 30s/5m/1h rồi DLQ.
- **Thanh toán an toàn:** webhook SePay xác thực HMAC-SHA256 kèm timestamp, chống xử lý trùng, giao dịch sai số tiền/quá hạn được giữ lại để đối soát tay, không tự đánh dấu đã trả.
- **AI tách thành service riêng:** AI lỗi hoặc tắt thì website vẫn chạy (`-NoAi` khi khởi động; frontend ẩn khối gợi ý nếu lỗi).
- **Tái lập kết quả AI:** model ảnh ghi revision cụ thể; bộ dữ liệu đánh giá ảnh có manifest và SHA-256; ngưỡng chốt trên DEV, không chỉnh theo TEST.

---

## 5. Cấu trúc thư mục

| Thư mục | Nội dung |
|---|---|
| `frontend/storefront/`, `frontend/admin/` | Web khách hàng, web quản trị |
| `gateway/` | API Gateway |
| `identity-service/`, `catalog-service/`, `sales-service/` | Ba service Spring Boot, mỗi cái có `db/migration` riêng |
| `services/search-service/` | Search API + indexing worker, migration `ai_search` |
| `recommendation-service/` | Recommendation API + worker, migration `recommendation` |
| `chatbot-service/` | Chatbot, tri thức `knowledge/`, migration `chatbot` |
| `contracts/` | OpenAPI (`rest/`) và JSON Schema cho sự kiện |
| `infra/` | Migration hoàn tất Phase 10 (`database/phase10`), cấu hình observability |
| `rabbitmq/` | Exchange, queue, retry, DLQ |
| `scripts/` | `run/` (launcher local), `setup/`, `maintenance/`, các gate `verify-microservice-phase-*.ps1` |
| `datasets/` | Bộ đánh giá tìm ảnh DEV/TEST |
| `catalog/` | Bộ dữ liệu sản phẩm để import |
| `docs/` | Tài liệu thiết kế, migration, runbook |
| `evidence/` | Kết quả benchmark và nghiệm thu |
| `backend/` | Chỉ còn file sót lại không thuộc git (monolith đã bị gỡ) |

Xem thêm: [WorkFlow.md](WorkFlow.md) · [Database Structure.md](Database%20Structure.md) · [Mission.md](Mission.md)
