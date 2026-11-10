# Phase 7 - identity-service

## Kết quả

Phase 7 đã hoàn thành implementation và Supabase acceptance gate ngày 10/11/2026, ở trạng thái
`DONE / CUTOVER_HELD`. Public route, write authority và `IDENTITY_EVENTS_ENABLED` tiếp tục giữ tắt;
core cũ vẫn là nguồn ghi chuẩn cho tới cửa sổ cut-over được duyệt.

## Ranh giới runtime

- Spring Boot 3.5/Java 21 service độc lập tại `identity-service`, Docker image, health/readiness/metrics riêng.
- Sở hữu schema `identity`, owner `identity_owner`, runtime role `identity_app`; pool mặc định 2-4.
- Sở hữu `users`, `user_identities`, `addresses`, `refresh_tokens`, `password_reset_tokens` và
  `integration_outbox`; không có FK hoặc import sang service khác.
- V2 backfill idempotent giữ nguyên năm bảng `public` để rollback, không rename/drop và không dual-write.
- Access JWT chứa `sub`, role và token type; service khác nhận identity bằng claim/external reference.
- `identity.user.created.v1` và `identity.user.updated.v1` được ghi transactional outbox cùng transaction.

## Authentication và migration token

- Password login dùng BCrypt; Google login kiểm tra `email_verified` và audience khi có `GOOGLE_CLIENT_ID`.
- Refresh token lưu SHA-256, rotate một lần; reuse sẽ thu hồi toàn bộ phiên active trong transaction được commit.
- Logout idempotent; password-reset token một lần và thu hồi toàn bộ refresh token khi đổi mật khẩu.
- Response auth dùng public projection nên không serialize `passwordHash`.
- Cut-over đầu tiên dùng đúng legacy `JWT_ACCESS_SECRET`/`JWT_REFRESH_SECRET` và backfill token hash, do đó
  token đang tồn tại tiếp tục hợp lệ.
- Khi xoay khóa, secret mới dùng để ký; secret cũ đặt ở `JWT_PREVIOUS_ACCESS_SECRET` và
  `JWT_PREVIOUS_REFRESH_SECRET` cho tới khi TTL cũ hết. `JwtRotationTest` chứng minh compatibility này.

## API và contract

REST contract: `contracts/rest/identity-service-v1.yaml`.

- `/api/v1/auth/register|login|google|refresh|logout|forgot-password|reset-password`
- `GET/PATCH /api/v1/users/me`
- CRUD/default cho `/api/v1/users/me/addresses`
- Admin role/status endpoint được chặn bởi JWT role `ADMIN`.

Event contract: `contracts/identity-user-event-v1.schema.json`; payload không chứa email, PII không cần thiết,
password, reset token hoặc JWT.

## Bằng chứng implementation

Chạy lại:

```powershell
.\scripts\verify-microservice-phase-7.ps1 -ImplementationOnly
```

Gate hiện tại đạt 6/6 tests:

- Integration: register/login, refresh rotation, reuse detection và session revocation.
- Security: response không lộ password hash; role claim được giữ trong JWT.
- Migration: token ký bằng previous key vẫn được xác thực sau rotation.
- Architecture: không import legacy core, migration không có FK sang `public`, runtime role bị revoke `public`.
- Contract/Compose: REST v1, user event v1 và profile `identity` tồn tại.

## Supabase acceptance

Acceptance đã được thực thi bằng:

```powershell
.\chatbot-service\.venv\Scripts\python.exe scripts\maintenance\apply-phase-7-supabase.py
```

- Đã tạo `identity_owner`, `identity_app` và schema `identity`.
- Backfill khớp legacy: 3 users, 1 user identity, 2 addresses, 18 refresh tokens và 0 password-reset tokens.
- `identity_app` không có superuser/createdb/createrole, có `USAGE` trên `identity`, không có `CREATE` trên `public`.
- Write-own/deny-public probe đạt bằng chính runtime credential.
- Script không in secret; runtime credential nằm trong `identity-service/.env` đã bị gitignore.
- Evidence: `evidence/microservice-phase-7-supabase-2026-11-10.json`.

## Cut-over và rollback

Chỉ route sang service mới sau login/refresh/Google/role/legacy-token smoke test và shadow comparison đạt.
Dừng writer cũ trước khi bật writer mới; không dual-write. Rollback bằng cách dừng writer mới, route về core
release cũ và phục hồi đúng legacy JWT secret. Giữ cả hai schema/token rows để điều tra; không reverse-copy
hoặc drop bảng trong cửa sổ rollback.
