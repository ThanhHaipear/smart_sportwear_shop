"""Build and validate the curated DEV v3 dataset from its pinned source inventory."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
from urllib.parse import urlsplit

from PIL import Image, ImageOps


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SPLITS = HERE.parent
PRIOR_SPLITS = ("dev-v1", "dev-v2", "test-v1", "test-v2")
GROUPS = {"tops", "accessories", "unknown", "bottoms", "uniforms", "shoes"}
MIN_DHASH_DISTANCE = 7


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def load_base():
    spec = importlib.util.spec_from_file_location("image_dataset_base", SPLITS / "test-v1/build_dataset.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load the shared Commons dataset builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.dhash = dhash
    return module


def dhash(path: Path) -> int:
    with Image.open(path) as source:
        resized = ImageOps.grayscale(ImageOps.exif_transpose(source)).resize((9, 8), Image.Resampling.LANCZOS)
        pixels = resized.tobytes()
    code = 0
    for y in range(8):
        for x in range(8):
            code = (code << 1) | (pixels[y * 9 + x] > pixels[y * 9 + x + 1])
    return code


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def references(base):
    images = {name: (digest(path), fingerprint) for name, path, fingerprint in base.reference_images(ROOT)}
    groups, pages = set(), set()
    for split in PRIOR_SPLITS:
        for sample in load_json(SPLITS / split / "manifest.json")["samples"]:
            path = SPLITS / split / sample["image"]
            if digest(path) != sample["sha256"]:
                raise ValueError(f"Prior reference checksum mismatch: {path}")
            images[path.relative_to(ROOT).as_posix()] = (sample["sha256"], dhash(path))
            if sample.get("sourceGroupCode"):
                groups.add(sample["sourceGroupCode"])
        if split != "dev-v1":
            pages.update(item["pageId"] for item in load_json(SPLITS / split / "source-inventory.json")["items"])
    return images, groups, pages


def build(download_missing: bool = False) -> dict[str, str]:
    base = load_base()
    inventory = load_json(HERE / "source-inventory.json")
    images, groups, pages = references(base)
    reference_count = len(images)
    hashes = {sha for sha, _ in images.values()}
    products = {group: {row["productSlug"]: row for row in rows}
                for group, rows in base.catalog_products(ROOT).items()}
    samples, labels, checks = [], [], []
    ids = set()
    for item in inventory["items"]:
        query_id, group, source = item["queryId"], item["group"], item["source"]
        if query_id in ids or group not in GROUPS or item["partition"] not in {"calibration", "DEV-check"}:
            raise ValueError(f"Invalid dataset query: {query_id}")
        ids.add(query_id)
        series = base.source_series_code(source["title"])
        if series in groups or source["pageId"] in pages:
            raise ValueError(f"Prior or repeated source series/page: {query_id}")
        groups.add(series)
        pages.add(source["pageId"])
        page_url = urlsplit(source["uri"])
        download_url = urlsplit(source["downloadUri"])
        if (
            not source["author"] or source["author"] == "UNKNOWN"
            or not source["licenseCode"].startswith(base.ALLOWED_LICENSE_PREFIXES)
            or (source["licenseCode"].startswith("CC ") and not source["licenseUrl"])
            or page_url.scheme != "https" or page_url.hostname != "commons.wikimedia.org"
            or download_url.scheme != "https"
            or download_url.hostname not in {"upload.wikimedia.org", "thumb.wikimedia.org"}
        ):
            raise ValueError(f"Missing or invalid source/license: {query_id}")
        path = HERE / "assets" / f"{query_id}.jpg"
        if not path.is_file() and download_missing:
            raw = base.download(source["downloadUri"])
            if base.sha256_bytes(raw) != source["downloadedSha256"]:
                raise ValueError(f"Downloaded source bytes changed: {query_id}")
            content, _, _, _ = base.canonical_image(raw)
            if base.sha256_bytes(content) != item["sha256"]:
                raise ValueError(f"Canonicalized asset checksum mismatch: {query_id}")
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(content)
        sha = digest(path)
        if sha != item["sha256"] or sha in hashes:
            raise ValueError(f"Asset checksum mismatch or exact duplicate: {query_id}")
        hashes.add(sha)
        fingerprint = dhash(path)
        distance, nearest = min(((fingerprint ^ ref).bit_count(), name) for name, (_, ref) in images.items())
        if distance < MIN_DHASH_DISTANCE:
            raise ValueError(f"Near duplicate {query_id} vs {nearest} (dHash distance {distance})")
        images[query_id] = (sha, fingerprint)
        with Image.open(path) as image:
            width, height = image.size
        slugs = item["relevantProductSlugs"]
        if (group == "unknown") != (not slugs) or len(set(slugs)) != len(slugs):
            raise ValueError(f"Invalid relevance coverage: {query_id}")
        relevant = [
            {**products[group][slug], "grade": 2, "judgment": "visually compatible subtype substitute"}
            for slug in slugs
        ]
        samples.append({
            "id": query_id, "image": path.relative_to(HERE).as_posix(), "sha256": sha,
            "bytes": path.stat().st_size, "width": width, "height": height, "group": group,
            "sourceGroupCode": series, "subtype": item["subtype"], "partition": item["partition"],
            "source": source,
        })
        labels.append({
            "queryId": query_id, "judgmentStatus": "COMPLETE",
            "expectedCategory": group, "relevantProducts": relevant,
        })
        checks.append({
            "queryId": query_id, "decision": "ACCEPT", "nearestAsset": nearest,
            "nearestHammingDistance": distance, "dHash64": f"{fingerprint:016x}",
        })
    for partition in ("calibration", "DEV-check"):
        if {sample["group"] for sample in samples if sample["partition"] == partition} != GROUPS:
            raise ValueError(f"{partition} must cover all six groups")
    payloads = {
        "manifest.json": {
            "schemaVersion": 1,
            "dataset": {
                "name": "image-search-dev-v3", "version": inventory["version"], "split": "DEV",
                "status": "FROZEN", "frozenAt": inventory["frozenAt"],
                "catalogSnapshot": {"version": base.CATALOG_SNAPSHOT_VERSION, "sha256": base.CATALOG_SNAPSHOT_SHA256},
            },
            "policy": {"mayTuneOnThisSplit": True, "mayReportAsFinalTest": False, "mayReuseInTestV3": False},
            "samples": samples,
        },
        "labels.json": {
            "schemaVersion": 1, "dataset": "image-search-dev-v3",
            "relevanceScale": {"minimum": 0, "maximum": 3, "relevantAtOrAbove": 2}, "cases": labels,
        },
        "duplicate-report.json": {
            "schemaVersion": 1, "exactDuplicates": 0, "sourceSeriesOverlap": 0,
            "sourcePageOverlap": 0, "referenceAssetsChecked": reference_count,
            "minimumDHashDistance": MIN_DHASH_DISTANCE, "accepted": checks, "unresolved": [],
        },
    }
    artifacts = {name: json.dumps(payload, indent=2, ensure_ascii=False) + "\n" for name, payload in payloads.items()}
    artifacts["assets.sha256"] = "".join(f"{sample['sha256']}  {sample['image']}\n" for sample in samples)
    return artifacts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate the checked-in manifests")
    parser.add_argument("--download-missing", action="store_true")
    args = parser.parse_args()
    artifacts = build(args.download_missing)
    for name, expected in artifacts.items():
        path = HERE / name
        if args.write:
            path.write_text(expected, encoding="utf-8")
        elif path.read_text(encoding="utf-8") != expected:
            raise ValueError(f"Generated artifact differs: {name}")
    manifest = json.loads(artifacts["manifest.json"])
    print(json.dumps({"status": "PASS", "groups": Counter(sample["group"] for sample in manifest["samples"])}))


if __name__ == "__main__":
    main()
