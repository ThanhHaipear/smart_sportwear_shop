# sales-service

Spring Boot/Java 21 service owning carts, orders, payments, SePay reconciliation, shipping, returns, refunds and financial notifications in schema `sales`.

## Safety boundary

- Identity, product and variant IDs are external references; there are no cross-service foreign keys or source imports.
- Checkout obtains immutable price/name/SKU snapshots and an inventory reservation from `catalog-service`.
- Order, payment, webhook ingestion, reconciliation, refund status and transactional outbox changes commit in local database transactions.
- SePay HMAC-SHA256 covers `timestamp + '.' + exact raw request bytes`; timestamp tolerance and constant-time digest comparison are mandatory.
- `(source, external_transaction_id)` and advisory transaction locking make duplicate webhook delivery idempotent.
- Late, mismatched and wrong-account transfers are retained for review and never mark an order paid.
- Runtime role `sales_app` can write only schema `sales`; migrations use a separate privileged credential.

## Run and verify

```powershell
..\backend\mvnw.cmd -f pom.xml test
powershell -ExecutionPolicy Bypass -File ..\scripts\verify-microservice-phase-8.ps1 -ImplementationOnly
```

Health/readiness/metrics: `/actuator/health`, `/actuator/health/readiness`, `/actuator/prometheus`.

## Cut-over

Keep `SALES_EVENTS_ENABLED=false`, `SEPAY_WEBHOOK_ENABLED=false` and the public route on the old core until schema acceptance, Catalog shadow/reservation tests and an approved writer switch. Stop the old order/SePay writers before enabling the new ones. Rollback routes to the previous core release, disables new writers and preserves both schemas for investigation; do not reverse-copy or drop tables in the rollback window.
