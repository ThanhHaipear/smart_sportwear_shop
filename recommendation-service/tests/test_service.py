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


def attributed(value, ptype, sport, quantity=10, gender="MEN"):
    return ProductSnapshot(id=uid(value), name=f"Product {value}", slug=f"product-{value}", availableQuantity=quantity,
                           productType=ptype, gender=gender, sportCodes=[sport], minPrice=100.0 * value)


def test_content_layer_fills_between_rules_and_popularity():
    repository = MemoryRepository()
    repository.product_by_id = {
        uid(1): attributed(1, "SPORTS_SHOES", "RUNNING"),
        uid(2): attributed(2, "SPORTS_ACCESSORY", "RUNNING"),     # rule target
        uid(3): attributed(3, "SPORTS_BOTTOM", "RUNNING"),        # complement, same sport
        uid(4): attributed(4, "SPORTS_SHOES", "RUNNING"),         # same type: never a complement
        uid(5): attributed(5, "SPORTS_TOP", "FOOTBALL", gender="WOMEN"),  # shares nothing: left to popularity
    }
    repository.popularity = {uid(5): 9, uid(4): 1}
    repository.rule_list = [Rule(uid(1), uid(2), .5, .8, 1.2, 4, "v1")]
    response = RecommendationService(repository, content_mode="COMPLEMENT").recommend(
        {uid(1)}, 4, "FREQUENTLY_BOUGHT_TOGETHER")
    assert [(item.product.id, item.algorithm) for item in response.items] == [
        (uid(2), "ASSOCIATION_RULE"), (uid(3), "SIMILAR_CONTENT"), (uid(5), "POPULARITY"), (uid(4), "POPULARITY")]


def test_content_layer_without_attributes_falls_through_to_popularity():
    repository = MemoryRepository()
    repository.product_by_id = {uid(1): product(1), uid(2): product(2), uid(3): product(3)}
    repository.popularity = {uid(3): 5}
    response = RecommendationService(repository, content_mode="COMPLEMENT").recommend(
        {uid(1)}, 2, "FREQUENTLY_BOUGHT_TOGETHER")
    assert [item.algorithm for item in response.items] == ["POPULARITY", "POPULARITY"]
