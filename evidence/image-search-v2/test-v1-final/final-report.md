# Image Search TEST v1 Final Report

## Decision

**Acceptance: FAILED.** TEST v1 was executed once after independent sign-off, using the DEV-locked candidate limit `20` and similarity threshold `0.71233916`. The TEST split was not used for tuning and must not be rerun after changing the threshold or retrieval configuration.

## Frozen inputs

- Dataset: `image-search-test-v1:1.0.0`
- Catalog snapshot: `image-catalog-v2-2026-10-23`
- Model: `google/siglip2-base-patch16-224`
- Revision: `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`
- Candidate limit: `20`
- Similarity threshold: `0.71233916`
- Samples: 100 positive and 20 unknown
- Independent reviewer: Dũng

## Final metrics

| Metric | Result | Target | Decision |
|---|---:|---:|---|
| Recall@5 | 1.67% | >= 80% | FAIL |
| Category accuracy | 4.00% | >= 90% | FAIL |
| Unknown rejection rate | 100% | Reported | PASS |
| Request failure rate | 0% | 0% | PASS |
| Latency P50 | 623.03 ms | Reported | PASS |
| Latency P95 | 670.52 ms | <= 3000 ms | PASS |
| Leakage | 0 unresolved cases | 0 | PASS |

Color accuracy is N/A because TEST v1 intentionally has no guessed expected-color labels.

## Post-run diagnosis

The following diagnostics use the already-saved ranked output and are not acceptance metrics or threshold tuning:

- Raw top-1 relevant product before thresholding: 59/100 positive queries.
- Raw top-5 hit before thresholding: 79/100.
- Raw top-10 hit before thresholding: 84/100.
- Raw top-1 category match before thresholding: 67/100.
- Only 4/100 positive top results crossed the locked threshold.
- Positive top-score median was `0.59598`; positive P95 was `0.70537`, still below the locked threshold.
- Unknown top-score maximum was `0.60379`; all 20 unknown queries were rejected.

The dominant failure is a DEV-to-TEST domain shift: the threshold selected on catalog-derived synthetic DEV is too high for independently sourced photos. The unthresholded rankings retain useful signal, but raw top-5 hit and raw category accuracy still miss the final quality targets, so lowering the threshold alone would not satisfy acceptance.

## Required follow-up

Preserve this failed run unchanged. Perform HAI-08 failure-case analysis by group and compare the legacy baseline. Any new preprocessing, labeling, model or threshold decision must be developed on a new independent DEV version and evaluated on a newly versioned TEST split under a protocol locked before collection. Public rollout remains disabled.
