# Image Search DEV v3 supplement

Version 3.1.0 contains 29 newly sourced Wikimedia Commons photographs. The
original DEV v3 manifest and labels remain available as version 3.0.0.
The supplement focuses on sports socks, wrist sweatbands, shin guards, warm-up
tops and equipment outside the catalog.

| Group | Calibration | Fresh DEV check |
|---|---:|---:|
| Tops | 3 | 4 |
| Accessories | 3 | 5 |
| Bottoms | 0 | 1 |
| Uniforms | 0 | 2 |
| Shoes | 2 | 2 |
| Unknown | 2 | 5 |

Calibration combines the ten supplemental calibration images with all 60
original DEV v3 images. The original DEV-check outcomes informed the new
experiment, so the 19 supplemental check images provide a fresh check. Each
positive label describes a compatible substitute in the frozen catalog.
Unknown images include backpacks, an umbrella and computer accessories.

The validator compares the new images against the catalog and all prior DEV
and TEST datasets, including the original DEV v3. Each source record pins its
file page, attribution, license and original checksum. Where relevant, it also
records the pixel crop applied to the canonical image so the visible garment or
accessory is the query focus.

From the repository root, with the prior assets available locally:

```powershell
python datasets/image-search/dev-v3/build_dataset.py --supplement
python datasets/image-search/dev-v3/build_dataset.py --supplement --download-missing
```

From `services/search-service`, with the pinned SigLIP2 model cached and a
Search DSN in `DATABASE_URL`:

```powershell
python -m scripts.evaluate_dev_v3_followup ../../datasets/image-search/dev-v3/manifest.json ../../datasets/image-search/dev-v3-supplement/manifest.json --output followup-report.json
```

The default experiment evaluates category consensus from the full image and
two regions. The `--catalog-semantics` diagnostic uses separate SigLIP2
descriptions for catalog top subtypes and out-of-catalog objects. It uses the
same supplemental check after the first experiment, so those numbers are
reported as development diagnostics. Add `--combine-pair-evidence` alongside
`--catalog-semantics` to replay the combined experiment.

Follow the terms of each source file when redistributing a canonical image or
crop. [Wikimedia Commons reuse and licensing](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia/licenses)
