from uuid import uuid4

import pytest

from app.persistence.repository import (
    blend_visual_and_color_similarity,
    histogram_intersection,
    rerank_visual_candidates,
)


def test_visual_shape_outweighs_an_unrelated_exact_color_match() -> None:
    correct_shape = blend_visual_and_color_similarity(visual_score=0.90, color_score=0.55)
    wrong_shape_same_color = blend_visual_and_color_similarity(visual_score=0.65, color_score=1.0)

    assert correct_shape > wrong_shape_same_color


def test_color_remains_a_tiebreaker_for_visually_similar_products() -> None:
    matching_color = blend_visual_and_color_similarity(visual_score=0.80, color_score=0.90)
    wrong_color = blend_visual_and_color_similarity(visual_score=0.80, color_score=0.30)

    assert matching_color > wrong_color
    assert matching_color == pytest.approx(0.815)


def test_histogram_intersection_is_bounded_and_requires_matching_dimensions() -> None:
    assert histogram_intersection((0.7, 0.3), (0.5, 0.5)) == pytest.approx(0.8)
    assert histogram_intersection((1.0,), (1.0, 0.0)) == 0.0


def test_color_rerank_changes_ties_but_not_strong_shape_mismatches() -> None:
    first_product, second_product = uuid4(), uuid4()
    rows = [
        (first_product, uuid4(), "https://example/red.jpg", 0.80, [1.0, 0.0], "SPORTS_TOP"),
        (second_product, uuid4(), "https://example/blue.jpg", 0.80, [0.0, 1.0], "SPORTS_TOP"),
    ]

    ranked = rerank_visual_candidates(rows, (0.0, 1.0), color_weight=0.15)

    assert ranked[0].product_id == second_product
    assert ranked[0].similarity > ranked[1].similarity


def test_category_consensus_uses_average_not_catalog_group_size() -> None:
    top_product, consensus_product = uuid4(), uuid4()
    rows = [
        (top_product, uuid4(), "https://example/top.jpg", 0.81, None, "SPORTS_TOP"),
        (consensus_product, uuid4(), "https://example/shoe-1.jpg", 0.80, None, "SPORTS_SHOES"),
        (uuid4(), uuid4(), "https://example/shoe-2.jpg", 0.79, None, "SPORTS_SHOES"),
        (uuid4(), uuid4(), "https://example/shoe-3.jpg", 0.78, None, "SPORTS_SHOES"),
    ]

    ranked = rerank_visual_candidates(rows, (), category_weight=0.10)

    # The top visual match remains first; consensus is deliberately a small
    # stabilizer, not a hard category filter.
    assert ranked[0].product_id == top_product
    assert {item.category_group for item in ranked} == {"tops", "shoes"}


def test_zero_shot_category_scores_override_retrieval_consensus() -> None:
    rows = [
        (uuid4(), uuid4(), "top.jpg", 0.70, None, "SPORTS_TOP"),
        (uuid4(), uuid4(), "shoe.jpg", 0.68, None, "SPORTS_SHOES"),
    ]

    ranked = rerank_visual_candidates(
        rows,
        (),
        category_weight=0.25,
        query_category_scores={"tops": 0.05, "shoes": 0.95},
    )

    assert ranked[0].category_group == "shoes"
