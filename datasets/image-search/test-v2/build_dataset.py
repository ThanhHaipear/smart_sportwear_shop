from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

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


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def configure(module: Any) -> None:
    module.DATASET_NAME = "image-search-test-v2"
    module.DATASET_VERSION = "2.0.0-rc1"
    module.TARGET_PER_GROUP = 20
    module.EXPECTED_REFERENCE_ASSET_COUNT = 316
    module.CANDIDATE_PREFETCH_TARGET = 40
    module.ACCESSORY_PREFETCH_TARGET = 40
    module.MANUAL_REJECT_PAGE_IDS.update({
        # TEST v2 contact-sheet review: absent, incidental, non-photographic,
        # false-category, or visually dominated by another object.
        20238218, 118114994, 84299197, 32198198, 20238224, 98021280,
        78343216, 56827030,
        60131454, 113055252, 108129443, 88425955, 132357859,
        30879248, 113995638, 122760643, 69011511, 80893616, 62512826,
        163074498, 80893595, 60086645, 109541976, 94068369, 33451819,
        38861017,
        107154309, 37496259, 68678350,
        63706297, 57480223, 129170056, 61557515, 63706306, 39735212,
        5204599, 117059400, 115852746, 25810865, 50984555, 131184758,
        50647214, 34149970, 130051471, 130051813,
        40431648, 37308483, 76396040,
        80893596, 129676060, 6953306, 66531203, 80872666,
        17355647,
        63706316, 66609452, 87739898, 66609696, 87739910, 48500490,
        41374653, 17089521,
        80893607, 181655022, 80872678, 80869710, 80893627,
    })

    def bounded_download(url: str) -> bytes:
        request = Request(url, headers={"User-Agent": module.USER_AGENT})
        with urlopen(request, timeout=8) as response:
            content = response.read(12 * 1024 * 1024 + 1)
        if len(content) > 12 * 1024 * 1024:
            raise ValueError("download exceeds 12 MiB")
        return content

    module.download = bounded_download

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
    base_category_files = module.category_files

    def expanded_category_files(category: str, maximum: int = 100):
        return base_category_files(category, maximum=maximum)

    module.category_files = expanded_category_files
    module.GROUP_SOURCES = {
        "tops": [
            "Polo shirts", "Hoodies", "Long-sleeved T-shirts",
            "Sleeveless shirts", "Clothing of runners", "Running shirts",
        ],
        "bottoms": [
            "Track pants", "Cycling shorts", "Running shorts",
            "Shorts (sports clothing)", "Sweatpants", "Yoga pants",
            "Leggings in sports", "Sports trousers", "Association football shorts",
            "Gym shorts", "Compression shorts", "Running tights",
        ],
        "uniforms": [
            "Handball players", "Field hockey players",
            "American football players", "Cricketers", "Association football players",
        ],
        "shoes": [
            "Running shoes", "Athletic shoes", "Association football boots",
            "Basketball shoes", "Tennis shoes",
        ],
        "accessories": [
            "Shin guards", "Sports socks", "Water bottles",
            "Headbands in sport", "Wristbands",
        ],
        "unknown": [
            "Clocks", "Cameras", "Oranges (fruit)", "Motorcycles",
            "Bridges", "Birds",
        ],
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
        "Running shirts": {
            "ao-chay-bo-nam-aero-navy", "ao-tank-chay-bo-nu-coral-flow",
            "ao-chay-bo-dai-tay-uni-cloud",
        },
        "Track pants": {"quan-jogger-the-thao-uni-motion", "quan-track-chay-bo-uni-forest"},
        "Sports trousers": {"quan-jogger-the-thao-uni-motion", "quan-track-chay-bo-uni-forest"},
        "Cycling shorts": {"quan-short-chay-bo-nam-swift-black", "quan-legging-chay-bo-nu-navy-flex"},
        "Running shorts": {"quan-short-chay-bo-nam-swift-black"},
        "Gym shorts": {
            "quan-short-chay-bo-nam-swift-black", "quan-bong-da-nam-royal-kick",
            "quan-tap-bong-da-nu-black-drive",
        },
        "Compression shorts": {
            "quan-short-chay-bo-nam-swift-black", "quan-legging-chay-bo-nu-navy-flex",
        },
        "Running tights": {"quan-legging-chay-bo-nu-navy-flex"},
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

    prior_manifests = [
        WORKSPACE / "datasets" / "image-search" / "dev-v2" / "manifest.json",
        WORKSPACE / "datasets" / "image-search" / "test-v1" / "manifest.json",
    ]
    excluded_source_groups = {
        sample["sourceGroupCode"]
        for path in prior_manifests
        for sample in load_json(path)["samples"]
    }
    base_normalized_candidate = module.normalized_candidate

    def independent_candidate(page: dict[str, Any], category: str):
        candidate = base_normalized_candidate(page, category)
        if candidate is None:
            return None
        if module.source_series_code(candidate["title"]) in excluded_source_groups:
            return None
        return candidate

    module.normalized_candidate = independent_candidate
    original_reference_images = module.reference_images

    def reference_images(workspace: Path):
        references = original_reference_images(workspace)
        for relative in (
            Path("datasets/image-search/dev-v2/assets"),
            Path("datasets/image-search/test-v1/assets"),
        ):
            root = workspace / relative
            if not root.is_dir():
                raise FileNotFoundError(f"required leakage reference directory is missing: {root}")
            for path in sorted(root.iterdir()):
                if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
                    references.append((path.relative_to(workspace).as_posix(), path, module.dhash(path)))
        return references

    module.reference_images = reference_images


def mark_review_candidate() -> None:
    manifest_path = DATASET_DIR / "manifest.json"
    review_path = DATASET_DIR / "review-report.json"
    manifest = load_json(manifest_path)
    review = load_json(review_path)
    manifest["dataset"]["status"] = "REVIEW_REQUIRED"
    manifest["dataset"]["version"] = "2.0.0-rc1"
    manifest["policy"]["mustNotReusePriorSplits"] = True
    review.update({
        "status": "REVIEW_REQUIRED",
        "reviewerSignoff": {
            "status": "PENDING",
            "requiredRole": "independent project team member",
        },
        "blockedAction": "Do not run the final TEST v2 benchmark before sign-off and freeze.",
    })
    write_json(manifest_path, manifest)
    write_json(review_path, review)


def validate_candidate(module: Any) -> None:
    manifest = load_json(DATASET_DIR / "manifest.json")
    review = load_json(DATASET_DIR / "review-report.json")
    if manifest["dataset"]["status"] != "REVIEW_REQUIRED":
        raise ValueError("TEST v2 candidate must remain REVIEW_REQUIRED before independent sign-off")
    manifest["dataset"]["status"] = "FROZEN"
    review["status"] = "COMPLETE"
    manifest_temporary = DATASET_DIR / "manifest.validation.tmp.json"
    manifest_original = DATASET_DIR / "manifest.json"
    manifest_backup = DATASET_DIR / "manifest.review-required.tmp.json"
    review_temporary = DATASET_DIR / "review-report.validation.tmp.json"
    review_original = DATASET_DIR / "review-report.json"
    review_backup = DATASET_DIR / "review-report.review-required.tmp.json"
    write_json(manifest_temporary, manifest)
    write_json(review_temporary, review)
    manifest_original.replace(manifest_backup)
    review_original.replace(review_backup)
    manifest_temporary.replace(manifest_original)
    review_temporary.replace(review_original)
    try:
        module.validate(DATASET_DIR)
    finally:
        manifest_original.unlink(missing_ok=True)
        review_original.unlink(missing_ok=True)
        manifest_backup.replace(manifest_original)
        review_backup.replace(review_original)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build or validate independent TEST v2 candidate.")
    parser.add_argument("--collect", action="store_true")
    parser.add_argument("--contact-sheets", action="store_true")
    args = parser.parse_args()
    module = load_base_builder()
    configure(module)
    if args.collect:
        bottoms_cache = DATASET_DIR / "assets" / "_cache" / "bottoms-candidates.json"
        if bottoms_cache.is_file():
            cached = load_json(bottoms_cache)
            if sum(len(items) for items in cached.values()) < module.TARGET_PER_GROUP:
                bottoms_cache.unlink()
        module.collect(WORKSPACE, DATASET_DIR)
        mark_review_candidate()
    validate_candidate(module)
    if args.contact_sheets:
        module.contact_sheets(DATASET_DIR)


if __name__ == "__main__":
    main()
