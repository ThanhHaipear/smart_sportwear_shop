# Image Search DEV v1

**Status:** `FROZEN`

**Version:** `image-search-dev-v1:1.0.0`

**Seed:** `20261024`

**Catalog snapshot:** `image-catalog-v2-2026-10-23`

DEV v1 contains 55 queries: ten each for tops, bottoms, uniforms, shoes and accessories, plus five unknown/negative queries. Positive cases are deterministic camera-like transformations of the frozen catalog and cover crop, angle, background, brightness, contrast, color, noise, blur and JPEG compression.

This split is only for choosing preprocessing, similarity threshold, candidate limit and grouping rules. It must not be reported as evidence of real-world generalization and must never be reused as TEST. TEST v1 must use independently sourced photos, remove exact/near duplicates and exclude every `sourceGroupCode` present here.

Generated binaries are intentionally ignored by Git. Rebuild them from the repository root with:

```powershell
services\search-service\.venv\Scripts\python.exe datasets\image-search\dev-v1\build_dataset.py
```

Committed reproducibility artifacts:

- `manifest.json`: source, provenance, transform seed, checksum and distribution.
- `labels.json`: complete product-level relevance judgments using grade 0–3 semantics.
- `assets.sha256`: checksums for all generated query files.
- `build_dataset.py`: deterministic builder and validation checks.

The five negative cases reuse legacy repository UI assets. Their license is recorded as `PROJECT_LEGACY_ASSET_REVIEW_REQUIRED`; do not redistribute those binaries outside the project without completing a rights review.
