# Image Search TEST v2

**Version:** `image-search-test-v2:2.0.0`

**Status:** `FROZEN`

TEST v2 is an independent acceptance candidate. Its assets and source groups
must not overlap the catalog, DEV v1, DEV v2, or TEST v1. It contains 100
positive queries balanced across five catalog groups and 20 unknown queries.

Dũng independently reviewed the dataset and Hải relayed the sign-off on
2026-11-03. The review is recorded in `review-report.json`; the dataset and its
artifact hashes are frozen. TEST v2 is authorized to run exactly once with the
threshold and ranking configuration frozen by DEV v2.2.

```powershell
services\search-service\.venv\Scripts\python.exe datasets\image-search\test-v2\build_dataset.py --collect --contact-sheets
services\search-service\.venv\Scripts\python.exe datasets\image-search\test-v2\build_dataset.py --contact-sheets
```

Image binaries and contact sheets remain ignored. Manifests, labels,
provenance, checksums, duplicate decisions, and review state are committed.
