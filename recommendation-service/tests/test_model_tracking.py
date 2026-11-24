"""Phase 8: every model can be traced to its configuration and training data; every response is logged."""
from datetime import datetime, timedelta, timezone
from uuid import UUID

from app.domain import BasketRow, training_checksum
from app.repository import MemoryRepository
from app.schemas import ProductSnapshot
from app.service import DEFAULT_MIN_CONFIDENCE, DEFAULT_MIN_SUPPORT, RecommendationService
from tests.test_contracts import client

NOW = datetime(2026, 11, 17, 12, tzinfo=timezone.utc)


def uid(value): return UUID(f"00000000-0000-0000-0000-{value:012d}")


def repository_with_baskets() -> MemoryRepository:
    repo = MemoryRepository()
    for order, items, days_ago in ((10, {1, 2}, 3), (11, {1, 2}, 2), (12, {1, 3}, -1)):  # order 12 is in the future
        repo.order_baskets[uid(order)] = {uid(i) for i in items}
        repo.basket_times[uid(order)] = NOW - timedelta(days=days_ago)
    return repo


def test_rebuild_records_config_cutoff_and_checksum_of_training_baskets():
    repo = repository_with_baskets()
    count = RecommendationService(repo).rebuild("association-a", cutoff=NOW)
    metadata = repo.models[("ASSOCIATION_RULE", "association-a")]["metadata"]
    assert count == 2  # only 1<->2 from the two past baskets; the future 1-3 basket is ignored
    assert metadata.training_cutoff_at == NOW
    assert metadata.config["baskets"] == 2 and metadata.config["rules"] == 2
    assert (metadata.config["minSupport"], metadata.config["minConfidence"]) == (DEFAULT_MIN_SUPPORT,
                                                                                 DEFAULT_MIN_CONFIDENCE)
    assert metadata.training_event_checksum == training_checksum(repo.baskets_before(NOW))
    assert len(metadata.training_event_checksum) == 64


def test_new_model_archives_previous_one():
    repo = repository_with_baskets()
    service = RecommendationService(repo)
    service.rebuild("association-a", cutoff=NOW)
    service.rebuild("association-b", cutoff=NOW + timedelta(days=2))
    assert repo.models[("ASSOCIATION_RULE", "association-a")]["status"] == "ARCHIVED"
    assert repo.models[("ASSOCIATION_RULE", "association-b")]["status"] == "ACTIVE"
    assert repo.models[("ASSOCIATION_RULE", "association-b")]["metadata"].config["baskets"] == 3


def test_training_checksum_ignores_row_and_item_order_but_not_content():
    rows = [BasketRow("o1", ("b", "a"), NOW), BasketRow("o2", ("c",), NOW)]
    same = [BasketRow("o2", ("c",), NOW), BasketRow("o1", ("a", "b"), NOW.astimezone(timezone(timedelta(hours=7))))]
    assert training_checksum(rows) == training_checksum(same)
    assert training_checksum(rows) != training_checksum(rows[:1])


def test_response_log_has_latency_and_user_ref():
    repo = MemoryRepository()
    repo.product_by_id = {uid(1): ProductSnapshot(id=uid(1), name="a", slug="a", availableQuantity=1),
                          uid(2): ProductSnapshot(id=uid(2), name="b", slug="b", availableQuantity=1)}
    response = RecommendationService(repo).recommend({uid(1)}, 2, "FREQUENTLY_BOUGHT_TOGETHER", "user-7")
    log = repo.request_log[-1]
    assert log["requestId"] == response.requestId and log["userRef"] == "user-7"
    assert isinstance(log["latencyMs"], int) and log["latencyMs"] >= 0


def test_api_logs_gateway_user_without_echoing_it():
    repo = MemoryRepository()
    repo.product_by_id = {uid(1): ProductSnapshot(id=uid(1), name="a", slug="a", availableQuantity=1),
                          uid(2): ProductSnapshot(id=uid(2), name="b", slug="b", availableQuantity=1)}
    api = client(repo)
    response = api.get(f"/api/v1/recommendations/products/{uid(1)}", headers={"X-User-Id": "user-9"})
    assert response.status_code == 200 and "user-9" not in response.text
    assert repo.request_log[-1]["userRef"] == "user-9"
    api.get(f"/api/v1/recommendations/products/{uid(1)}")
    assert repo.request_log[-1]["userRef"] is None
