# Image Search TEST v1 Protocol

**Status:** `COLLECTION_PENDING`

**Planned version:** `image-search-test-v1:1.0.0`

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
