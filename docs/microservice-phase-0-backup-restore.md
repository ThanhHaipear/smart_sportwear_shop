# Phase 0 Supabase backup and restore evidence

Ngày thực hiện: 09/11/2026

Trạng thái: `PASS`

## Phạm vi

- Nguồn: Supabase PostgreSQL 17.11 production.
- Schema ứng dụng: `public`, `ai_retrieval`.
- Định dạng nguồn: PostgreSQL custom archive.
- Image dùng để dump và restore: `pgvector/pgvector:pg17`.
- Production chỉ bị đọc; restore được thực hiện trong container tạm và container đã bị xóa sau kiểm tra.

## Artifact cục bộ

Các file nằm trong `backups/microservice-phase-0/` và bị loại khỏi Git:

| Artifact | Mục đích |
|---|---|
| `supabase-v13-20261109-145703.dump.aes` | Backup đã mã hóa AES |
| `supabase-v13-20261109-145703.key.dpapi` | Khóa AES được bảo vệ bằng Windows DPAPI `CurrentUser` |
| `supabase-v13-20261109-145703.toc.txt` | Danh mục 474 đối tượng trong archive |
| `supabase-v13-20261109-145703.restore-evidence.json` | Hash và kết quả đối chiếu sau restore |

Checksum SHA-256:

- Plaintext archive trước khi xóa: `58C0CB25E7FE26C9D44CFD7B625788900224C04C81832FE3EEA0C651D79C82C5`
- Encrypted archive: `F1971CEB601117C518DD3CC8E31971C7E84BC54E3B339D3B77192598F085290F`

Plaintext archive và bản giải mã tạm đã bị xóa sau khi checksum khớp.

## Kết quả restore

| Kiểm tra | Kết quả |
|---|---:|
| Flyway version cao nhất | 13 |
| Products | 36 |
| Active products | 36 |
| Product images | 81 |
| READY image embeddings | 81 |
| PUBLISHED outbox events | 489 |

## Chạy lại

```powershell
.\scripts\maintenance\backup-restore-supabase.ps1
```

Script đọc thông tin kết nối từ `backend/.env`, chỉ dump hai schema ứng dụng, mã hóa archive, restore vào PostgreSQL/pgvector tạm, chạy truy vấn đối chiếu và dọn container/plaintext trong khối `finally`.

## Giới hạn phục hồi

Khóa được DPAPI bảo vệ cho Windows user hiện tại. Cần sao lưu cả file `.dump.aes` và `.key.dpapi`; bản sao này chỉ giải mã được dưới cùng Windows user/machine. Trước khi dùng cho disaster recovery ngoài máy phát triển, phải chuyển sang secret manager hoặc khóa mã hóa có escrow độc lập.
