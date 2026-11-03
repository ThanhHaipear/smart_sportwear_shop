from __future__ import annotations

import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps


DATASET_DIR = Path(__file__).resolve().parent
WORKSPACE = DATASET_DIR.parents[2]
BASE_BUILDER = WORKSPACE / "datasets" / "image-search" / "test-v1" / "build_dataset.py"


def load_base_builder():
    spec = importlib.util.spec_from_file_location("image_search_test_v1_builder", BASE_BUILDER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load base dataset builder: {BASE_BUILDER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def configure(module: Any) -> None:
    module.DATASET_NAME = "image-search-dev-v2"
    module.DATASET_VERSION = "2.1.0"
    module.TARGET_PER_GROUP = 10

    def compatible_dhash(path: Path) -> int:
        with Image.open(path) as source:
            gray = ImageOps.grayscale(ImageOps.exif_transpose(source)).resize(
                (9, 8), Image.Resampling.LANCZOS
            )
            pixels = list(gray.getdata())
        value = 0
        for row in range(8):
            for column in range(8):
                value = (value << 1) | int(
                    pixels[row * 9 + column] > pixels[row * 9 + column + 1]
                )
        return value

    module.dhash = compatible_dhash
    module.MANUAL_REJECT_PAGE_IDS.update({
        20238218, 32198198,              # tops: mosaic / incidental clothing
        30879248, 113995638,             # uniforms: headshot / boots only
        62512826, 69011511,              # uniforms: parade / mascot
        11397527, 11397538, 61387843,    # shoes: spring shoes / non-shoe cleat
        579665, 22608761,                 # shoes: ballet slipper / nautical cleat
        129170056, 57480223, 115852746,  # accessories: target object not visible
        84299197,                         # tops: illustration, not a real-domain photo
        109541625, 163074498, 109541976, # uniforms: uniform is absent/incidental
        63706297, 63706306, 131184758,   # accessories: subtype is absent/incidental
        50984555,
        33451819, 60086645, 129676060,    # uniforms: false category metadata
        58462361,                         # bottoms: compression arm sleeve, not bottoms
        113055252,                        # bottoms: distant cyclists, garment not query focus
        88425955,                         # bottoms: chamois cream/pad, not a garment
        63706316, 50647214, 130051471,    # accessories: baseball scene, target incidental
        5204599,                          # accessories: dirty liquid, bottle not query focus
        34149970,                         # accessories: advertising vehicle, not a bottle query
        69171020,                         # accessories: headscarf, not a sports headband
        130051813,                        # accessories: wide baseball scene, guard incidental
        87739898,                         # accessories: temple photo miscategorized as bottle
        87739910,                         # accessories: monkey/temple photo miscategorized as bottle
        201645565,                        # accessories: event photo, bottles incidental on table
        41374653,                         # accessories: dinner photo, bottles incidental on table
        122760643,                        # uniforms: historical parade, not sportswear query focus
        201127598,                        # unknown: visually near-duplicate porcelain lamp scene
        6953306,                          # uniforms: portrait in suit, no sportswear visible
        60131454,                         # bottoms: pole-vault scene, shorts not query focus
        3694279,                          # bottoms: full cyclist scene, shorts too small
        108129443,                        # bottoms: shopping-mall scene miscategorized as shorts
        132357857, 132357859,             # bottoms: distant team scenes, shorts not query focus
    })
    test_manifest_path = WORKSPACE / "datasets" / "image-search" / "test-v1" / "manifest.json"
    test_manifest = json.loads(test_manifest_path.read_text(encoding="utf-8"))
    test_source_groups = {sample["sourceGroupCode"] for sample in test_manifest["samples"]}
    base_normalized_candidate = module.normalized_candidate

    def non_test_candidate(page: dict[str, Any], category: str):
        candidate = base_normalized_candidate(page, category)
        if candidate is not None and module.source_series_code(candidate["title"]) in test_source_groups:
            return None
        return candidate

    module.normalized_candidate = non_test_candidate

    # Candidate metadata is cached for reproducibility. Apply the same TEST-v1
    # source-series exclusion to an existing cache, not only to fresh API rows.
    cache_root = DATASET_DIR / "assets" / "_cache"
    for cache_path in cache_root.glob("*-candidates.json"):
        cached = json.loads(cache_path.read_text(encoding="utf-8"))
        for items in cached.values():
            for item in items:
                if module.source_series_code(item["title"]) in test_source_groups:
                    module.MANUAL_REJECT_TITLES.add(item["title"])
    # These categories intentionally differ from the TEST v1 category plan.
    # The duplicate/source-group validator also checks against every TEST v1 asset.
    module.GROUP_SOURCES = {
        "tops": [
            "Polo shirts", "Hoodies", "Long-sleeved T-shirts",
            "Sleeveless shirts", "Clothing of runners",
        ],
        "bottoms": [
            "Track pants", "Cycling shorts", "Running shorts", "Shorts (sports clothing)",
            "Sweatpants", "Yoga pants", "Leggings in sports", "Sports trousers",
            "Association football shorts",
        ],
        "uniforms": [
            "Handball players", "Rugby union players", "Field hockey players",
            "American football players", "Cricketers",
        ],
        "shoes": [
            "Running shoes", "Athletic shoes", "Association football boots",
            "Basketball shoes", "Tennis shoes",
        ],
        "accessories": [
            "Shin guards", "Sports socks", "Water bottles",
            "Headbands in sport", "Wristbands",
        ],
        "unknown": ["Table lamps", "Books", "Apples", "Bicycles", "Dogs"],
    }
    module.PRODUCT_SLUGS_BY_SOURCE_CATEGORY.update({
        "Polo shirts": {
            "ao-chay-bo-nam-aero-navy", "ao-tap-bong-da-nam-blaze-red",
            "ao-tap-bong-da-nu-royal-motion",
        },
        "Hoodies": {
            "ao-chay-bo-dai-tay-uni-cloud", "ao-giu-nhiet-bong-da-uni-core",
            "ao-khoac-chay-bo-nu-violet-air", "ao-gio-chay-bo-nam-orange-pace",
        },
        "Long-sleeved T-shirts": {
            "ao-chay-bo-dai-tay-uni-cloud", "ao-giu-nhiet-bong-da-uni-core",
        },
        "Sleeveless shirts": {"ao-tank-chay-bo-nu-coral-flow"},
        "Clothing of runners": {
            "ao-chay-bo-nam-aero-navy", "ao-tank-chay-bo-nu-coral-flow",
            "ao-chay-bo-dai-tay-uni-cloud", "ao-khoac-chay-bo-nu-violet-air",
            "ao-gio-chay-bo-nam-orange-pace",
        },
        "Track pants": {"quan-jogger-the-thao-uni-motion", "quan-track-chay-bo-uni-forest"},
        "Sports trousers": {"quan-jogger-the-thao-uni-motion", "quan-track-chay-bo-uni-forest"},
        "Sweatpants": {"quan-jogger-the-thao-uni-motion", "quan-track-chay-bo-uni-forest"},
        "Cycling shorts": {"quan-short-chay-bo-nam-swift-black", "quan-legging-chay-bo-nu-navy-flex"},
        "Running shorts": {"quan-short-chay-bo-nam-swift-black"},
        "Association football shorts": {
            "quan-bong-da-nam-royal-kick", "quan-tap-bong-da-nu-black-drive",
        },
        "Yoga pants": {"quan-legging-chay-bo-nu-navy-flex"},
        "Leggings in sports": {"quan-legging-chay-bo-nu-navy-flex"},
        "Athletic shoes": {
            "giay-chay-bo-nam-cobalt-ride", "giay-chay-bo-nu-coral-cloud",
            "giay-chay-bo-uni-pure-step", "giay-da-bong-nam-turf-shadow",
            "giay-da-bong-nu-turf-spark", "giay-futsal-uni-red-court",
        },
        "Basketball shoes": {
            "giay-chay-bo-nam-cobalt-ride", "giay-chay-bo-nu-coral-cloud",
            "giay-chay-bo-uni-pure-step",
        },
        "Tennis shoes": {
            "giay-chay-bo-nam-cobalt-ride", "giay-chay-bo-nu-coral-cloud",
            "giay-chay-bo-uni-pure-step",
        },
    })

    original_reference_images = module.reference_images

    def reference_images(workspace: Path):
        references = original_reference_images(workspace)
        test_assets = workspace / "datasets" / "image-search" / "test-v1" / "assets"
        if not test_assets.is_dir():
            raise FileNotFoundError("TEST v1 assets are required for DEV v2 leakage checks")
        for path in sorted(test_assets.iterdir()):
            if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
                references.append((path.relative_to(workspace).as_posix(), path, module.dhash(path)))
        return references

    module.reference_images = reference_images

    base_download_cached = module.download_cached
    fresh_accessory_budget = 0

    def bounded_download_cached(candidate: dict[str, Any], cache_dir: Path) -> bytes:
        nonlocal fresh_accessory_budget
        cached = cache_dir / "downloads" / f"{candidate['pageId']}.bin"
        # Commons repeatedly stalls on one accessory thumbnail. Thirty-nine
        # already-cached, licensed candidates are more than enough for ten
        # accepted cases, so do not let that single remote object block a
        # reproducible rebuild.
        accessory_categories = set(module.GROUP_SOURCES["accessories"])
        if candidate.get("category") in accessory_categories and not cached.is_file():
            if fresh_accessory_budget <= 0:
                raise TimeoutError("uncached accessory candidate skipped after bounded replacement pool")
            fresh_accessory_budget -= 1
        return base_download_cached(candidate, cache_dir)

    module.download_cached = bounded_download_cached


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def postprocess(module: Any) -> None:
    manifest = load_json(DATASET_DIR / "manifest.json")
    labels = load_json(DATASET_DIR / "labels.json")
    inventory = load_json(DATASET_DIR / "source-inventory.json")
    duplicates = load_json(DATASET_DIR / "duplicate-report.json")
    review = load_json(DATASET_DIR / "review-report.json")

    id_map: dict[str, str] = {}
    for index, sample in enumerate(manifest["samples"], start=1):
        old_id = sample["id"]
        new_id = f"dev2-{index:03d}"
        old_path = DATASET_DIR / sample["image"]
        new_path = old_path.with_name(f"{new_id}{old_path.suffix}")
        old_path.replace(new_path)
        id_map[old_id] = new_id
        sample["id"] = new_id
        sample["image"] = f"assets/{new_path.name}"
        sample["caseKind"] = (
            "independent-dev-domain-photo" if sample["group"] != "unknown"
            else "independent-dev-negative-photo"
        )

    for case in labels["cases"]:
        case["queryId"] = id_map[case["queryId"]]
    for item in inventory["items"]:
        item["queryId"] = id_map[item["queryId"]]
    for item in duplicates["accepted"]:
        item["queryId"] = id_map[item["queryId"]]
    for item in duplicates.get("unresolved", []):
        if item.get("queryId") in id_map:
            item["queryId"] = id_map[item["queryId"]]

    test_manifest = load_json(WORKSPACE / "datasets" / "image-search" / "test-v1" / "manifest.json")
    test_source_groups = {sample["sourceGroupCode"] for sample in test_manifest["samples"]}
    overlapping_groups = sorted(
        {sample["sourceGroupCode"] for sample in manifest["samples"]}.intersection(test_source_groups)
    )
    if overlapping_groups:
        raise ValueError(f"DEV v2 source groups overlap TEST v1: {overlapping_groups}")

    manifest["dataset"].update({
        "name": "image-search-dev-v2",
        "version": "2.1.0",
        "split": "DEV",
        "status": "FROZEN",
    })
    manifest["policy"] = {
        "purpose": "Tune real-domain preprocessing, reranking weights and similarity threshold.",
        "mayTuneOnThisSplit": True,
        "mayReportAsFinalTest": False,
        "mustNotReuseInTestV2": True,
        "provider": "Wikimedia Commons",
        "canonicalization": module.THUMBNAIL_WIDTH,
    }
    labels["dataset"] = "image-search-dev-v2"
    labels["review"] = {
        "status": "COMPLETE",
        "method": (
            "Manual query-focus review plus source-subtype-to-catalog relevance mapping; "
            "exact, dHash and TEST-v1 source-group leakage checks."
        ),
    }
    review.update({
        "status": "COMPLETE",
        "reviewer": "Hải/Codex DEV-v2 engineering review",
        "datasetVersion": "2.1.0",
        "method": "Real-domain query-focus and subtype review; never final acceptance evidence.",
        "caseCount": len(manifest["samples"]),
        "distribution": dict(sorted(Counter(sample["group"] for sample in manifest["samples"]).items())),
        "manualVisualReview": {
            "status": "PASS",
            "scope": "All 60 contact-sheet cases; reject incidental, absent and near-duplicate subjects.",
            "groups": sorted(module.GROUP_SOURCES),
        },
        "relevanceJudgment": {
            "status": "PASS",
            "method": "Source subtype mapped only to compatible frozen-catalog product slugs.",
            "maximumRelevantProductsPerQuery": 6,
        },
        "unresolved": [],
    })
    review["limitations"] = [
        "Grade-2 labels represent visually compatible substitutes, not an exact physical product match.",
        "Expected color remains omitted unless a human reviewer adds an unambiguous normalized color label.",
        "DEV v2.1 is tuning-only and must not be reused in TEST v2 or reported as final acceptance.",
    ]
    duplicates["method"] = (
        "SHA-256 and 64-bit dHash against catalog, DEV v1, TEST v1 and accepted DEV v2; "
        "TEST-v1 sourceGroupCode overlap is forbidden."
    )

    write_json(DATASET_DIR / "manifest.json", manifest)
    write_json(DATASET_DIR / "labels.json", labels)
    write_json(DATASET_DIR / "source-inventory.json", inventory)
    write_json(DATASET_DIR / "duplicate-report.json", duplicates)
    write_json(DATASET_DIR / "review-report.json", review)
    checksum_lines = [f"{sample['sha256']}  {sample['image']}" for sample in manifest["samples"]]
    (DATASET_DIR / "assets.sha256").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")


def validate(module: Any) -> None:
    manifest = load_json(DATASET_DIR / "manifest.json")
    labels = load_json(DATASET_DIR / "labels.json")
    samples = manifest.get("samples", [])
    cases = labels.get("cases", [])
    if manifest.get("dataset", {}).get("split") != "DEV":
        raise ValueError("DEV v2 must declare split=DEV")
    if manifest.get("dataset", {}).get("status") != "FROZEN":
        raise ValueError("DEV v2 must be frozen before tuning")
    if len(samples) != 60 or len(cases) != 60:
        raise ValueError("DEV v2 must contain 60 samples and labels")
    expected = Counter({group: 10 for group in module.GROUP_SOURCES})
    if Counter(sample["group"] for sample in samples) != expected:
        raise ValueError("DEV v2 distribution must be 10 queries per group")
    cases_by_id = {case["queryId"]: case for case in cases}
    if set(cases_by_id) != {sample["id"] for sample in samples}:
        raise ValueError("manifest and labels do not contain the same query IDs")
    checksum_lines = []
    for sample in samples:
        path = DATASET_DIR / sample["image"]
        if not path.is_file() or module.sha256_file(path) != sample["sha256"]:
            raise ValueError(f"missing asset or checksum mismatch: {sample['id']}")
        checksum_lines.append(f"{sample['sha256']}  {sample['image']}")
    if (DATASET_DIR / "assets.sha256").read_text(encoding="utf-8").splitlines() != checksum_lines:
        raise ValueError("assets.sha256 is stale")
    print(json.dumps({"dataset": manifest["dataset"], "distribution": dict(expected), "validation": "PASS"}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description="Build or validate real-domain Image Search DEV v2.")
    parser.add_argument("--collect", action="store_true")
    parser.add_argument("--contact-sheets", action="store_true")
    args = parser.parse_args()
    module = load_base_builder()
    configure(module)
    if args.collect:
        module.collect(WORKSPACE, DATASET_DIR)
        postprocess(module)
    validate(module)
    if args.contact_sheets:
        module.contact_sheets(DATASET_DIR)


if __name__ == "__main__":
    main()
