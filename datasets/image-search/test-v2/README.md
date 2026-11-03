# Image Search TEST v2 Candidate

**Version:** `image-search-test-v2:2.0.0-rc1`

**Status:** `REVIEW_REQUIRED`

TEST v2 is an independent acceptance candidate. Its assets and source groups
must not overlap the catalog, DEV v1, DEV v2, or TEST v1. It contains 100
positive queries balanced across five catalog groups and 20 unknown queries.

The final benchmark is intentionally blocked until another project team member
reviews the contact sheets, provenance, relevance judgments, and duplicate
report, records sign-off in `review-report.json`, changes the dataset status to
`FROZEN`, and locks the final artifact hashes. TEST v2 must then be run exactly
once with the threshold and ranking configuration frozen by DEV v2.2.

```powershell
visual-search-service\.venv\Scripts\python.exe datasets\image-search\test-v2\build_dataset.py --collect --contact-sheets
visual-search-service\.venv\Scripts\python.exe datasets\image-search\test-v2\build_dataset.py --contact-sheets
```

Image binaries and contact sheets remain ignored. Manifests, labels,
provenance, checksums, duplicate decisions, and review state are committed.
