# recommendation-service

Phase 5 runtime for association-rule and popularity recommendations. The service owns
schema `recommendation`; catalog product snapshots and order baskets are derived read
models received through versioned events. It never reads Commerce repositories/tables.

## Run

```powershell
python -m venv .venv
.\.venv\Scripts\pip.exe install -r requirements-test.txt
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8091
```

The service starts with `RECOMMENDATION_ENABLED=false`. Apply the migration using the
migration role, configure `recommendation_app`, run the permission probe, then enable API
and worker. Rebuild a model with `python scripts/rebuild_model.py association-v1`.

Public routes include product/cart recommendations and interaction ingestion. Internal
event, rebuild, and evaluation routes require `X-Internal-Service-Token`.

Rollback is route-only: disable the feature flag and use the popularity/empty fallback.
Keep the schema and event queue for replay; do not delete derived data during rollback.
