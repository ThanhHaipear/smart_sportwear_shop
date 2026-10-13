import math

import pytest

from app.embeddings.image_source import validate_cloudinary_original_url
from app.embeddings.models import l2_normalize, pgvector_literal


def test_l2_normalize_enforces_dimension_and_unit_norm():
    vector = l2_normalize([3.0, 4.0], 2)
    assert vector == pytest.approx([0.6, 0.8])
    assert math.sqrt(sum(value * value for value in vector)) == pytest.approx(1.0)


def test_l2_normalize_rejects_bad_vector():
    with pytest.raises(ValueError, match="dimensions"):
        l2_normalize([1.0], 2)
    with pytest.raises(ValueError, match="zero"):
        l2_normalize([0.0, 0.0], 2)


def test_pgvector_literal_is_stable():
    assert pgvector_literal([0.5, -0.25]) == "[0.5,-0.25]"


def test_cloudinary_url_must_be_versioned_original():
    validate_cloudinary_original_url(
        "https://res.cloudinary.com/demo/image/upload/v123/catalog/shirt.jpg", "123"
    )
    with pytest.raises(ValueError, match="asset version"):
        validate_cloudinary_original_url(
            "https://res.cloudinary.com/demo/image/upload/w_200/v123/catalog/shirt.jpg", "999"
        )
    with pytest.raises(ValueError, match="host"):
        validate_cloudinary_original_url("https://example.com/image/upload/v123/a.jpg", "123")
