# Phase 4 - chatbot-service

## Kết quả

Phần triển khai Phase 4 đã hoàn thành. Việc cut-over production vẫn bị khóa bởi
exit gate Phase 3 và chưa áp dụng migration/role lên Supabase production.

## Ranh giới runtime

- RAG gọi `POST /internal/v1/search` của `search-service` bằng service token.
- Enrichment, chi tiết, SKU và related product đi qua Catalog API; API runtime
  không mở `DB_READ_URL` và không chạy SQL trên bảng thương mại.
- Database runtime duy nhất là `CHATBOT_DATABASE_URL` của role `chatbot_app`,
  với `search_path=chatbot,public` và pool tối đa 3 connection.
- `chatbot.chat_sessions`, `chatbot.chat_messages` và
  `chatbot.retrieval_logs` được tạo bởi migration riêng.
- Search candidate order được giữ nguyên sau khi enrichment; mỗi retrieval lưu
  query, candidate IDs, search mode và latency, không lưu token hay PII thừa.

## Vận hành

- API: `POST /chat`, `POST /chat/stream` và các endpoint admin hiện hữu.
- Probe: `GET /health/live`, `GET /health/ready`.
- Metric: `GET /metrics`.
- Correlation ID nhận/trả qua `X-Correlation-ID`; ID không hợp lệ được thay bằng UUID.
- Docker image chạy non-root; compose profile là `chatbot`.

## Bằng chứng gate

Ngày 2026-11-10:

- `chatbot-service`: `249 passed` (unit, integration, contract và boundary).
- Backend catalog contract: Maven compile thành công sau khi bổ sung `slug` vào
  index document (thay đổi tương thích cộng thêm).
- Boundary test xác nhận các runtime service không import commercial repository
  hoặc read pool.

Chạy lại:

```powershell
.\scripts\verify-microservice-phase-4.ps1 -ImplementationOnly
```

Acceptance gate mặc định còn chạy permission probe bằng DSN
`CHATBOT_PERMISSION_TEST_URL` của chính role `chatbot_app`.

## Deploy và rollback

1. Áp dụng `chatbot-service/migrations/V1__create_chatbot_schema.sql` bằng role migration.
2. Kiểm tra `chatbot_app` không ghi được ngoài schema `chatbot`.
3. Cấu hình Search/Catalog URL và token riêng, sau đó kiểm tra readiness.
4. Chuyển route chatbot bằng gateway/feature flag.

Rollback chỉ đổi route về chatbot release cũ. Giữ nguyên schema/session export;
không xóa bảng trong cửa sổ rollback. Dữ liệu retrieval trace có thể giữ lại để
đối chiếu và không phải nguồn dữ liệu thương mại.

## Trạng thái migration

- Schema `chatbot` và role `chatbot_owner`/`chatbot_app` đã được tạo trên
  Supabase được chấp thuận.
- `chatbot_app` không có superuser/createdb/createrole, ghi được schema riêng và
  không có `CREATE` trên `public`; write/deny probe đã rollback sạch.
- Phase 4 được đóng `DONE / CUTOVER_HELD`. Public cut-over vẫn giữ tắt cùng
  Search; session schema được giữ nguyên để smoke/cut-over sau.
