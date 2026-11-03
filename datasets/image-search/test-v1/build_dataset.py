from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import html
import io
import json
from pathlib import Path
import re
import time
from typing import Any
from urllib.parse import urlencode
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from PIL import Image, ImageOps


DATASET_NAME = "image-search-test-v1"
DATASET_VERSION = "1.0.0"
CATALOG_SNAPSHOT_VERSION = "image-catalog-v2-2026-10-23"
CATALOG_SNAPSHOT_SHA256 = "421349750aaa44ef15b16448cf64fd8f34cd08a0f11cd578466f2de9aa3e0de0"
TARGET_PER_GROUP = 20
EXPECTED_REFERENCE_ASSET_COUNT = 136
CANDIDATE_PREFETCH_TARGET = 20
ACCESSORY_PREFETCH_TARGET = 40
THUMBNAIL_WIDTH = 1024
USER_AGENT = "SmartSportswearShopResearch/1.0 (academic image-search benchmark)"
COMMONS_API = "https://commons.wikimedia.org/w/api.php"

GROUP_SOURCES = {
    "tops": [
        "Sports T-shirts", "T-shirts", "Sports jerseys", "Sweatshirts", "Tank tops in sports",
        "Running shirts", "Association football jerseys", "People wearing sports clothing",
    ],
    "bottoms": [
        "Shorts (sports clothing)", "Sweatpants", "Yoga pants", "Leggings in sports",
        "Running shorts", "Association football shorts",
    ],
    "uniforms": [
        "People wearing sports clothing", "Association football players",
        "Basketball players", "Baseball players", "Volleyball players",
    ],
    "shoes": [
        "Running shoes", "Athletic shoes", "Association football boots",
        "Basketball shoes", "Tennis shoes",
    ],
    "accessories": [
        "Shin guards", "Sports socks", "Water bottles", "Headbands in sport", "Wristbands",
    ],
    "unknown": [
        "Coffee mugs", "Chairs", "Bananas", "Passenger automobiles",
        "Landscapes", "Domestic cats",
    ],
}

PRODUCT_TYPES = {
    "SPORTS_TOP": "tops",
    "SPORTS_BOTTOM": "bottoms",
    "SPORTS_UNIFORM": "uniforms",
    "SPORTS_SHOES": "shoes",
    "SPORTS_ACCESSORY": "accessories",
}

ALLOWED_LICENSE_PREFIXES = (
    "CC0", "CC BY ", "CC BY-SA ", "Public domain", "PDM",
)
ALLOWED_MIME = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}

MANUAL_REJECT_TITLES = {
    "File:HK TKO Spot mall 將軍澳 Tseung Kwan O 尚德廣場 Sheung Tak Estate Shopping Centre shop 巴雷諾 Baleno Clothing June 2022 Px3 01.jpg",
    "File:HK CWB 銅鑼灣 Causeway Bay 啟超道 Kai Chiu Road Hysan Place shop Lululemon clothing store September 2021 SS2 03.jpg",
    "File:11 ОЭ. в.ч. 10422. 1986-89. Стармос Коркин.jpg",
    'File:"Wards Wonders" of the Players League, 1980 LCCN2016649499.jpg',
    "File:2006 Friederikentunnel Hannover Graffiti zur Fussballweltmeisterschaft.jpg",
    "File:(카셰어링 쏘카 - 양정원 편) 나는, 쏘카를 탑니다. -DYOP 5s.jpg",
    "File:'nude' man 4889143635.jpg",
    "File:The Koloffs and Don Kernodle.jpg",
    "File:Antifascist protest Dresden 2020-02-15 blockade St. Petersburger StraÃŸe 08.jpg",
    "File:Antifascist protest Dresden 2020-02-15 blockade St. Petersburger StraÃŸe 15.jpg",
    "File:2015ChinaJoy SGç¾Žå¥³ ç¬¬ä¸€é›†ã€‚ (7).jpg",
    "File:Armband des 10. Wave-Gotik-Treffen in Leipzig.jpg",
    "File:BlimE.png",
    "File:Broken hope.jpg",
    "File:1901 Sartorial Arts Journal Fashion Plate Men's Norfolk Jacket.png",
}

# Page IDs are the stable guard for titles containing non-ASCII characters;
# this avoids locale-dependent mojibake when a review decision is replayed.
MANUAL_REJECT_PAGE_IDS = {
    71290673,   # fashion-event photo; socks are not the query focus
    87121681,   # protest scene incorrectly categorized as shin guards
    87121831,   # protest scene incorrectly categorized as shin guards
    22348803,   # automobile photo incorrectly categorized as water bottle
    20054724,   # meal photo; bottle is incidental
}

ACCESSORY_PRODUCT_BY_CATEGORY = {
    "Shin guards": "op-bao-ve-ong-dong-red-guard",
    "Sports socks": "tat-bong-da-white-match",
    "Water bottles": "binh-nuoc-the-thao-black-flow-750ml",
    "Headbands in sport": "bang-tay-the-thao-royal-pair",
    "Wristbands": "bang-tay-the-thao-royal-pair",
}

PRODUCT_SLUGS_BY_SOURCE_CATEGORY = {
    "Sports jerseys": {
        "ao-tap-bong-da-nam-blaze-red", "ao-tap-bong-da-nu-royal-motion",
        "ao-giu-nhiet-bong-da-uni-core", "ao-dau-argentina-2026-home-jm8396",
        "ao-dau-bi-2026-home-jm8381", "ao-dau-duc-nu-2026-home-jz4559",
        "ao-dau-brazil-2026-home-if7054-724", "ao-dau-phap-2026-home-ib5300-480",
        "ao-dau-anh-2026-home-ib5290-100",
    },
    "Sports T-shirts": {
        "ao-chay-bo-nam-aero-navy", "ao-tank-chay-bo-nu-coral-flow",
        "ao-chay-bo-dai-tay-uni-cloud", "ao-tap-bong-da-nam-blaze-red",
        "ao-tap-bong-da-nu-royal-motion",
    },
    "T-shirts": {
        "ao-chay-bo-nam-aero-navy", "ao-tank-chay-bo-nu-coral-flow",
        "ao-tap-bong-da-nam-blaze-red", "ao-tap-bong-da-nu-royal-motion",
    },
    "Sweatshirts": {
        "ao-chay-bo-dai-tay-uni-cloud", "ao-giu-nhiet-bong-da-uni-core",
        "ao-khoac-chay-bo-nu-violet-air", "ao-gio-chay-bo-nam-orange-pace",
    },
    "Shorts (sports clothing)": {
        "quan-short-chay-bo-nam-swift-black", "quan-bong-da-nam-royal-kick",
        "quan-tap-bong-da-nu-black-drive",
    },
    "Sweatpants": {"quan-jogger-the-thao-uni-motion", "quan-track-chay-bo-uni-forest"},
    "Yoga pants": {"quan-legging-chay-bo-nu-navy-flex"},
    "Running shoes": {
        "giay-chay-bo-nam-cobalt-ride", "giay-chay-bo-nu-coral-cloud",
        "giay-chay-bo-uni-pure-step",
    },
    "Association football boots": {
        "giay-da-bong-nam-turf-shadow", "giay-da-bong-nu-turf-spark",
        "giay-futsal-uni-red-court",
    },
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def clean_html(value: str | None) -> str:
    text = re.sub(r"<[^>]+>", " ", html.unescape(value or ""))
    return re.sub(r"\s+", " ", text).strip()


def api_json(params: dict[str, object]) -> dict[str, Any]:
    query = urlencode({**params, "format": "json", "formatversion": 2, "maxlag": 5})
    request = Request(f"{COMMONS_API}?{query}", headers={"User-Agent": USER_AGENT})
    for attempt in range(7):
        try:
            with urlopen(request, timeout=45) as response:
                payload = json.load(response)
                time.sleep(0.75)
                return payload
        except HTTPError as exc:
            if exc.code != 429 or attempt == 6:
                raise
            retry_after = int(exc.headers.get("Retry-After", "0") or 0)
            time.sleep(max(retry_after, 5 * (attempt + 1)))
        except URLError:
            if attempt == 6:
                raise
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("unreachable API retry state")


def category_files(category: str, maximum: int = 30) -> list[dict[str, Any]]:
    members: list[dict[str, Any]] = []
    continuation: dict[str, object] = {}
    while len(members) < maximum:
        payload = api_json({
            "action": "query",
            "list": "categorymembers",
            "cmtitle": f"Category:{category}",
            "cmtype": "file",
            "cmlimit": min(100, maximum - len(members)),
            **continuation,
        })
        members.extend(payload.get("query", {}).get("categorymembers", []))
        if "continue" not in payload:
            break
        continuation = payload["continue"]
    files: list[dict[str, Any]] = []
    for offset in range(0, len(members), 50):
        titles = "|".join(str(item["title"]) for item in members[offset : offset + 50])
        payload = api_json({
            "action": "query",
            "titles": titles,
            "prop": "imageinfo",
            "iiprop": "url|mime|size|extmetadata",
            "iiurlwidth": THUMBNAIL_WIDTH,
        })
        files.extend(payload.get("query", {}).get("pages", []))
    return files


def metadata_value(metadata: dict[str, Any], key: str) -> str:
    item = metadata.get(key, {})
    return clean_html(str(item.get("value", ""))) if isinstance(item, dict) else ""


def source_series_code(title: str) -> str:
    stem = re.sub(r"^File:|\.[A-Za-z0-9]{2,5}$", "", title, flags=re.IGNORECASE)
    stem = re.sub(r"\([^)]*\d+[^)]*\)", " ", stem)
    stem = re.sub(r"\b\d{2,}\b", " ", stem)
    stem = re.sub(r"[_\-–—]+", " ", stem)
    words = re.findall(r"[a-z]+", stem.lower())
    return "commons-series:" + "-".join(words[:10])


def normalized_candidate(page: dict[str, Any], category: str) -> dict[str, Any] | None:
    info_list = page.get("imageinfo", [])
    if not info_list:
        return None
    info = info_list[0]
    mime = str(info.get("thumbmime") or info.get("mime") or "").lower()
    if mime not in ALLOWED_MIME:
        return None
    width = int(info.get("width", 0))
    height = int(info.get("height", 0))
    if min(width, height) < 300:
        return None
    metadata = info.get("extmetadata", {})
    license_name = metadata_value(metadata, "LicenseShortName")
    if not license_name.startswith(ALLOWED_LICENSE_PREFIXES):
        return None
    thumb_url = str(info.get("thumburl") or info.get("url") or "")
    if not thumb_url.startswith(("https://upload.wikimedia.org/", "https://thumb.wikimedia.org/")):
        return None
    return {
        "pageId": int(page["pageid"]),
        "title": str(page["title"]),
        "category": category,
        "downloadUrl": thumb_url,
        "originalUrl": str(info.get("url", "")),
        "descriptionUrl": str(info.get("descriptionurl", "")),
        "mime": mime,
        "originalWidth": width,
        "originalHeight": height,
        "author": metadata_value(metadata, "Artist") or metadata_value(metadata, "Credit") or "UNKNOWN",
        "licenseCode": license_name,
        "licenseUrl": metadata_value(metadata, "LicenseUrl"),
        "attribution": metadata_value(metadata, "Attribution") or metadata_value(metadata, "Credit"),
        "dateTimeOriginal": metadata_value(metadata, "DateTimeOriginal"),
    }


def download(url: str) -> bytes:
    request = Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(2):
        try:
            with urlopen(request, timeout=20) as response:
                content = response.read(12 * 1024 * 1024 + 1)
                if len(content) > 12 * 1024 * 1024:
                    raise ValueError("download exceeds 12 MiB")
                time.sleep(0.5)
                return content
        except HTTPError as exc:
            if exc.code != 429 or attempt == 1:
                raise
            retry_after = int(exc.headers.get("Retry-After", "0") or 0)
            time.sleep(max(retry_after, 4 * (attempt + 1)))
        except URLError:
            if attempt == 1:
                raise
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("unreachable download retry state")


def download_cached(candidate: dict[str, Any], cache_dir: Path) -> bytes:
    path = cache_dir / "downloads" / f"{candidate['pageId']}.bin"
    if path.is_file():
        return path.read_bytes()
    content = download(candidate["downloadUrl"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return content


def canonical_image(content: bytes) -> tuple[bytes, str, int, int]:
    with Image.open(io.BytesIO(content)) as source:
        source.load()
        image = ImageOps.exif_transpose(source).convert("RGB")
    if min(image.size) < 224 or image.width * image.height > 25_000_000:
        raise ValueError(f"unsafe image dimensions {image.size}")
    image.thumbnail((THUMBNAIL_WIDTH, THUMBNAIL_WIDTH), Image.Resampling.LANCZOS)
    output = io.BytesIO()
    image.save(output, "JPEG", quality=92, optimize=True)
    return output.getvalue(), ".jpg", image.width, image.height


def dhash(path: Path) -> int:
    with Image.open(path) as source:
        gray = ImageOps.grayscale(ImageOps.exif_transpose(source)).resize((9, 8), Image.Resampling.LANCZOS)
        pixels = list(gray.get_flattened_data())
    value = 0
    for row in range(8):
        for column in range(8):
            value = (value << 1) | int(pixels[row * 9 + column] > pixels[row * 9 + column + 1])
    return value


def hamming(left: int, right: int) -> int:
    return (left ^ right).bit_count()


def reference_images(workspace: Path) -> list[tuple[str, Path, int]]:
    roots = [
        workspace / "catalog" / "pilot-v1" / "assets",
        workspace / "catalog" / "world-cup-2026-store-staging-v1" / "assets",
        workspace / "datasets" / "image-search" / "dev-v1" / "assets",
    ]
    result: list[tuple[str, Path, int]] = []
    for root in roots:
        if not root.is_dir():
            raise FileNotFoundError(f"required leakage reference directory is missing: {root}")
        for path in sorted(root.iterdir()):
            if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
                result.append((path.relative_to(workspace).as_posix(), path, dhash(path)))
    return result


def catalog_products(workspace: Path) -> dict[str, list[dict[str, str]]]:
    grouped = {group: [] for group in PRODUCT_TYPES.values()}
    inputs = [
        (workspace / "catalog" / "pilot-v1", "manifest.json", "import-result.json"),
        (workspace / "catalog" / "world-cup-2026-store-staging-v1", "manifest.json", "import-result.json"),
    ]
    for directory, manifest_name, import_name in inputs:
        manifest = load_json(directory / manifest_name)
        imported = load_json(directory / import_name)
        imported_by_slug = {item["slug"]: item for item in imported["products"]}
        for product in manifest["products"]:
            group = PRODUCT_TYPES[product["productType"]]
            imported_product = imported_by_slug[product["slug"]]
            grouped[group].append({
                "productId": imported_product["id"],
                "productSlug": product["slug"],
                "productName": product["name"],
            })
    return grouped


def collect(workspace: Path, dataset_dir: Path) -> None:
    assets_dir = dataset_dir / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = assets_dir / "_cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    for old in assets_dir.iterdir():
        if old.is_file():
            old.unlink()

    references = reference_images(workspace)
    reference_hashes = {sha256_file(path): name for name, path, _ in references}
    selected_hashes: dict[str, str] = {}
    selected_dhashes: list[tuple[str, int]] = []
    products = catalog_products(workspace)
    samples: list[dict[str, Any]] = []
    labels: list[dict[str, Any]] = []
    duplicate_checks: list[dict[str, Any]] = []
    rejections: Counter[str] = Counter()
    source_inventory: list[dict[str, Any]] = []
    acquired_at = datetime.now(timezone.utc).isoformat()

    query_number = 1
    for group, categories in GROUP_SOURCES.items():
        candidate_cache = cache_dir / f"{group}-candidates.json"
        candidates_by_category: dict[str, list[dict[str, Any]]] = {}
        if candidate_cache.is_file():
            candidates_by_category = load_json(candidate_cache)
            if not set(candidates_by_category).issubset(set(categories)):
                candidates_by_category = {}
            else:
                print(f"using cached Commons metadata group={group}", flush=True)
        if not candidates_by_category:
            for category in categories:
                print(f"querying Commons category={category}", flush=True)
                category_candidates: list[dict[str, Any]] = []
                for page in category_files(category):
                    candidate = normalized_candidate(page, category)
                    if candidate is not None:
                        category_candidates.append(candidate)
                candidates_by_category[category] = sorted(
                    {item["pageId"]: item for item in category_candidates}.values(),
                    key=lambda item: item["title"],
                )
                usable_categories = [items for items in candidates_by_category.values() if len(items) >= 5]
                if len(usable_categories) >= 4:
                    break
            write_json(candidate_cache, candidates_by_category)
        usable_count = sum(len(items) for items in candidates_by_category.values())
        print(f"usable candidates group={group} count={usable_count}", flush=True)

        candidates: list[dict[str, Any]] = []
        for offset in range(40):
            for category in categories:
                items = sorted(
                    [
                        item for item in candidates_by_category.get(category, [])
                        if item["title"] not in MANUAL_REJECT_TITLES
                        and item["pageId"] not in MANUAL_REJECT_PAGE_IDS
                    ],
                    key=lambda item: (
                        not item["downloadUrl"].startswith("https://thumb.wikimedia.org/"),
                        item["title"],
                    ),
                )
                if offset < len(items):
                    candidates.append(items[offset])

        selected_category_counts: Counter[str] = Counter()
        selected_series: Counter[str] = Counter()

        download_results: dict[int, bytes | Exception] = {}
        cached_candidates = [
            candidate for candidate in candidates
            if (cache_dir / "downloads" / f"{candidate['pageId']}.bin").is_file()
        ]
        # The accessory taxonomy is noisier than the apparel categories, so keep
        # a larger replacement pool for manual visual QA without redownloading
        # already-stable groups on every reproducibility run.
        cache_target = (
            ACCESSORY_PREFETCH_TARGET
            if group == "accessories"
            else CANDIDATE_PREFETCH_TARGET
        )
        cached_page_ids = {item["pageId"] for item in cached_candidates}
        fresh_candidates = [
            candidate for candidate in candidates
            if candidate["pageId"] not in cached_page_ids
        ][:max(0, cache_target - len(cached_candidates))]
        prefetch = cached_candidates + fresh_candidates
        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = {executor.submit(download_cached, candidate, cache_dir): candidate for candidate in prefetch}
            for future in as_completed(futures):
                candidate = futures[future]
                try:
                    download_results[candidate["pageId"]] = future.result()
                except Exception as exc:
                    download_results[candidate["pageId"]] = exc

        for candidate in prefetch:
            if sum(sample["group"] == group for sample in samples) >= TARGET_PER_GROUP:
                break
            if selected_category_counts[candidate["category"]] >= 10:
                continue
            series_code = source_series_code(candidate["title"])
            if selected_series[series_code] >= 2:
                rejections["DUPLICATE_SOURCE_SERIES"] += 1
                continue
            try:
                downloaded = download_results[candidate["pageId"]]
                if isinstance(downloaded, Exception):
                    raise downloaded
                raw = downloaded
                content, suffix, width, height = canonical_image(raw)
            except Exception as exc:  # network and malformed remote assets are recorded, not fatal
                rejections[f"DOWNLOAD_OR_DECODE:{type(exc).__name__}"] += 1
                continue
            digest = sha256_bytes(content)
            if digest in reference_hashes or digest in selected_hashes:
                rejections["EXACT_DUPLICATE"] += 1
                continue

            query_id = f"test-{query_number:03d}"
            output_path = assets_dir / f"{query_id}{suffix}"
            output_path.write_bytes(content)
            fingerprint = dhash(output_path)
            nearest_name = ""
            nearest_distance = 65
            for name, _, reference_fingerprint in references:
                distance = hamming(fingerprint, reference_fingerprint)
                if distance < nearest_distance:
                    nearest_name, nearest_distance = name, distance
            for name, selected_fingerprint in selected_dhashes:
                distance = hamming(fingerprint, selected_fingerprint)
                if distance < nearest_distance:
                    nearest_name, nearest_distance = name, distance
            if nearest_distance <= 6:
                output_path.unlink()
                rejections["NEAR_DUPLICATE_DHASH_LE_6"] += 1
                continue

            selected_hashes[digest] = query_id
            selected_dhashes.append((query_id, fingerprint))
            source_group = series_code
            selected_series[series_code] += 1
            selected_category_counts[candidate["category"]] += 1
            source = {
                **candidate,
                "sourceGroupCode": source_group,
                "acquiredAt": acquired_at,
                "downloadedSha256": sha256_bytes(raw),
            }
            source_inventory.append({"queryId": query_id, **source})
            duplicate_checks.append({
                "queryId": query_id,
                "sha256Unique": True,
                "dHash64": f"{fingerprint:016x}",
                "nearestAsset": nearest_name or None,
                "nearestHammingDistance": nearest_distance if nearest_name else None,
                "decision": "ACCEPT",
            })
            samples.append({
                "id": query_id,
                "image": f"assets/{output_path.name}",
                "sha256": digest,
                "bytes": len(content),
                "width": width,
                "height": height,
                "group": group,
                "caseKind": "independent-commons-photo" if group != "unknown" else "independent-negative-commons-photo",
                "sourceGroupCode": source_group,
                "sourceCategory": candidate["category"],
                "source": {
                    "uri": candidate["descriptionUrl"],
                    "originalUri": candidate["originalUrl"],
                    "title": candidate["title"],
                    "author": candidate["author"],
                    "licenseCode": candidate["licenseCode"],
                    "licenseUrl": candidate["licenseUrl"],
                    "attribution": candidate["attribution"],
                    "acquiredAt": acquired_at,
                },
            })
            if group == "unknown":
                relevant = []
            elif group == "accessories":
                target_slug = ACCESSORY_PRODUCT_BY_CATEGORY[candidate["category"]]
                relevant = [
                    {**product, "grade": 2, "judgment": "visually relevant same-subtype substitute"}
                    for product in products[group] if product["productSlug"] == target_slug
                ]
            else:
                category_slugs = PRODUCT_SLUGS_BY_SOURCE_CATEGORY.get(candidate["category"])
                relevant = [
                    {**product, "grade": 2, "judgment": "visually relevant same-category substitute"}
                    for product in products[group]
                    if category_slugs is None or product["productSlug"] in category_slugs
                ]
            labels.append({
                "queryId": query_id,
                "judgmentStatus": "COMPLETE",
                "expectedCategory": group,
                "relevantProducts": relevant,
            })
            query_number += 1

        count = sum(sample["group"] == group for sample in samples)
        if count != TARGET_PER_GROUP:
            raise RuntimeError(
                f"collected only {count}/{TARGET_PER_GROUP} accepted images for {group}; "
                f"rejections={dict(rejections)}"
            )

    distribution = dict(sorted(Counter(sample["group"] for sample in samples).items()))
    checksums = [f"{sample['sha256']}  {sample['image']}" for sample in samples]
    (dataset_dir / "assets.sha256").write_text("\n".join(checksums) + "\n", encoding="utf-8")
    write_json(dataset_dir / "source-inventory.json", {
        "schemaVersion": 1, "provider": "Wikimedia Commons", "items": source_inventory,
    })
    write_json(dataset_dir / "duplicate-report.json", {
        "schemaVersion": 1,
        "method": "SHA-256 exact-match and 64-bit dHash; reject Hamming distance <= 6 against catalog, DEV and accepted TEST assets.",
        "referenceAssetCount": len(references),
        "accepted": duplicate_checks,
        "rejections": dict(sorted(rejections.items())),
        "unresolved": [],
    })
    write_json(dataset_dir / "review-report.json", {
        "schemaVersion": 1,
        "status": "COMPLETE",
        "reviewer": "Codex-assisted category/provenance review; independent team-member sign-off still required by final project checklist",
        "method": "Every query is sourced from a group-specific Commons category. Tops, bottoms, shoes and accessories use source-category/subtype grade-2 substitutes; uniforms use sports-uniform category substitutes; unknown queries have no relevant product.",
        "limitations": [
            "Subcategory/category judgments do not claim an exact-product match.",
            "Expected color is intentionally omitted unless a human reviewer adds an unambiguous normalized color label.",
            "A second team member must visually review all queries before final academic submission.",
        ],
        "caseCount": len(samples),
        "distribution": distribution,
        "unresolved": [],
    })
    frozen_at = datetime.now(timezone.utc).isoformat()
    write_json(dataset_dir / "manifest.json", {
        "schemaVersion": 1,
        "dataset": {
            "name": DATASET_NAME,
            "version": DATASET_VERSION,
            "split": "TEST",
            "status": "FROZEN",
            "frozenAt": frozen_at,
            "catalogSnapshot": {
                "version": CATALOG_SNAPSHOT_VERSION,
                "sha256": CATALOG_SNAPSHOT_SHA256,
            },
        },
        "policy": {
            "purpose": "Final independent generalization evaluation only.",
            "mayTuneOnThisSplit": False,
            "mayReportAsFinalTest": True,
            "provider": "Wikimedia Commons",
            "canonicalization": f"EXIF transpose, RGB conversion, max {THUMBNAIL_WIDTH}px, JPEG quality 92",
        },
        "samples": samples,
    })
    write_json(dataset_dir / "labels.json", {
        "schemaVersion": 1,
        "dataset": DATASET_NAME,
        "relevanceScale": {"minimum": 0, "maximum": 3, "relevantAtOrAbove": 2},
        "review": {
            "status": "COMPLETE",
            "method": "Independent Commons category mapped to frozen catalog category or accessory subtype; unlisted products are grade 0.",
        },
        "cases": labels,
    })
    print(json.dumps({
        "dataset": DATASET_NAME,
        "samples": len(samples),
        "distribution": distribution,
        "referenceAssetsChecked": len(references),
        "status": "FROZEN",
    }, ensure_ascii=False, indent=2))


def validate(dataset_dir: Path) -> None:
    manifest = load_json(dataset_dir / "manifest.json")
    labels = load_json(dataset_dir / "labels.json")
    source_inventory = load_json(dataset_dir / "source-inventory.json")
    duplicate_report = load_json(dataset_dir / "duplicate-report.json")
    review_report = load_json(dataset_dir / "review-report.json")
    samples = manifest.get("samples", [])
    cases = labels.get("cases", [])
    if manifest.get("dataset", {}).get("status") != "FROZEN":
        raise ValueError("dataset is not FROZEN")
    if len(samples) != 120 or len(cases) != 120:
        raise ValueError("TEST v1 must contain exactly 120 samples and labels")
    distribution = Counter(sample["group"] for sample in samples)
    if distribution != Counter({group: TARGET_PER_GROUP for group in GROUP_SOURCES}):
        raise ValueError(f"invalid distribution: {dict(distribution)}")
    case_by_id = {case["queryId"]: case for case in cases}
    sample_ids = {sample["id"] for sample in samples}
    if len(case_by_id) != 120 or set(case_by_id) != sample_ids:
        raise ValueError("manifest/labels IDs do not match")
    inventory_ids = {item["queryId"] for item in source_inventory.get("items", [])}
    if len(inventory_ids) != 120 or inventory_ids != sample_ids:
        raise ValueError("manifest/source-inventory IDs do not match")
    duplicate_items = duplicate_report.get("accepted", [])
    duplicate_ids = {item["queryId"] for item in duplicate_items}
    if len(duplicate_ids) != 120 or duplicate_ids != sample_ids:
        raise ValueError("manifest/duplicate-report IDs do not match")
    if duplicate_report.get("referenceAssetCount") != EXPECTED_REFERENCE_ASSET_COUNT:
        raise ValueError("leakage reference count changed; rebuild and review TEST v1")
    for item in duplicate_items:
        if not item.get("sha256Unique") or item.get("decision") != "ACCEPT":
            raise ValueError(f"failed duplicate decision: {item['queryId']}")
        distance = item.get("nearestHammingDistance")
        if distance is not None and distance <= 6:
            raise ValueError(f"accepted near duplicate: {item['queryId']}")
    seen_hashes: set[str] = set()
    source_group_counts: Counter[str] = Counter()
    expected_checksum_lines = []
    for sample in samples:
        path = (dataset_dir / sample["image"]).resolve()
        if dataset_dir.resolve() not in path.parents or not path.is_file():
            raise ValueError(f"missing or unsafe asset path: {sample['image']}")
        actual = sha256_file(path)
        if actual != sample["sha256"]:
            raise ValueError(f"checksum mismatch: {sample['id']}")
        if actual in seen_hashes:
            raise ValueError(f"exact duplicate: {sample['id']}")
        seen_hashes.add(actual)
        source_group_counts[str(sample["sourceGroupCode"])] += 1
        case = case_by_id[sample["id"]]
        if case.get("judgmentStatus") != "COMPLETE":
            raise ValueError(f"incomplete judgment: {sample['id']}")
        relevant = case.get("relevantProducts", [])
        if sample["group"] == "unknown" and relevant:
            raise ValueError(f"unknown query has relevant products: {sample['id']}")
        if sample["group"] != "unknown" and not relevant:
            raise ValueError(f"positive query has no relevant products: {sample['id']}")
        if sample["group"] == "accessories" and len(relevant) != 1:
            raise ValueError(f"accessory query must map to exactly one subtype: {sample['id']}")
        source = sample.get("source", {})
        if not source.get("uri") or not source.get("author") or not source.get("licenseCode"):
            raise ValueError(f"incomplete source provenance: {sample['id']}")
        if not str(source["licenseCode"]).startswith(ALLOWED_LICENSE_PREFIXES):
            raise ValueError(f"unsupported source license: {sample['id']}")
        expected_checksum_lines.append(f"{actual}  {sample['image']}")
    actual_checksum_lines = (dataset_dir / "assets.sha256").read_text(encoding="utf-8").splitlines()
    if actual_checksum_lines != expected_checksum_lines:
        raise ValueError("assets.sha256 is stale or incorrectly ordered")
    if duplicate_report.get("unresolved") or review_report.get("unresolved"):
        raise ValueError("duplicate/review report has unresolved cases")
    if review_report.get("status") != "COMPLETE" or review_report.get("caseCount") != 120:
        raise ValueError("review report is incomplete")
    if max(source_group_counts.values()) > 2:
        raise ValueError("a sourceGroupCode contributes more than 2 TEST queries")
    print(json.dumps({
        "dataset": manifest["dataset"], "samples": len(samples),
        "distribution": dict(sorted(distribution.items())), "validation": "PASS",
    }, ensure_ascii=False, indent=2))


def contact_sheets(dataset_dir: Path) -> None:
    manifest = load_json(dataset_dir / "manifest.json")
    output_dir = dataset_dir / "assets" / "_review"
    output_dir.mkdir(parents=True, exist_ok=True)
    for group in GROUP_SOURCES:
        samples = [sample for sample in manifest["samples"] if sample["group"] == group]
        sheet = Image.new("RGB", (5 * 240, 4 * 280), "white")
        for index, sample in enumerate(samples):
            with Image.open(dataset_dir / sample["image"]) as source:
                image = ImageOps.contain(ImageOps.exif_transpose(source).convert("RGB"), (220, 230))
            tile = Image.new("RGB", (240, 280), "white")
            tile.paste(image, ((240 - image.width) // 2, 8))
            from PIL import ImageDraw
            ImageDraw.Draw(tile).text((8, 255), sample["id"], fill="black")
            sheet.paste(tile, ((index % 5) * 240, (index // 5) * 280))
        sheet.save(output_dir / f"{group}.jpg", "JPEG", quality=88)
    print(f"contact sheets: {output_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build or validate independent Wikimedia Commons TEST v1.")
    parser.add_argument("--collect", action="store_true", help="query Commons, download, canonicalize and freeze TEST v1")
    parser.add_argument("--contact-sheets", action="store_true", help="write ignored contact sheets for visual review")
    args = parser.parse_args()
    dataset_dir = Path(__file__).resolve().parent
    workspace = dataset_dir.parents[2]
    if args.collect:
        collect(workspace, dataset_dir)
    validate(dataset_dir)
    if args.contact_sheets:
        contact_sheets(dataset_dir)


if __name__ == "__main__":
    main()
