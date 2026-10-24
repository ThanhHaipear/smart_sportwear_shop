from __future__ import annotations

import hashlib
import io
import json
import random
import shutil
from collections import Counter
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter


DATASET_VERSION = "image-search-dev-v1"
CATALOG_SNAPSHOT_VERSION = "image-catalog-v2-2026-10-23"
CATALOG_SNAPSHOT_SHA256 = "421349750aaa44ef15b16448cf64fd8f34cd08a0f11cd578466f2de9aa3e0de0"
SEED = 20261024
FROZEN_AT = "2026-10-24T09:50:00+07:00"
TARGET_PER_GROUP = 10

PRODUCT_GROUPS = {
    "SPORTS_TOP": "tops",
    "SPORTS_BOTTOM": "bottoms",
    "SPORTS_UNIFORM": "uniforms",
    "SPORTS_SHOES": "shoes",
    "SPORTS_ACCESSORY": "accessories",
}

UNKNOWN_SOURCES = [
    "frontend/storefront/public/images/slideshow_2.webp",
    "frontend/storefront/public/images/slideshow_5.webp",
    "frontend/storefront/public/images/slideshow_9.webp",
    "frontend/storefront/public/images/slideshow_10.webp",
    "frontend/storefront/public/images/banner-hero-1.png",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def simulate_camera(content: bytes, seed: int) -> bytes:
    rng = random.Random(seed)
    with Image.open(io.BytesIO(content)) as source:
        image = source.convert("RGB")

    image.thumbnail((780, 780), Image.Resampling.LANCZOS)
    crop_fraction = rng.uniform(0.82, 0.98)
    crop_width = max(1, int(image.width * crop_fraction))
    crop_height = max(1, int(image.height * crop_fraction))
    left = rng.randint(0, max(0, image.width - crop_width))
    top = rng.randint(0, max(0, image.height - crop_height))
    image = image.crop((left, top, left + crop_width, top + crop_height))
    image = image.rotate(
        rng.uniform(-14, 14),
        resample=Image.Resampling.BICUBIC,
        expand=True,
        fillcolor=(238, 235, 228),
    )
    image = ImageEnhance.Brightness(image).enhance(rng.uniform(0.76, 1.20))
    image = ImageEnhance.Contrast(image).enhance(rng.uniform(0.80, 1.24))
    image = ImageEnhance.Color(image).enhance(rng.uniform(0.80, 1.16))

    canvas = Image.new("RGB", (900, 900), tuple(rng.randint(170, 245) for _ in range(3)))
    scale = rng.uniform(0.70, 0.92)
    image.thumbnail((int(900 * scale), int(900 * scale)), Image.Resampling.LANCZOS)
    x = (900 - image.width) // 2 + rng.randint(-40, 40)
    y = (900 - image.height) // 2 + rng.randint(-40, 40)
    canvas.paste(image, (x, y))

    noise = Image.effect_noise(canvas.size, rng.uniform(3, 11)).convert("L")
    noise_rgb = Image.merge("RGB", (noise, noise, noise))
    canvas = Image.blend(canvas, noise_rgb, rng.uniform(0.015, 0.045))
    canvas = canvas.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.12, 0.70)))

    output = io.BytesIO()
    canvas.save(output, "JPEG", quality=rng.randint(70, 90), optimize=True)
    return output.getvalue()


def build_catalog_sources(workspace: Path) -> dict[str, list[dict]]:
    catalog_dir = workspace / "catalog" / "pilot-v1"
    manifest = load_json(catalog_dir / "manifest.json")
    imported = load_json(catalog_dir / "import-result.json")
    imported_by_slug = {product["slug"]: product for product in imported["products"]}
    grouped = {group: [] for group in PRODUCT_GROUPS.values()}

    for product in manifest["products"]:
        group = PRODUCT_GROUPS[product["productType"]]
        imported_product = imported_by_slug[product["slug"]]
        imported_images = {image["checksum"]: image for image in imported_product["images"]}
        for image in product["images"]:
            imported_image = imported_images[image["sha256"]]
            source_path = catalog_dir / image["path"]
            if not source_path.is_file():
                raise FileNotFoundError(source_path)
            if sha256(source_path) != image["sha256"]:
                raise ValueError(f"Catalog checksum mismatch: {source_path}")
            grouped[group].append(
                {
                    "path": source_path,
                    "repoPath": source_path.relative_to(workspace).as_posix(),
                    "productId": imported_product["id"],
                    "productSlug": product["slug"],
                    "productName": product["name"],
                    "imageId": imported_image["id"],
                    "sourceGroupCode": imported_image["sourceGroupCode"],
                    "licenseCode": imported_image["licenseCode"],
                    "sourceSha256": image["sha256"],
                }
            )
    return grouped


def main() -> None:
    dataset_dir = Path(__file__).resolve().parent
    workspace = dataset_dir.parents[2]
    assets_dir = dataset_dir / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)

    grouped = build_catalog_sources(workspace)
    samples: list[dict] = []
    labels: list[dict] = []
    generated_files: list[Path] = []
    index = 1

    for group in ("tops", "bottoms", "uniforms", "shoes", "accessories"):
        sources = grouped[group]
        if not sources:
            raise ValueError(f"No catalog source for group {group}")
        for offset in range(TARGET_PER_GROUP):
            source = sources[offset % len(sources)]
            query_id = f"dev-{index:03d}"
            output_path = assets_dir / f"{query_id}.jpg"
            output_path.write_bytes(simulate_camera(source["path"].read_bytes(), SEED + index))
            generated_files.append(output_path)
            samples.append(
                {
                    "id": query_id,
                    "image": f"assets/{output_path.name}",
                    "sha256": sha256(output_path),
                    "bytes": output_path.stat().st_size,
                    "group": group,
                    "caseKind": "catalog-derived-camera-simulation",
                    "sourceGroupCode": source["sourceGroupCode"],
                    "source": {
                        "uri": f"repo://{source['repoPath']}",
                        "imageId": source["imageId"],
                        "productSlug": source["productSlug"],
                        "sourceSha256": source["sourceSha256"],
                        "licenseCode": source["licenseCode"],
                    },
                    "transform": {
                        "seed": SEED + index,
                        "operations": [
                            "crop",
                            "rotation",
                            "background",
                            "brightness",
                            "contrast",
                            "color",
                            "noise",
                            "blur",
                            "jpeg",
                        ],
                    },
                }
            )
            labels.append(
                {
                    "queryId": query_id,
                    "judgmentStatus": "COMPLETE",
                    "relevantProducts": [
                        {
                            "productId": source["productId"],
                            "productSlug": source["productSlug"],
                            "grade": 3,
                        }
                    ],
                }
            )
            index += 1

    for relative_source in UNKNOWN_SOURCES:
        source_path = workspace / relative_source
        if not source_path.is_file():
            raise FileNotFoundError(source_path)
        query_id = f"dev-{index:03d}"
        output_path = assets_dir / f"{query_id}{source_path.suffix.lower()}"
        shutil.copyfile(source_path, output_path)
        generated_files.append(output_path)
        samples.append(
            {
                "id": query_id,
                "image": f"assets/{output_path.name}",
                "sha256": sha256(output_path),
                "bytes": output_path.stat().st_size,
                "group": "unknown",
                "caseKind": "negative-repository-asset",
                "sourceGroupCode": f"legacy-ui:{source_path.stem}",
                "source": {
                    "uri": f"repo://{relative_source}",
                    "sourceSha256": sha256(source_path),
                    "licenseCode": "PROJECT_LEGACY_ASSET_REVIEW_REQUIRED",
                },
                "transform": {"seed": None, "operations": []},
            }
        )
        labels.append(
            {
                "queryId": query_id,
                "judgmentStatus": "COMPLETE",
                "relevantProducts": [],
            }
        )
        index += 1

    asset_names = {path.name for path in generated_files}
    unexpected = sorted(path.name for path in assets_dir.iterdir() if path.is_file() and path.name not in asset_names)
    if unexpected:
        raise ValueError(f"Unexpected files in DEV assets directory: {unexpected}")

    checksums = [f"{sha256(path)}  assets/{path.name}" for path in sorted(generated_files)]
    (dataset_dir / "assets.sha256").write_text("\n".join(checksums) + "\n", encoding="utf-8")

    manifest = {
        "schemaVersion": 1,
        "dataset": {
            "name": DATASET_VERSION,
            "version": "1.0.0",
            "split": "DEV",
            "status": "FROZEN",
            "generatedAt": FROZEN_AT,
            "seed": SEED,
            "catalogSnapshot": {
                "version": CATALOG_SNAPSHOT_VERSION,
                "sha256": CATALOG_SNAPSHOT_SHA256,
            },
        },
        "policy": {
            "purpose": "Preprocessing, threshold, candidate-limit and grouping selection only.",
            "mayTuneOnThisSplit": True,
            "mayReportAsFinalTest": False,
            "limitations": [
                "Positive cases are deterministic catalog-derived simulations.",
                "This split measures transformation robustness, not real-world generalization.",
                "Negative legacy UI assets require rights review before redistribution.",
            ],
        },
        "samples": samples,
    }
    label_document = {
        "schemaVersion": 1,
        "dataset": DATASET_VERSION,
        "relevanceScale": {"minimum": 0, "maximum": 3, "relevantAtOrAbove": 2},
        "review": {
            "status": "COMPLETE",
            "method": "Product IDs inherited from frozen catalog provenance; negative cases have no relevant catalog product.",
        },
        "cases": labels,
    }
    (dataset_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (dataset_dir / "labels.json").write_text(
        json.dumps(label_document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    duplicate_hashes = sum(count - 1 for count in Counter(sample["sha256"] for sample in samples).values() if count > 1)
    if duplicate_hashes:
        raise ValueError(f"Generated DEV set contains {duplicate_hashes} exact duplicate assets")
    print(json.dumps({
        "dataset": DATASET_VERSION,
        "samples": len(samples),
        "groups": dict(sorted(Counter(sample["group"] for sample in samples).items())),
        "exactDuplicates": duplicate_hashes,
        "status": "FROZEN",
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
