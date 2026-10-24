# Visual search rollout and rollback

## Acceptance gate

Keep the public feature disabled until all of these are true:

1. Flyway migrations are applied and the configured model is the only `ACTIVE` model.
2. `/health/ready` reports the query database and active model ready; RabbitMQ is not a query-path dependency.
3. Admin coverage reports no `PENDING`, `PROCESSING`, `FAILED` or `MISSING` ACTIVE images and 100% READY coverage for the frozen catalog snapshot.
4. Before enabling the indexing worker, the admin operations endpoint reports that main, retry and DLQ queues and the outbox have no unexplained backlog.
5. The frozen TEST benchmark passes with 100 positive images balanced across `tops`, `bottoms`, `uniforms`, `shoes` and `accessories`, plus 20 unknown queries. Recall@5 must be >= 80%, category accuracy >= 90%, leakage zero and p95 <= 3 seconds on recorded hardware.

Benchmark images must follow `datasets/image-search/test-v1/README.md`: independently sourced and licensed, not catalog/DEV files, exact or near duplicates, direct crops or synthetic catalog transformations. Keep image binaries outside Git and commit the frozen manifest, labels, checksums, review reports and evaluation artifacts. Use the similarity threshold locked by DEV; never tune it on TEST. Run:

```powershell
cd visual-search-service
python scripts/benchmark_search.py ..\datasets\image-search\test-v1 `
  --output-dir ..\evidence\image-search-v2\test-v1-final `
  --candidate-limit 20 --similarity-threshold 0.71233916
```

The command verifies the frozen dataset and checksums, calls the real internal HTTP endpoint, measures preprocessing/embedding/vector-search latency, resolves result metadata from Supabase, and exits with code 2 when an acceptance gate fails. It writes `ranked-results.json`, `metrics.json` and `benchmark-config.json`; none contains image bytes or secrets.

The frozen DEV v1 set is synthetic and may be used only for tuning and transformation-robustness checks. Its results must not be presented as final acceptance or evidence of real-world generalization.

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
