import hashlib
import json

from app.catalog.client import CatalogIndexDocument


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
