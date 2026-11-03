# Image Search TEST v2 Final Report

## Decision

**Acceptance: FAILED — public rollout remains disabled.** TEST v2 was executed exactly once after Dũng's independent sign-off and dataset freeze. The run used only the configuration locked on DEV v2.2. TEST v2 must not be rerun or used to tune ranking, labels, or threshold.

## Frozen inputs

- Dataset: `image-search-test-v2:2.0.0`, status `FROZEN`
- Dataset freeze commit: `fc07163c652a7454c9f2c863c157404ebfd261d8`
- Catalog snapshot: `image-catalog-v2-2026-10-23`
- Model: `google/siglip2-base-patch16-224`
- Revision: `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`
- Candidate limit: `20`
- Similarity threshold: `0.755936`
- Category consensus weight: `0.60`
- Color rerank weight: `0`
- Samples: 100 positive and 20 unknown
- Independent reviewer: Dũng; confirmation relayed by Hải
- Manifest SHA-256: `c0490c76f327c145dc79a1e9e51e2ce471361e2e202f5e6346f86085691e345b`
- Labels SHA-256: `781f8b6110fd5cdb734f9abd2642d3bdc198c507d54411d7dd138f06d23bac32`

## Final metrics

| Metric | Result | Target | Decision |
|---|---:|---:|---|
| Recall@5 | 73.75% | >= 80% | FAIL |
| Category accuracy | 79.00% | >= 90% | FAIL |
| Top-5 accuracy | 81.00% | Reported | — |
| Unknown rejection rate | 75.00% | Reported | — |
| Request failure rate | 0.00% | 0% | PASS |
| Latency P50 | 626.87 ms | Reported | — |
| Latency P95 | 1,381.07 ms | <= 3,000 ms | PASS |
| Leakage | 0 unresolved cases | 0 | PASS |

Color accuracy is `N/A_MISSING_EXPECTED_COLOR_LABELS`; TEST v2 intentionally does not guess ambiguous expected colors.

## Per-group results

| Group | Recall@5 | Category accuracy | Top-5 accuracy | Failure@5 |
|---|---:|---:|---:|---:|
| Accessories | 65.00% | 60.00% | 65.00% | 35.00% |
| Bottoms | 80.00% | 80.00% | 80.00% | 20.00% |
| Shoes | 85.00% | 90.00% | 90.00% | 10.00% |
| Tops | 63.75% | 75.00% | 80.00% | 20.00% |
| Uniforms | 75.00% | 90.00% | 90.00% | 10.00% |

## Offline diagnosis

This diagnosis reads the saved ranked output only. It does not call the service and is not threshold tuning.

- Raw top-1 relevant: 67/100 positive queries.
- Raw hit@5: 86/100; raw hit@10: 89/100.
- Raw top-1 category match: 83/100.
- Fourteen positive queries have no relevant product in the raw top five.
- Five queries have a relevant result in the raw top five that falls below the locked threshold.
- Five of 20 unknown queries cross the locked threshold; unknown rejection is therefore 75%.
- Accessories are the largest weakness: raw hit@5 15/20 and raw category 13/20.
- Tops have raw hit@5 18/20 but accepted Recall@5 63.75%, showing that relevance coverage and threshold behavior both contribute.

The raw hit@5 result exceeds 80%, but raw category accuracy remains below 90%, and acceptance metrics remain below both mandatory gates. Lowering the threshold alone would also increase already-observed unknown false accepts and would be invalid post-TEST tuning.

## Required follow-up

1. Preserve TEST v2, its hashes, configuration, and failed result unchanged.
2. Keep `VISUAL_SEARCH_ENABLED=false` for public rollout.
3. Perform any improvements only on a new DEV v3, prioritizing accessories, tops, category discrimination, and unknown calibration.
4. After DEV v3 is fully locked, collect and independently freeze a new TEST v3 before one final run.
5. Do not rerun TEST v1 or TEST v2 for acceptance or configuration selection.
