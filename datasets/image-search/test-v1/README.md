# Image Search TEST v1 Protocol

**Status:** `FROZEN`

**Version:** `image-search-test-v1:1.0.0`

**Frozen at:** `2026-10-24T14:28:53.558879+00:00`

This protocol is locked before any TEST result is inspected. TEST v1 is the final, independent generalization evaluation for the SigLIP2 V2 image-search pipeline. It must never be used to choose preprocessing, similarity threshold, candidate limit, grouping, model revision or relevance rules.

## Fixed composition

TEST v1 contains 120 queries:

| Group | Positive queries |
|---|---:|
| Tops | 20 |
| Bottoms | 20 |
| Uniforms | 20 |
| Shoes | 20 |
| Accessories | 20 |
| Unknown/negative | 20 |

Every positive query receives complete product-level judgments over the frozen 36-product catalog. Grade `3` means the exact product, grade `2` means a visually relevant substitute, grade `1` means marginal, and grade `0` means not relevant. Metrics treat `grade >= 2` as relevant. An unknown query has no catalog product graded `2` or `3`.

## Independence and rights rules

- Do not use a catalog image, a copy, a direct crop, or a synthetic transformation of a catalog image.
- Do not use any asset or `sourceGroupCode` present in the catalog snapshot or DEV v1.
- Use independently captured photos or independently sourced images with `OWNED`, compatible Creative Commons, or explicit project-use permission.
- Record the original URI or capture record, author/owner, license code, acquisition date and `sourceGroupCode` for every query.
- Multiple frames from one shoot, listing, video or original asset share one `sourceGroupCode`; source groups may not cross catalog, DEV and TEST.
- Query binaries stay outside Git under the ignored `assets/` directory. Provenance, labels, checksums and review reports are committed.

## Collection coverage

The positive set must cover front/side/angled views, indoor/outdoor or plain/complex backgrounds, lighting changes, partial crop, moderate occlusion, scale changes, blur and camera compression. No single product or source group may contribute more than 20% of a positive group. Unknowns must cover both non-product scenes and visually confusable objects.

## Leakage and quality gates

Before freeze:

1. Verify every file SHA-256 and required provenance/license field.
2. Reject exact checksum matches against catalog, DEV and other TEST queries.
3. Flag perceptual-hash distance `<= 6` or embedding cosine similarity `>= 0.98` for manual near-duplicate review.
4. Record every flagged pair and the reviewer decision; unresolved pairs are excluded.
5. Require readable images, supported MIME, and no corrupt or decompression-bomb asset.
6. Require all 120 judgments to be `COMPLETE` and reviewed. Disagreements on grade/category/color require adjudication.
7. Store expected category and, when visible and unambiguous, normalized color-family labels. Missing color labels are reported as N/A rather than guessed.

## Locked evaluation

- Dataset schema: `manifest.json` plus `labels.json`, matching DEV v1 schema. TEST labels may add `expectedColorFamilies` per case.
- Candidate limit: `20`.
- Report Precision@1/5/10, Recall@1/5/10, top-1/top-5 accuracy, category/color accuracy, retrieval/request failure rates, unknown rejection rate, latency P50/P95 and per-group slices.
- Final acceptance: Recall@5 `>= 0.80`, category accuracy `>= 0.90`, catalog embedding coverage `100%`, leakage `0`, and latency P95 `<= 3000 ms` on recorded hardware.
- The similarity threshold and all other runtime configuration must come from the frozen DEV report.
- Run TEST only after `manifest.json`, `labels.json`, `assets.sha256`, exact/near-duplicate report and reviewer sign-off are frozen. Do not retune after seeing TEST results.

Changing this protocol requires a new dataset version and must happen before collecting or evaluating that version.

## Frozen artifact

TEST v1 now contains 120 independently sourced Wikimedia Commons queries: 20 each for tops, bottoms, uniforms, shoes, accessories and unknown/negative cases. The images are licensed CC0, CC BY, CC BY-SA or Public Domain and their complete attribution is recorded in `source-inventory.json`.

The freeze includes:

- `manifest.json`: immutable sample metadata and catalog-snapshot binding.
- `labels.json`: 120 complete relevance judgments.
- `assets.sha256`: checksums for all query binaries.
- `source-inventory.json`: source URI, author, license and acquisition metadata.
- `duplicate-report.json`: exact SHA-256 and 64-bit dHash leakage checks against 136 catalog/DEV assets.
- `review-report.json`: category, subtype and provenance review outcome.

Reproduce validation without changing the frozen data:

```powershell
python datasets/image-search/test-v1/build_dataset.py
```

Regenerate ignored contact sheets for visual review:

```powershell
python datasets/image-search/test-v1/build_dataset.py --contact-sheets
```

`--collect` rebuilds the dataset from Commons and therefore creates a new freeze timestamp and artifact hashes. It must not be run after final TEST metrics are inspected; any intentional dataset change requires a new dataset version. Query binaries and download/review caches remain ignored under `assets/`.

Automated validation and a full contact-sheet review are complete. The project-level requirement for a second team member to sign off the dataset, code, metrics and report remains separate and must be completed before academic submission.
