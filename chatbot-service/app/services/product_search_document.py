from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence


def _join(values: Sequence[str] | None) -> str:
    return " ".join(value for value in (values or ()) if value)


def canonical_document(row: Mapping) -> str:
    """Build the deterministic Vietnamese catalog document used by later embeddings."""
    attributes = row["attributes"] or {}
    if isinstance(attributes, str):
        attributes = json.loads(attributes)
    searchable_attributes = " ".join(
        f"{key}: {attributes[key]}" for key in sorted(attributes) if attributes.get(key)
    )
    fields = (
        ("Tên", row["name"]),
        ("Danh mục", f"{row['category_name']} {row['category_code']}"),
        ("Thương hiệu", f"{row['brand_name']} {row['brand_code']}"),
        ("Loại sản phẩm", f"{row['product_type_name']} {row['product_type_code']}"),
        ("Giới tính", row["gender_code"]),
        ("Môn thể thao", f"{_join(row['sport_names'])} {_join(row['sport_codes'])}"),
        ("Nhu cầu", f"{_join(row['use_case_names'])} {_join(row['use_case_codes'])}"),
        ("Chất liệu", f"{_join(row['material_names'])} {_join(row['material_codes'])}"),
        ("Công nghệ", f"{_join(row['technology_names'])} {_join(row['technology_codes'])}"),
        ("Mô tả", f"{row['short_description'] or ''} {row['description'] or ''}"),
        ("Thuộc tính", searchable_attributes),
    )
    return "\n".join(f"{label}: {' '.join(str(value).split())}" for label, value in fields if str(value).strip())


def content_hash(document: str) -> str:
    return hashlib.sha256(document.encode("utf-8")).hexdigest()


PRODUCT_DOCUMENT_SQL = """
select p.id, p.name, p.short_description, p.description, p.gender_code,
       p.attributes, p.status, p.catalog_version,
       c.code as category_code, c.name as category_name,
       b.code as brand_code, b.name as brand_name,
       pt.code as product_type_code, pt.name as product_type_name,
       coalesce((select array_agg(s.code order by s.code)
                   from product_sports ps join sports s on s.id = ps.sport_id
                  where ps.product_id = p.id), '{}'::text[]) as sport_codes,
       coalesce((select array_agg(s.name order by s.code)
                   from product_sports ps join sports s on s.id = ps.sport_id
                  where ps.product_id = p.id), '{}'::text[]) as sport_names,
       coalesce((select array_agg(u.code order by u.code)
                   from product_use_cases pu join use_cases u on u.id = pu.use_case_id
                  where pu.product_id = p.id), '{}'::text[]) as use_case_codes,
       coalesce((select array_agg(u.name order by u.code)
                   from product_use_cases pu join use_cases u on u.id = pu.use_case_id
                  where pu.product_id = p.id), '{}'::text[]) as use_case_names,
       coalesce((select array_agg(m.code order by m.code)
                   from product_materials pm join materials m on m.id = pm.material_id
                  where pm.product_id = p.id), '{}'::text[]) as material_codes,
       coalesce((select array_agg(m.name order by m.code)
                   from product_materials pm join materials m on m.id = pm.material_id
                  where pm.product_id = p.id), '{}'::text[]) as material_names,
       coalesce((select array_agg(t.code order by t.code)
                   from product_technologies ptech join technologies t on t.id = ptech.technology_id
                  where ptech.product_id = p.id), '{}'::text[]) as technology_codes,
       coalesce((select array_agg(t.name order by t.code)
                   from product_technologies ptech join technologies t on t.id = ptech.technology_id
                  where ptech.product_id = p.id), '{}'::text[]) as technology_names
  from products p
  join categories c on c.id = p.category_id
  join brands b on b.id = p.brand_id
  join product_types pt on pt.id = p.product_type_id
 where p.id = $1
"""


PRODUCT_VARIANTS_SQL = """
select pv.id, pv.sku, s.code as size_code, c.code as color_code,
       c.family_code as color_family_code, pv.price,
       pv.stock_quantity - pv.reserved_quantity as available_quantity,
       pv.status
  from product_variants pv
  join sizes s on s.id = pv.size_id
  join colors c on c.id = pv.color_id
 where pv.product_id = $1
 order by pv.id
"""
