import asyncio
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from app.persistence.repository import VisualSearchRepository


def async_context(value):
    context = MagicMock()
    context.__aenter__ = AsyncMock(return_value=value)
    context.__aexit__ = AsyncMock(return_value=None)
    return context


def test_active_model_reads_v2_registry() -> None:
    model_id = uuid4()
    cursor = AsyncMock()
    cursor.fetchone.return_value = (
        model_id,
        "HUGGING_FACE_LOCAL",
        "google/siglip2-base-patch16-224",
        768,
        "75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2",
        "siglip2-autoprocessor-original-v1",
    )
    connection = MagicMock()
    connection.cursor.return_value = async_context(cursor)

    model = asyncio.run(VisualSearchRepository.active_model_on(connection))

    assert model is not None
    assert model.id == model_id
    assert model.dimensions == 768
    assert "ai_retrieval.embedding_models" in cursor.execute.await_args.args[0]


def test_exact_search_groups_products_and_uses_stable_order() -> None:
    product_id = uuid4()
    image_id = uuid4()
    cursor = AsyncMock()
    cursor.fetchall.return_value = [
        (product_id, image_id, "https://res.cloudinary.com/demo/image.jpg", 0.91)
    ]
    connection = MagicMock()
    connection.cursor.return_value = async_context(cursor)

    rows = asyncio.run(
        VisualSearchRepository.search_on(connection, uuid4(), (0.5, 0.5), 10)
    )

    sql = cursor.execute.await_args.args[0]
    assert "ai_retrieval.search_product_images_exact" in sql
    assert "partition by result.product_id" in sql
    assert "order by similarity desc, product_id, image_id" in sql
    assert rows[0].product_id == product_id
    assert rows[0].image_id == image_id
    assert rows[0].similarity == 0.91


def test_exact_search_bounds_image_candidates_and_preserves_tie_order() -> None:
    first_product = uuid4()
    second_product = uuid4()
    first_image = uuid4()
    second_image = uuid4()
    model_id = uuid4()
    cursor = AsyncMock()
    cursor.fetchall.return_value = [
        (first_product, first_image, "https://example/first.jpg", 0.8),
        (second_product, second_image, "https://example/second.jpg", 0.8),
    ]
    connection = MagicMock()
    connection.cursor.return_value = async_context(cursor)

    rows = asyncio.run(
        VisualSearchRepository.search_on(connection, model_id, (0.5, 0.5), 20)
    )

    sql = cursor.execute.await_args.args[0]
    params = cursor.execute.await_args.args[1]
    assert "partition by result.product_id" in sql
    assert "where product_rank = 1" in sql
    assert "order by similarity desc, product_id, image_id" in sql
    assert params[2:] == (100, 20)
    assert [row.product_id for row in rows] == [first_product, second_product]
