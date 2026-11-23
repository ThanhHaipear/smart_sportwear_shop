# DEV v3.2 jacket catalog coverage check

This study compares the original 81-vector catalog with an 85-vector snapshot
that adds two on-model references for Violet Air and two for Orange Pace.
The four reference images are synthetic variants of the corresponding fictional
pilot designs. They are stored locally under `assets/reference/`. The
[reference inventory](reference-inventory.json) records their source images,
hashes, product IDs and project use scope. The [prompt set](imagegen-prompts.json)
records the exact prompts used with built-in ImageGen.

The fresh check contains independent Wikimedia Commons photographs. The
reference images belong exclusively to the catalog side of the comparison.
The builder checks query hashes, source groups and dHash distance against
prior DEV and TEST assets and the four experimental references. Crops and
attribution are recorded per source.

Both arms use the same query images, SigLIP2 revision, exact cosine, one
candidate per product and the catalog-subtype profile selected in DEV v3.1.
The comparison runs offline after loading the catalog snapshot. The report
separates the jacket subset from control queries and records each rank change.

The augmented snapshot passed the three DEV quality gates. The
[locked profile](locked-profile.json) pins the 85 vectors and ranking
parameters for independent TEST v3. The local export is
`assets/snapshots/catalog-85.json`. When replaying the comparison, use
`--augmented-snapshot-output` to serialise the generated snapshot.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --coverage
cd services/search-service
python -m scripts.evaluate_catalog_coverage ../../datasets/image-search/dev-v3-catalog-coverage/manifest.json --profile-evidence ../../evidence/image-search-v2/cutover-ai-search/hai-14-dev-v3-followup-2026-11-18.json --output coverage-report.json
```

Set the Search `DATABASE_URL` for a read-only snapshot, or pass
`--catalog-vectors` with a local export of the checksum-pinned snapshot.
The SigLIP2 revision must be available in the local cache. To restore missing
Commons query assets, add `--download-missing` to the builder command. An
exact replay requires the four local PNGs matching their recorded checksums.
