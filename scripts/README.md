# Project scripts

Các script được chia theo mục đích:

- `run/`: khởi động hạ tầng, sáu microservice, hai worker, gateway và frontend.
- `setup/`: chuẩn bị công cụ phát triển trên máy local.
- `maintenance/`: migration, audit và bảo trì dữ liệu có kiểm soát.

Chạy lệnh từ thư mục gốc repository. File `.cmd` là launcher cho file PowerShell
`.ps1` cùng tên.

## Khởi động runtime hiện tại

```powershell
.\scripts\run\start-all-local.cmd
```

Chỉ chạy backend services và gateway:

```powershell
.\scripts\run\start-all-local.cmd -NoFrontends
```

Các thành phần cũng có thể được chạy riêng bằng launcher tương ứng trong
`scripts/run/`. Xem thứ tự phụ thuộc, cổng và cách đọc log trong `RUN_LOCAL.md`.

## Thiết lập

```powershell
.\scripts\setup\setup-local-tools.cmd
```

## Nghiệm thu microservice

```powershell
.\scripts\verify-microservice-phase-12.ps1 -StaticOnly
.\scripts\verify-microservice-phase-12.ps1 -EvidencePath evidence/microservice-phase-12-local-acceptance.json
```

Các script Phase 3-11 và `maintenance/` được giữ để audit lịch sử migration;
chúng không phải launcher của runtime hiện tại.
