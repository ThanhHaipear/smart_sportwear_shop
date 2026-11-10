# Phase 8 - sales-service

## Trạng thái

Phase 8 đã hoàn thành implementation và Supabase acceptance ngày 10/11/2026, ở trạng thái `DONE / CUTOVER_HELD`.

Không có public route, event publisher hay SePay writer mới nào được bật. Core cũ vẫn là write authority cho đến cửa sổ cut-over được duyệt.

## Ranh giới runtime

- Spring Boot 3.5/Java 21 service độc lập tại `sales-service`, Docker image, health/readiness/metrics và pool 2-4 connection riêng.
- Sở hữu schema `sales`, owner `sales_owner`, runtime role `sales_app` và migration V1/V2 riêng.
- Sở hữu cart, order/item/history, payment/event, bank transaction, shipping, return/refund, financial notification và transactional outbox.
- `user_ref`, `product_ref`, `variant_ref`, `actor_ref` là external reference; không có FK, entity, repository hoặc source import xuyên Identity/Catalog.
- Checkout lấy sale snapshot và reservation qua Catalog API. Nếu commit order lỗi thì reservation được release; nếu confirm reservation lỗi sau commit thì Sales hủy order/payment bằng transaction bù và phát `sales.order.cancelled.v1`.

## Tính đúng đắn tài chính

- Order/payment/refund và outbox tương ứng được ghi trong transaction cục bộ của Sales.
- HMAC SePay dùng exact raw bytes theo `timestamp + '.' + body`, giới hạn timestamp và so sánh constant-time.
- Advisory transaction lock cùng unique `(source, external_transaction_id)` đảm bảo webhook trùng idempotent; HTTP thành công chỉ được tạo sau khi `@Transactional` ingestion commit.
- Payment chỉ được match khi account, reference, amount, trạng thái và expiry đều đúng. Late payment, sai số tiền, sai tài khoản và transfer không match được lưu để đối soát nhưng không đổi trạng thái paid.
- Refund không thể vượt captured amount; outgoing SePay chỉ hoàn tất refund khi reference, amount và destination đầy đủ đều khớp.
- Transactional outbox phát contract v1 cho order created/cancelled, payment completed và refund completed.

## Bằng chứng implementation

Chạy lại:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-microservice-phase-8.ps1 -ImplementationOnly
```

Gate kiểm tra test domain COD/VietQR/reservation, rollback/compensation, webhook duplicate, late payment, amount mismatch, refund; exact-byte HMAC; source boundary; migration permission invariant; REST/event contract và Docker Compose surface.

## Supabase acceptance

Acceptance đã được thực thi bằng:

```powershell
.\chatbot-service\.venv\Scripts\python.exe scripts\maintenance\apply-phase-8-supabase.py
```

Kết quả:

- Đã tạo `sales_owner`, `sales_app` và schema `sales`; runtime role không có superuser/createdb/createrole, có `USAGE` trên `sales` và không có `CREATE` trên `public`.
- Write-own/deny-public probe đạt bằng chính runtime credential; credential được ghi vào `sales-service/.env` đã gitignore và không xuất hiện trong evidence.
- Backfill khớp legacy: 17 carts, 0 cart items, 3 orders, 3 order items, 6 status history, 3 payments, 2 payment events, 0 returns/items, 1 refund, 3 bank transactions, 0 shipping methods/shipments và 2 notifications.
- Bảng legacy không tồn tại được đối chiếu count 0; mọi bảng legacy tồn tại đều được copy idempotent và giữ nguyên cho rollback.
- Evidence: `evidence/microservice-phase-8-supabase-2026-11-10.json`.
- `SALES_EVENTS_ENABLED=false`, `SEPAY_WEBHOOK_ENABLED=false` và public route cut-over tiếp tục tắt.

## Cut-over và rollback

Trước cut-over phải đạt shadow response, Catalog reservation concurrency, COD/VietQR/duplicate/late/refund smoke và xác minh JWT tại public entrypoint. Dừng writer order/payment/SePay ở core trước khi bật writer mới; không dual-write. Rollback bằng cách tắt route/events/SePay mới, route về core release trước và giữ nguyên cả schema legacy lẫn `sales` để điều tra; không reverse-copy hoặc drop trong cửa sổ rollback.
