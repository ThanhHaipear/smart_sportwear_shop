# Image Search DEV v3

DEV v3 contains licensed, visually reviewed Wikimedia Commons photographs for
development of the image-search pilot. Every source record identifies the
Commons file, author, license, downloaded checksum and canonical asset checksum.
The canonical JPEGs are resized and EXIF-corrected. When redistributing an
asset, credit its author and source, identify the changes and follow the
license recorded for that file.

The dataset emphasizes tops, accessories and out-of-catalog queries. Positive
labels describe visually compatible catalog substitutes by subtype. Unknown
queries have no relevant catalog product. The builder validates the files and
checks source page IDs, source groups, SHA-256 and 64-bit dHash against the
catalog and prior DEV/TEST manifests. Prior TEST cases serve only as leakage
references.

| Group | Calibration | DEV-check |
|---|---:|---:|
| Tops | 13 | 7 |
| Accessories | 5 | 3 |
| Unknown | 9 | 5 |
| Bottoms | 3 | 1 |
| Uniforms | 5 | 2 |
| Shoes | 5 | 2 |

Visual review excluded incidental garments, ambiguous labels and illustrations.
The accessories include five reusable bottles, two wrist sweatbands and one
shin guard. This leaves limited evidence for non-bottle accessories and no
direct evidence for sports socks. The four bottoms and their single DEV-check
case also limit per-group conclusions. The nearest prior or accepted DEV v3
image has dHash distance 8 across 436 prior reference photos.

The calibration partition selects the configuration. The DEV-check partition
measures how it transfers to unseen source groups within DEV. The final
acceptance dataset is TEST v3.

Run from the repository root with Pillow installed and the prior assets
available locally. The builder can download missing DEV v3 assets using the
pinned Commons URLs.

```powershell
python datasets/image-search/dev-v3/build_dataset.py
python datasets/image-search/dev-v3/build_dataset.py --download-missing
```

The benchmark uses the cached SigLIP2 model and a read-only snapshot from
`DATABASE_URL`. It compares full, center and upper-region views and combinations
of views with the same catalog and candidate limit. Use `--catalog-vectors`
with a local snapshot to replay the benchmark offline.

```powershell
cd services/search-service
python -m scripts.evaluate_dev_v3 ../../datasets/image-search/dev-v3/manifest.json --output dev-v3-report.json
```

See [Wikimedia Commons reuse and licensing](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia/licenses)
for license terms.
