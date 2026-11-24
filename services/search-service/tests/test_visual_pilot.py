import json

import pytest

from app.services.visual_pilot import PILOT, PROMPTS, pilot_image_rows, rank_pilot


def test_new_catalog_product_can_rank_without_a_pilot_subtype():
    reference = PILOT["referenceVectors"][0]
    vector = tuple(json.loads(reference["vector"]))
    new_image = {
        **reference,
        "product_id": "10000000-0000-0000-0000-000000000001",
        "image_id": "50000000-0000-0000-0000-000000000001",
        "image_url": "/api/v1/catalog-images/50000000-0000-0000-0000-000000000001.jpg",
    }
    wrong_model = {**new_image, "model": "old-model"}
    rows = pilot_image_rows([new_image, wrong_model])
    scores = dict.fromkeys(PROMPTS, 0.1)
    scores["tops:short"] = 1.0

    assert rows == [new_image]
    match, = rank_pilot(rows, vector, scores, limit=4)
    assert match["product_id"] == new_image["product_id"]
    assert match["image_id"] == new_image["image_id"]
    assert match["similarity"] == pytest.approx(1.0)
    assert pilot_image_rows([]) == []


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

    match, = rank_pilot(pilot_image_rows([base]), vector, scores, limit=4)

    assert match["product_id"] == base["product_id"]
    assert match["image_id"] == base["image_id"]
    assert match["image_url"] == base["image_url"]
    assert match["similarity"] == pytest.approx(1.0)
