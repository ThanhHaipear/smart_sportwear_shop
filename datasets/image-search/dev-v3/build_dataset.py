"""Build and validate DEV and TEST v3 from their pinned source inventories."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import importlib.util
from io import BytesIO
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


def canonical_sha(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def crop_asset(content: bytes, box: list[int]) -> bytes:
    with Image.open(BytesIO(content)) as image:
        left, top, right, bottom = box
        if not (0 <= left < right <= image.width and 0 <= top < bottom <= image.height):
            raise ValueError("Asset crop lies outside the canonical image")
        output = BytesIO()
        image.crop(tuple(box)).save(output, "JPEG", quality=92, optimize=True)
        return output.getvalue()


def references(base, prior_splits=PRIOR_SPLITS):
    images = {name: (digest(path), fingerprint) for name, path, fingerprint in base.reference_images(ROOT)}
    groups, pages = set(), set()
    for split in prior_splits:
        for sample in load_json(SPLITS / split / "manifest.json")["samples"]:
            path = SPLITS / split / sample["image"]
            if digest(path) != sample["sha256"]:
                raise ValueError(f"Prior reference checksum mismatch: {path}")
            images[path.relative_to(ROOT).as_posix()] = (sample["sha256"], dhash(path))
            if sample.get("sourceGroupCode"):
                groups.add(sample["sourceGroupCode"])
        if split != "dev-v1":
            inventory = load_json(SPLITS / split / "source-inventory.json")
            pages.update(item.get("source", item)["pageId"] for item in inventory["items"])
    return images, groups, pages


def build(download_missing: bool = False, directory: Path = HERE) -> dict[str, str]:
    base = load_base()
    inventory = load_json(directory / "source-inventory.json")
    dataset_name = f"image-search-{directory.name}"
    references_by_split = {
        "dev-v3": PRIOR_SPLITS,
        "dev-v3-supplement": (*PRIOR_SPLITS, "dev-v3"),
        "dev-v3-catalog-coverage": (*PRIOR_SPLITS, "dev-v3", "dev-v3-supplement"),
        "test-v3": (*PRIOR_SPLITS, "dev-v3", "dev-v3-supplement", "dev-v3-catalog-coverage"),
    }
    prior_splits = references_by_split[directory.name]
    check_only = directory.name == "dev-v3-catalog-coverage"
    final_test = directory.name == "test-v3"
    images, groups, pages = references(base, prior_splits)
    if check_only or final_test:
        coverage_dir = SPLITS / "dev-v3-catalog-coverage"
        for row in load_json(coverage_dir / "reference-inventory.json")["images"]:
            reference = coverage_dir / row["image"]
            if digest(reference) != row["sha256"]:
                raise ValueError(f"Experimental reference checksum mismatch: {reference}")
            images[reference.relative_to(ROOT).as_posix()] = (row["sha256"], dhash(reference))
    reference_count = len(images)
    hashes = {sha for sha, _ in images.values()}
    products = {group: {row["productSlug"]: row for row in rows}
                for group, rows in base.catalog_products(ROOT).items()}
    samples, labels, checks = [], [], []
    ids = set()
    for item in inventory["items"]:
        query_id, group, source = item["queryId"], item["group"], item["source"]
        partitions = {"TEST"} if final_test else {"calibration", "DEV-check"}
        if query_id in ids or group not in GROUPS or item["partition"] not in partitions:
            raise ValueError(f"Invalid dataset query: {query_id}")
        if check_only and item["partition"] != "DEV-check":
            raise ValueError(f"Catalog coverage queries belong to the frozen check: {query_id}")
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
        path = directory / "assets" / f"{query_id}.jpg"
        if not path.is_file() and download_missing:
            raw = base.download(source["downloadUri"])
            if base.sha256_bytes(raw) != source["downloadedSha256"]:
                raise ValueError(f"Downloaded source bytes changed: {query_id}")
            content, _, _, _ = base.canonical_image(raw)
            if item.get("cropBox"):
                content = crop_asset(content, item["cropBox"])
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
            "id": query_id, "image": path.relative_to(directory).as_posix(), "sha256": sha,
            "bytes": path.stat().st_size, "width": width, "height": height, "group": group,
            "sourceGroupCode": series, "subtype": item["subtype"], "partition": item["partition"],
            "source": source,
            **({"cropBox": item["cropBox"]} if item.get("cropBox") else {}),
        })
        labels.append({
            "queryId": query_id, "judgmentStatus": "COMPLETE",
            "expectedCategory": group, "relevantProducts": relevant,
        })
        checks.append({
            "queryId": query_id, "decision": "ACCEPT", "nearestAsset": nearest,
            "nearestHammingDistance": distance, "dHash64": f"{fingerprint:016x}",
        })
    for partition in (("TEST",) if final_test else ("calibration", "DEV-check")):
        groups_in_partition = {sample["group"] for sample in samples if sample["partition"] == partition}
        if (partition in {"TEST", "DEV-check"} or directory.name == "dev-v3") and groups_in_partition != GROUPS:
            raise ValueError(f"{partition} must cover all six groups")
    payloads = {
        "manifest.json": {
            "schemaVersion": 1,
            "dataset": {
                "name": dataset_name, "version": inventory["version"], "split": "TEST" if final_test else "DEV",
                "status": "REVIEW_REQUIRED" if final_test else "FROZEN",
                **({"preparedAt": inventory["preparedAt"]} if final_test else {"frozenAt": inventory["frozenAt"]}),
                "catalogSnapshot": {"version": base.CATALOG_SNAPSHOT_VERSION, "sha256": base.CATALOG_SNAPSHOT_SHA256},
            },
            "policy": {"mayTuneOnThisSplit": not final_test, "mayReportAsFinalTest": False,
                       "mayReuseInTestV3": False},
            "samples": samples,
        },
        "labels.json": {
            "schemaVersion": 1, "dataset": dataset_name,
            "relevanceScale": {"minimum": 0, "maximum": 3, "relevantAtOrAbove": 2}, "cases": labels,
        },
        "duplicate-report.json": {
            "schemaVersion": 1, "exactDuplicates": 0, "sourceSeriesOverlap": 0,
            "sourcePageOverlap": 0, "referenceAssetsChecked": reference_count,
            "minimumDHashDistance": MIN_DHASH_DISTANCE, "accepted": checks, "unresolved": [],
        },
    }
    if final_test:
        profile_path = SPLITS / "dev-v3-catalog-coverage/locked-profile.json"
        lock = load_json(profile_path)
        payloads["manifest.json"]["dataset"]["catalogSnapshot"] = {
            "vectors": lock["catalog"]["snapshotVectorCount"],
            "sha256": lock["catalog"]["snapshotFileSha256"],
            "checksum": lock["catalog"]["snapshotChecksum"],
        }
    artifacts = {name: json.dumps(payload, indent=2, ensure_ascii=False) + "\n" for name, payload in payloads.items()}
    artifacts["assets.sha256"] = "".join(f"{sample['sha256']}  {sample['image']}\n" for sample in samples)
    if final_test:
        bindings = {
            "samplesSha256": canonical_sha(samples),
            "labelsSha256": hashlib.sha256(artifacts["labels.json"].encode("utf-8")).hexdigest(),
            "assetListSha256": hashlib.sha256(artifacts["assets.sha256"].encode("utf-8")).hexdigest(),
            "duplicateReportSha256": hashlib.sha256(artifacts["duplicate-report.json"].encode("utf-8")).hexdigest(),
            "lockedProfileSha256": hashlib.sha256(profile_path.read_text(encoding="utf-8").encode("utf-8")).hexdigest(),
        }
        packet = {
            "status": "REVIEW_REQUIRED", "bindings": bindings,
            "reviewerRole": "independent project member",
            "caseCount": len(samples), "groups": dict(Counter(row["group"] for row in samples)),
            "referenceAssetsChecked": reference_count,
        }
        artifacts["review-packet.json"] = json.dumps(packet, indent=2, ensure_ascii=False) + "\n"
    return artifacts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate the checked-in manifests")
    parser.add_argument("--download-missing", action="store_true")
    parser.add_argument("--contact-sheets", action="store_true", help="Write local contact sheets for image review")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--supplement", action="store_true", help="Validate the separate DEV v3 supplement")
    mode.add_argument("--coverage", action="store_true", help="Validate the frozen catalog coverage check")
    mode.add_argument("--test-v3", action="store_true", help="Build or validate the TEST v3 review candidate")
    parser.add_argument("--freeze", action="store_true", help="Seal TEST v3 after documented independent approval")
    args = parser.parse_args()
    if args.freeze and (not args.test_v3 or args.write):
        parser.error("--freeze requires --test-v3 and cannot be combined with --write")
    directory = HERE
    if args.supplement:
        directory = SPLITS / "dev-v3-supplement"
    if args.coverage:
        directory = SPLITS / "dev-v3-catalog-coverage"
    if args.test_v3:
        directory = SPLITS / "test-v3"
        if args.freeze and not (directory / "review-approval.json").is_file():
            parser.error("Independent TEST v3 approval is required before freezing")
    artifacts = build(args.download_missing, directory)
    if args.test_v3:
        manifest_path = directory / "manifest.json"
        already_frozen = manifest_path.is_file() and load_json(manifest_path)["dataset"]["status"] == "FROZEN"
        if already_frozen and args.write:
            parser.error("A frozen TEST manifest cannot be regenerated")
        if args.freeze or already_frozen:
            approval = load_json(directory / "review-approval.json")
            reviewer = approval.get("reviewer", "").strip()
            packet = json.loads(artifacts["review-packet.json"])
            if (
                approval.get("status") != "APPROVED" or approval.get("independent") is not True
                or not reviewer or reviewer.casefold() in {"hải", "hai", "lê thanh hải", "codex"}
                or not approval.get("reviewedAt") or approval.get("bindings") != packet["bindings"]
            ):
                raise ValueError("Independent approval does not match the TEST v3 review packet")
            for name in ("labels.json", "assets.sha256", "duplicate-report.json", "review-packet.json"):
                if (directory / name).read_text(encoding="utf-8") != artifacts[name]:
                    raise ValueError(f"Reviewed artifact differs: {name}")
            frozen = json.loads(artifacts["manifest.json"])
            frozen["dataset"].update(status="FROZEN", frozenAt=approval["reviewedAt"])
            frozen["policy"]["mayReportAsFinalTest"] = True
            if args.freeze and not already_frozen:
                if manifest_path.read_text(encoding="utf-8") != artifacts["manifest.json"]:
                    raise ValueError("Reviewed manifest differs")
                temporary = manifest_path.with_suffix(".tmp")
                temporary.write_text(json.dumps(frozen, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
                temporary.replace(manifest_path)
            artifacts["manifest.json"] = json.dumps(frozen, indent=2, ensure_ascii=False) + "\n"
    for name, expected in artifacts.items():
        path = directory / name
        if args.write:
            path.write_text(expected, encoding="utf-8")
        elif path.read_text(encoding="utf-8") != expected:
            raise ValueError(f"Generated artifact differs: {name}")
    manifest = json.loads(artifacts["manifest.json"])
    if args.contact_sheets:
        load_base().contact_sheets(directory)
    print(json.dumps({"status": "PASS", "groups": Counter(sample["group"] for sample in manifest["samples"])}))


if __name__ == "__main__":
    main()
