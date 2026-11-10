# identity-service

Spring Boot service owning authentication, user profiles, addresses and tokens in schema `identity`.

## Runtime boundary

- Runtime role `identity_app`, explicit `search_path=identity`, pool 2-4 connections.
- Flyway uses a separate migration credential. Runtime cannot create or write in `public`.
- Password and Google login, one-time refresh-token rotation/reuse detection, logout and one-time password reset.
- Access tokens contain only `sub`, `role`, `type`, time claims and `jti`; other services use claims/external user references rather than reading identity tables.
- `identity.user.created.v1` and `identity.user.updated.v1` are written to the transactional outbox.
- Health/readiness/metrics and `X-Correlation-Id` are available independently on port 8093.

## Token migration and key rotation

At first cut-over, set `JWT_ACCESS_SECRET` and `JWT_REFRESH_SECRET` to the exact legacy core values. V2 copies active token hashes, so existing refresh tokens remain usable. To rotate, deploy the new current secrets and put the former values in `JWT_PREVIOUS_ACCESS_SECRET` and `JWT_PREVIOUS_REFRESH_SECRET`. After the maximum old access/refresh TTL has elapsed, clear the previous values. Never rotate both and remove the old verification key in one deployment.

## Deploy / cut-over

1. Complete backup/restore drill; create separate `identity_owner` migration and `identity_app` login secrets.
2. Apply V1/V2, compare count/checksum for all five legacy tables, then run the permission probe.
3. Run login, refresh rotation/reuse, Google audience, role and legacy-token smoke tests in shadow/staging.
4. Route Identity endpoints to this service in one controlled window. Keep the legacy writer stopped; do not dual-write.
5. Enable identity events only after queues/bindings are ready and observe error rate, refresh failures, pool usage and outbox lag.

Rollback: route auth/profile/address traffic to the previous core release, stop the new writer first, and restore the exact legacy JWT secrets. Keep both schemas and token rows for investigation; do not reverse-copy or drop tables during the rollback window.
