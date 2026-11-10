from __future__ import annotations


PRODUCT_TYPE_GROUPS = {
    "SPORTS_TOP": "tops",
    "SPORTS_BOTTOM": "bottoms",
    "SPORTS_UNIFORM": "uniforms",
    "SPORTS_SHOES": "shoes",
    "SPORTS_ACCESSORY": "accessories",
}


def category_group(product_type: str | None) -> str | None:
    return PRODUCT_TYPE_GROUPS.get(product_type or "")


def rerank_visual_candidates(
    rows: list[dict], *, category_weight: float,
    query_category_scores: dict[str, float] | None = None,
) -> list[dict]:
    """Apply bounded category support while preserving visual similarity as the base signal."""
    candidates = [dict(row, category_group=category_group(row.get("product_type"))) for row in rows]
    group_scores: dict[str, list[float]] = {}
    for row in candidates:
        group = row["category_group"]
        if group:
            group_scores.setdefault(group, []).append(float(row["similarity"]))
    consensus = {
        group: sum(sorted(scores, reverse=True)[:3]) / min(3, len(scores))
        for group, scores in group_scores.items()
    }
    support_scores = query_category_scores or consensus
    # Unknown is a rejection signal, never a catalog category to boost.
    strongest = max((score for group, score in support_scores.items() if group != "unknown"), default=0.0)
    for row in candidates:
        support = support_scores.get(row["category_group"], 0.0) / strongest if strongest > 0 else 0.0
        row["similarity"] = (
            (1.0 - category_weight) * float(row["similarity"])
            + category_weight * support
        )
    return sorted(
        candidates,
        key=lambda row: (-float(row["similarity"]), str(row["product_id"]), str(row["image_id"])),
    )
