# Visual search rollout and rollback

## Acceptance gate

Keep the public feature disabled until all of these are true:

1. Flyway migrations are applied and the configured model is the only `ACTIVE` model.
2. `/health/ready` reports the query database and active model ready; RabbitMQ is not a query-path dependency.
3. Admin coverage reports no `PENDING`, `PROCESSING`, `FAILED` or `MISSING` ACTIVE images and at least 98% READY coverage.
4. Before enabling the indexing worker, the admin operations endpoint reports that main, retry and DLQ queues and the outbox have no unexplained backlog.
5. The external-image benchmark passes with at least 100 images across the three searchable catalog strata (`tops`, `bottoms`, `shoes`), Recall@5 >= 80%, category accuracy >= 90%, and end-to-end p95 <= 3 seconds. `accessories` is explicitly N/A while the audited ACTIVE catalog contains zero accessory products with images; it becomes mandatory as soon as such a product is added.

Benchmark images must be independently sourced alternate views, not catalog files, duplicates, or direct crops. Keep the images outside Git; commit only an anonymized manifest/report when licensing permits. Run:

```powershell
cd visual-search-service
python scripts/benchmark_search.py ..\evidence\visual-search\benchmark-manifest.json `
  --output ..\evidence\visual-search\phase10-benchmark-report.json
```

The command calls the real internal HTTP endpoint, measures end-to-end latency, resolves the top result's catalog category from Supabase, and exits with code 2 when an acceptance gate fails. The report never contains image bytes or secrets.

For the current catalog, the user approved a deterministic synthetic robustness benchmark because no independent photos were available. `scripts/build_synthetic_benchmark.py` produces 100 camera-like transformations with source image IDs, fixed seeds and operation provenance; generated binaries remain outside Git. This validates transformation robustness only and must not be presented as evidence of real-world generalization. The 2026-08-01 run passed with Recall@1 98%, Recall@5 100%, category accuracy 100% and p95 2978.23 ms.

## Staged rollout

1. Deploy migrations, RabbitMQ topology, backend and visual service with the public/storefront feature disabled.
2. Verify readiness, active model, coverage, outbox and queue depth.
3. Run the benchmark and archive its manifest version and report.
4. Enable ADMIN/internal access and observe errors, latency, usage, cost and DLQ for one business day.
5. Enable the storefront for a small traffic cohort at the ingress/feature-management layer.
6. Expand only while p95, error rate, DLQ, monthly budget and search quality remain within the agreed limits.

Never expose RabbitMQ management port `15672`, the Voyage key, database credentials, or the internal service token publicly.

## Rollback

Disable visual search at the storefront/ingress first, then set `VISUAL_SEARCH_ENABLED=false` for the public path and restart affected services. Do not remove migrations, purge queues, or delete the active/previous model vectors during an incident. Commerce and text search remain independent. Preserve benchmark reports, usage events, outbox rows and DLQ messages for diagnosis; re-enable only after readiness and the acceptance gate pass again.

## Incident checks

- Provider timeout/429/5xx: leave commerce running, stop cohort expansion, inspect budget and delayed retries.
- Growing outbox: verify backend publisher confirms and RabbitMQ connectivity.
- Growing retry/DLQ: inspect a bounded sample before requeueing; fix permanent image-policy errors instead of retrying them.
- Coverage regression: run reconciliation dry-run, then enqueue a bounded batch through the normal outbox/RabbitMQ pipeline.
- Latency regression: compare provider latency and PostgreSQL exact-cosine query time before considering an ANN index.
