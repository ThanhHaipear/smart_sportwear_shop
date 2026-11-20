import json

import pytest

from app.services.visual_pilot import PILOT, PROMPTS, pilot_image_rows, rank_pilot


def test_visual_pilot_blocks_changed_catalog():
    with pytest.raises(ValueError, match="differs from the evaluated snapshot"):
        pilot_image_rows([])


def test_reference_match_uses_a_real_product_image():
    reference = PILOT["referenceVectors"][0]
    vector = tuple(json.loads(reference["vector"]))
    base = {
        **reference,
        "image_id": "00000000-0000-0000-0000-000000000001",
        "image_url": "https://example.test/catalog/primary.jpg",
        "vector": json.dumps([-value for value in vector]),
    }
    scores = dict.fromkeys(PROMPTS, 0.1)
    scores[PILOT["topSubtypes"][reference["product_id"]]] = 1.0

    match, = rank_pilot([base, reference], vector, scores, limit=4)

    assert match["product_id"] == base["product_id"]
    assert match["image_id"] == base["image_id"]
    assert match["image_url"] == base["image_url"]
    assert match["similarity"] == pytest.approx(1.0)
