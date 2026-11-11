# Project scripts

Các script được chia theo mục đích sử dụng:

- `run/`: khởi động backend, frontend và các dịch vụ hỗ trợ.
- `setup/`: chuẩn bị công cụ phát triển trên máy local.
- `maintenance/`: backfill và bảo trì dữ liệu; cần kiểm tra cấu hình trước khi chạy.

Chạy các lệnh từ thư mục gốc của repository. File `.cmd` là launcher tiện dụng
cho file PowerShell `.ps1` cùng tên.

## Khởi động

```powershell
.\scripts\run\start-backend.cmd
.\scripts\run\start-admin.cmd
.\scripts\run\start-storefront.cmd
.\scripts\run\start-chatbot-service.cmd
.\scripts\run\start-product-search-indexer.cmd
.\scripts\run\start-visual-search.cmd
.\scripts\run\start-sepay-tunnel.cmd
```

## Thiết lập

```powershell
.\scripts\setup\setup-local-tools.cmd
```

## Bảo trì dữ liệu

```powershell
.\scripts\maintenance\backfill-catalog-index.ps1
.\scripts\maintenance\backfill-product-embeddings.ps1
```

## Nghiệm thu microservice

Gate tổng hợp cuối của sáu service:

```powershell
.\scripts\verify-microservice-phase-12.ps1 -StaticOnly
.\scripts\verify-microservice-phase-12.ps1 -EvidencePath evidence/microservice-phase-12-local-acceptance.json
```

Xem điều kiện chạy permission probe live và các gate production còn giữ tại
`docs/microservice-phase-12-final-acceptance.md`.
