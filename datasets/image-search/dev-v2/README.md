# Image Search DEV v2

**Version:** `image-search-dev-v2:2.0.0`

DEV v2 is the real-domain tuning split created after TEST v1 exposed the
synthetic-to-real domain shift. It contains 60 independently sourced Wikimedia
Commons images: ten each for tops, bottoms, uniforms, shoes, accessories and
unknown queries.

This split may be used to tune preprocessing, color reranking, category
consensus weight, candidate limit and similarity threshold. It must never be
reported as final acceptance evidence or reused in TEST v2.

The builder rejects exact/near duplicates against the catalog, DEV v1 and TEST
v1, and rejects any `sourceGroupCode` shared with TEST v1.

```powershell
visual-search-service\.venv\Scripts\python.exe datasets\image-search\dev-v2\build_dataset.py --collect --contact-sheets
visual-search-service\.venv\Scripts\python.exe datasets\image-search\dev-v2\build_dataset.py
```

Generated image binaries and review sheets are ignored by Git. The manifest,
labels, provenance inventory, checksum list and duplicate report are committed.
