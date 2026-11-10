from app.services.visual_ranking import category_group, rerank_visual_candidates
from app.config import Settings


def _row(product: str, score: float, product_type: str) -> dict:
    return {
        "product_id": product, "image_id": f"image-{product}", "image_url": f"/{product}.jpg",
        "similarity": score, "product_type": product_type,
    }


def test_product_types_map_to_locked_evaluation_groups():
    assert category_group("SPORTS_TOP") == "tops"
    assert category_group("SPORTS_ACCESSORY") == "accessories"
    assert category_group("UNKNOWN") is None


def test_locked_visual_acceptance_configuration():
    settings = Settings()
    assert settings.visual_category_consensus_weight == .60
    assert settings.visual_min_similarity == .755936


def test_zero_shot_category_support_can_fix_close_cross_category_result():
    rows = [_row("bottom", .81, "SPORTS_BOTTOM"), _row("top", .80, "SPORTS_TOP")]
    ranked = rerank_visual_candidates(
        rows, category_weight=.60,
        query_category_scores={"tops": .8, "bottoms": .2, "unknown": .0},
    )
    assert ranked[0]["product_id"] == "top"
    assert ranked[0]["category_group"] == "tops"


def test_consensus_uses_top_three_average_not_catalog_group_size():
    rows = [
        _row("shoe-a", .90, "SPORTS_SHOES"), _row("shoe-b", .80, "SPORTS_SHOES"),
        _row("top-a", .89, "SPORTS_TOP"), _row("top-b", .88, "SPORTS_TOP"),
        _row("top-c", .87, "SPORTS_TOP"), _row("top-d", .10, "SPORTS_TOP"),
    ]
    ranked = rerank_visual_candidates(rows, category_weight=.10)
    assert ranked[0]["product_id"] == "shoe-a"
