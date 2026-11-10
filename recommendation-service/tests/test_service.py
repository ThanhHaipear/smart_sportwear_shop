from uuid import UUID

from app.domain import Rule
from app.repository import MemoryRepository
from app.schemas import ProductSnapshot
from app.service import RecommendationService


def uid(value): return UUID(f"00000000-0000-0000-0000-{value:012d}")


def product(value, quantity=10):
    return ProductSnapshot(id=uid(value), name=f"Product {value}", slug=f"product-{value}", availableQuantity=quantity)


def test_rule_ranking_filters_unavailable_and_falls_back_to_popular():
    repository = MemoryRepository()
    repository.product_by_id = {uid(1): product(1), uid(2): product(2, 0), uid(3): product(3)}
    repository.popularity = {uid(3): 10}
    repository.rule_list = [Rule(uid(1), uid(2), .5, .8, 1.2, 4, "v1")]
    response = RecommendationService(repository).recommend({uid(1)}, 2, "FREQUENTLY_BOUGHT_TOGETHER")
    assert [item.product.id for item in response.items] == [uid(3)]
    assert response.items[0].algorithm == "POPULARITY"


def test_model_job_replaces_rules():
    repository = MemoryRepository()
    repository.order_baskets = {uid(10): {uid(1), uid(2)}, uid(11): {uid(1), uid(2)}}
    count = RecommendationService(repository).rebuild("v2", min_lift=0)
    assert count == 2
    assert {rule.model_version for rule in repository.rule_list} == {"v2"}
