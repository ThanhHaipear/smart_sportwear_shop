import hashlib
import json

from app.catalog.client import CatalogIndexDocument


PRODUCT_TYPE_BY_CATEGORY_SLUG = {
    "ao-the-thao": "SPORTS_TOP",
    "quan-the-thao": "SPORTS_BOTTOM",
    "dong-phuc-the-thao": "SPORTS_UNIFORM",
    "giay-the-thao": "SPORTS_SHOES",
    "giay-chay-bo": "SPORTS_SHOES",
    "giay-bong-da": "SPORTS_SHOES",
    "phu-kien-the-thao": "SPORTS_ACCESSORY",
}


def enrich_search_classification(document: CatalogIndexDocument) -> CatalogIndexDocument:
    """Fill the search-owned classification when legacy Catalog data omits it."""
    attributes = document.attributes
    product_type = (
        document.product_type
        or attributes.get("productType")
        or attributes.get("product_type")
        or PRODUCT_TYPE_BY_CATEGORY_SLUG.get(document.category_slug or "")
    )
    gender = document.gender or attributes.get("gender")
    sport_codes = document.sport_codes
    if not sport_codes:
        raw_sports = attributes.get("sportCodes") or attributes.get("sport_codes")
        if isinstance(raw_sports, list):
            sport_codes = [str(value) for value in raw_sports if value]
        else:
            sport = attributes.get("sportType") or attributes.get("sport_type")
            if sport:
                sport_codes = [str(sport)]
    return document.model_copy(update={
        "product_type": str(product_type) if product_type else None,
        "gender": str(gender) if gender else None,
        "sport_codes": sport_codes,
    })


def canonical_text(document: CatalogIndexDocument) -> str:
    attributes = " ".join(
        f"{key}: {document.attributes[key]}" for key in sorted(document.attributes)
        if document.attributes[key] not in (None, "", [], {})
    )
    fields = (
        ("Tên", document.name), ("Danh mục", document.category),
        ("Thương hiệu", document.brand), ("Loại", document.product_type),
        ("Giới tính", document.gender), ("Môn thể thao", " ".join(document.sport_codes)),
        ("Mô tả", document.description), ("Thuộc tính", attributes),
    )
    return "\n".join(f"{label}: {' '.join(str(value).split())}" for label, value in fields if value)


def content_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def stable_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
