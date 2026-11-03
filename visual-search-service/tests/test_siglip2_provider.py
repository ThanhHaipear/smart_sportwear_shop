import asyncio
import math
from unittest.mock import patch

import pytest

from app.config import Settings
from app.providers.siglip2 import Siglip2EmbeddingProvider, category_probabilities, l2_normalize


def test_siglip2_vector_is_l2_normalized() -> None:
    vector = l2_normalize([3.0, 4.0], 2)

    assert vector == pytest.approx((0.6, 0.8))
    assert math.sqrt(sum(value * value for value in vector)) == pytest.approx(1.0)


def test_siglip2_rejects_wrong_dimension() -> None:
    with pytest.raises(ValueError, match="Expected 3 dimensions"):
        l2_normalize([1.0, 2.0], 3)


@pytest.mark.parametrize(
    "values",
    [[0.0, 0.0], [float("nan"), 1.0], [float("inf"), 1.0]],
)
def test_siglip2_rejects_invalid_vector(values: list[float]) -> None:
    with pytest.raises(ValueError):
        l2_normalize(values, 2)


def test_category_probabilities_favor_closest_prototype() -> None:
    scores = category_probabilities(
        (1.0, 0.0),
        {"tops": (1.0, 0.0), "shoes": (0.0, 1.0)},
    )

    assert scores["tops"] > 0.99
    assert sum(scores.values()) == pytest.approx(1.0)


def test_siglip2_fails_fast_when_embedding_dependencies_are_missing() -> None:
    provider = Siglip2EmbeddingProvider(Settings(_env_file=None))

    with patch.dict("sys.modules", {"torch": None}):
        with pytest.raises(RuntimeError, match="requirements-embeddings.txt"):
            asyncio.run(provider.prepare())
