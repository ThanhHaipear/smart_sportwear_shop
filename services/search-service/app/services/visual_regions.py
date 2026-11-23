"""Bounded overlapping regions for the opt-in visual search experiment."""

from __future__ import annotations

from PIL import Image


MIN_RESCUE_SCORE = 0.73
ACCESSORY_TYPE_MARGIN = 1.5
ACCESSORY_TYPES = {
    "0f192014-452e-44d8-93bb-aacfa55399ab": "accessory-bottle",
    "73c01f88-cb97-48bb-83c4-ffdc20ad36db": "accessory-wristband",
    "770314ec-4470-4d79-a42c-d779c2ce616c": "accessory-socks",
    "c440bf6a-164d-4471-9df2-ed9e96bf1a16": "accessory-guards",
}


def image_regions(image: Image.Image) -> list[Image.Image]:
    width, height = image.size
    if min(width, height) < 96:
        return []
    region_width, region_height = round(width * 0.56), round(height * 0.56)
    x_edge, y_edge = width - region_width, height - region_height
    offsets = ((0, 0), (x_edge, 0), (0, y_edge), (x_edge, y_edge), (x_edge // 2, y_edge // 2))
    return [image.crop((x, y, x + region_width, y + region_height)) for x, y in offsets]


def rescue_region_ranking(regions: list[list[dict]]) -> list[dict]:
    best = max((ranked for ranked in regions if ranked), key=lambda ranked: ranked[0]["similarity"], default=[])
    return best if best and best[0]["similarity"] >= MIN_RESCUE_SCORE else []


def narrow_accessory_ranking(ranked: list[dict], scores: dict[str, float]) -> list[dict]:
    """For the frozen pilot, narrow results when subtype and catalog match agree."""
    if not ranked or ranked[0]["category_group"] != "accessories":
        return ranked
    subtype = ACCESSORY_TYPES.get(str(ranked[0]["product_id"]))
    if subtype is None:
        return ranked
    other_types = set(ACCESSORY_TYPES.values()) - {subtype}
    if not all(key in scores for key in (*other_types, subtype)):
        return ranked
    next_best = max(scores[key] for key in other_types)
    if scores[subtype] <= 0 or scores[subtype] < ACCESSORY_TYPE_MARGIN * next_best:
        return ranked
    return [row for row in ranked if ACCESSORY_TYPES.get(str(row["product_id"])) == subtype]
