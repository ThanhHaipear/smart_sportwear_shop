# Phase 12 - Nghiệm thu hoàn tất tách microservice

## Trạng thái

`DONE / LOCAL_ACCEPTANCE` từ ngày 11/11/2026.

Phase 12 chốt phần implementation và nghiệm thu cục bộ của kiến trúc sáu service. Phase này không tự động
chạy cleanup V3, không thay thế E2E trên môi trường acceptance và không tự phê duyệt stability window của
Phase 10-11. Các thao tác đó vẫn fail-closed vì có thể làm mất đường rollback hoặc tác động dữ liệu thật.

## Phạm vi đã hoàn tất

- Sáu service có Dockerfile, migration/schema sở hữu, runtime role, permission probe và README vận hành riêng.
- Sáu REST API có OpenAPI contract v1 trong `contracts/rest`; event contract tiếp tục được version hóa trong
  `contracts/*.schema.json`.
- Compose có sáu deployment độc lập và một gateway public; không còn `legacy_upstream`, cut-over flag hay route
  fallback về backend cũ.
- Gate tổng hợp kiểm tra artifact, contract, migration/role, permission probe, regression test, frontend và
  cấu hình Compose bằng một lệnh duy nhất.
- Bằng chứng máy đọc được lưu tại
  `evidence/microservice-phase-12-local-acceptance-2026-11-11.json`.

## Đối chiếu tiêu chí hoàn thành service

| Tiêu chí | Bằng chứng Phase 12 |
|---|---|
| Build/triển khai độc lập | Dockerfile riêng và service riêng trong Compose cho Search, Chatbot, Recommendation, Catalog, Identity, Sales |
| API/event contract version hóa | 6 OpenAPI v1 và JSON Schema event v1 trong `contracts` |
| Schema/migration riêng | `ai_search`, `chatbot`, `recommendation`, `catalog`, `identity`, `sales` có migration riêng |
| Không ghi dữ liệu service khác | Boundary test của từng service và Phase 10 ownership audit |
| Không import source/entity/repository service khác | Boundary test của sáu service đạt |
| Unit/integration/contract/smoke | Test service, gateway, frontend và các gate Phase 3-11 |
| Health/readiness/log/metrics/correlation ID | Contract runtime của từng service và observability gate Phase 9 |
| Timeout/retry/idempotency | Client timeout, outbox/inbox, retry/DLQ và duplicate-event test từ Phase 2-8 |
| Deploy/rollback/runbook | README từng service và tài liệu Phase 3-11 |
| Role không ghi ngoài schema | Sáu permission probe; live ownership audit Phase 10 đạt ngày 11/11/2026 |

## Kết quả gate

Lần chạy đầy đủ ngày 11/11/2026 đạt `20/20` check:

- Search `11 passed`; Chatbot `249 passed`; Recommendation `11 passed`.
- Catalog `4 passed`; Identity `6 passed`; Sales `10 passed`.
- Gateway `12 passed`.
- Storefront typecheck và Admin lint đạt.
- `docker compose config --quiet` đạt.

Chatbot còn một cảnh báo deprecation từ adapter `fastapi.testclient`/`httpx`; cảnh báo không làm thay đổi kết
quả test và được giữ làm backlog nâng dependency, không phải lỗi Phase 12.

## Cách chạy lại

Gate tĩnh, không cần dependency runtime:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-12.ps1 -StaticOnly
```

Gate đầy đủ và xuất evidence:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-12.ps1 `
  -EvidencePath evidence/microservice-phase-12-local-acceptance.json
```

Permission probe live chỉ chạy khi sáu biến `PHASE12_<SERVICE>_DATABASE_URL` đã trỏ tới runtime role tương ứng.
Các giá trị phải là PostgreSQL DSN (`postgresql://...`), không phải JDBC URL hay migration-owner DSN:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-12.ps1 `
  -LivePermissions -EvidencePath evidence/microservice-phase-12-live-acceptance.json
```

`-LivePermissions` dừng ngay nếu thiếu một DSN. Probe chỉ ghi trong transaction kiểm tra và xác nhận bị từ chối
ghi `public`; không chạy migration hoặc cleanup.

## Ranh giới với finalization production

Phase 12 xác nhận codebase đã đạt kiến trúc microservice mục tiêu ở local acceptance. Trước khi đổi Phase 10 và
Phase 11 thành `DONE` ở môi trường triển khai vẫn bắt buộc:

1. chạy E2E COD/VietQR/cancel/return/refund trên database acceptance riêng;
2. xác nhận writer/job/consumer legacy đã dừng và chạy incremental parity cuối;
3. kết thúc stability window theo phê duyệt vận hành;
4. tạo và restore thử restore point mới;
5. chỉ sau bốn gate trên mới chạy V3 thủ công và audit `--require-legacy-retired`.

Không được dùng trạng thái `DONE / LOCAL_ACCEPTANCE` của Phase 12 để bỏ qua các gate dữ liệu này.
