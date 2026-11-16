from datetime import datetime, timezone
from uuid import UUID, uuid4

import httpx
import pytest

from app.catalog import CatalogUnavailable, HttpCatalogClient
from app.config import Settings
from app.enrichment import EventEnricher
from app.repository import MemoryRepository
from app.schemas import IntegrationEvent, ProductSnapshot
from app.service import RecommendationService
from tests.fakes import FakeCatalog, variant


def event(event_type, **fields):
    return IntegrationEvent.model_validate({
        "eventId": str(uuid4()), "eventType": event_type, "eventVersion": 1,
        "occurredAt": datetime.now(timezone.utc).isoformat(), "traceId": "test", **fields})


def setup():
    catalog, repo = FakeCatalog(), MemoryRepository()
    return catalog, repo, EventEnricher(catalog, repo)


def test_thin_catalog_event_builds_snapshot_from_active_variants_only():
    catalog, repo, enricher = setup()
    product = uuid4()
    catalog.add_product(product, version=7, slug="ao-chay-bo", variants=[
        variant(uuid4(), price=200, available=3), variant(uuid4(), price=150, available=2),
        variant(uuid4(), price=50, available=9, status="INACTIVE")],
        images=[{"id": str(uuid4()), "url": "https://img/2.jpg", "sortOrder": 2},
                {"id": str(uuid4()), "url": "https://img/1.jpg", "sortOrder": 1}])

    applied = repo.apply_event(enricher.enrich(event("PRODUCT_REINDEX_REQUESTED", productId=str(product), sourceVersion=7)))

    snapshot = repo.product_by_id[product]
    assert applied is True
    assert (snapshot.slug, snapshot.availableQuantity, snapshot.minPrice, snapshot.maxPrice) == ("ao-chay-bo", 5, 150, 200)
    assert snapshot.thumbnail == snapshot.thumbnailUrl == "https://img/1.jpg"


def test_inactive_product_skips_public_slug_and_is_not_recommendable():
    catalog, repo, enricher = setup()
    product = uuid4()
    catalog.add_product(product, status="INACTIVE", variants=[variant(uuid4())])
    repo.apply_event(enricher.enrich(event("PRODUCT_REINDEX_REQUESTED", productId=str(product), sourceVersion=1)))
    assert ("public_slug", product) not in catalog.calls
    assert repo.products([product]) == []


def test_deleted_product_becomes_tombstone():
    _, repo, enricher = setup()
    product = uuid4()
    repo.apply_event(enricher.enrich(event("PRODUCT_REINDEX_REQUESTED", productId=str(product), sourceVersion=3)))
    assert repo.product_by_id[product].status == "DELETED"


def test_older_snapshot_does_not_overwrite_newer_one():
    catalog, repo, enricher = setup()
    product = uuid4()
    catalog.add_product(product, version=5, slug="new", variants=[variant(uuid4())])
    repo.apply_event(enricher.enrich(event("PRODUCT_REINDEX_REQUESTED", productId=str(product), sourceVersion=5)))
    catalog.add_product(product, version=2, slug="old", variants=[variant(uuid4())])
    repo.apply_event(enricher.enrich(event("PRODUCT_REINDEX_REQUESTED", productId=str(product), sourceVersion=2)))
    assert repo.product_by_id[product].slug == "new"


def test_order_variants_are_resolved_to_distinct_products():
    catalog, repo, enricher = setup()
    shirt, shoes = uuid4(), uuid4()
    shirt_m, shirt_l, shoes_42 = uuid4(), uuid4(), uuid4()
    catalog.add_product(shirt, slug="shirt", variants=[variant(shirt_m), variant(shirt_l)])
    catalog.add_product(shoes, slug="shoes", variants=[variant(shoes_42)])
    order = uuid4()

    repo.apply_event(enricher.enrich(event("ORDER_CREATED", orderId=str(order), userRef="u1",
                                           items=[str(shirt_m), str(shirt_l), str(shoes_42)])))

    assert repo.order_baskets[order] == {shirt, shoes}
    assert repo.popularity == {shirt: 1, shoes: 1}


def test_order_items_that_are_already_product_ids_are_kept():
    catalog, repo, enricher = setup()
    product = uuid4()
    catalog.add_product(product, slug="p", variants=[variant(uuid4())])
    assert enricher.resolve_products([product]) == [product]


def test_unknown_order_item_is_dropped():
    _, _, enricher = setup()
    assert enricher.resolve_products([uuid4()]) == []


def test_same_order_republished_is_counted_once():
    catalog, repo, enricher = setup()
    product, item = uuid4(), uuid4()
    catalog.add_product(product, slug="p", variants=[variant(item)])
    order = uuid4()
    for _ in range(2):
        repo.apply_event(enricher.enrich(event("ORDER_CREATED", orderId=str(order), items=[str(item)])))
    assert repo.popularity[product] == 1


def test_rules_mined_from_resolved_baskets_serve_product_recommendations():
    catalog, repo, enricher = setup()
    shoes, socks = uuid4(), uuid4()
    shoes_v, socks_v = uuid4(), uuid4()
    catalog.add_product(shoes, slug="shoes", variants=[variant(shoes_v)])
    catalog.add_product(socks, slug="socks", variants=[variant(socks_v)])
    for _ in range(3):
        repo.apply_event(enricher.enrich(event("ORDER_CREATED", orderId=str(uuid4()),
                                               items=[str(shoes_v), str(socks_v)])))
    engine = RecommendationService(repo)
    engine.rebuild("association-test", min_lift=0)
    response = engine.recommend({shoes}, 4, "FREQUENTLY_BOUGHT_TOGETHER")
    assert [item.product.id for item in response.items] == [socks]
    assert response.items[0].algorithm == "ASSOCIATION_RULE"


def test_recompute_purchase_counts_after_basket_repair():
    _, repo, _ = setup()
    a, b = uuid4(), uuid4()
    for product in (a, b):
        repo.upsert_product(ProductSnapshot(id=product, name="x", slug=str(product), availableQuantity=1), 1)
    repo.order_baskets = {uuid4(): {a, b}, uuid4(): {a}}
    repo.recompute_purchase_counts()
    assert repo.popularity == {a: 2, b: 1}


def http_client(handler):
    settings = Settings(catalog_service_token="secret", catalog_api_url="http://catalog")
    return HttpCatalogClient(settings, transport=httpx.MockTransport(handler))


def test_http_client_sends_token_and_maps_404_to_none():
    seen = {}
    def handler(request):
        seen["token"] = request.headers.get("X-Internal-Service-Token")
        return httpx.Response(404)
    assert http_client(handler).variant_product_id(uuid4()) is None
    assert seen["token"] == "secret"


def test_http_client_reads_public_slug_from_envelope_without_token():
    product = uuid4()
    def handler(request):
        assert "X-Internal-Service-Token" not in request.headers
        return httpx.Response(200, json={"data": {"id": str(product), "slug": "giay-chay"}})
    assert http_client(handler).public_slug(product) == "giay-chay"


def test_http_client_raises_on_server_error_so_event_is_retried():
    with pytest.raises(CatalogUnavailable):
        http_client(lambda request: httpx.Response(503)).index_document(uuid4())


def test_http_client_requires_token():
    with pytest.raises(ValueError):
        HttpCatalogClient(Settings(catalog_service_token=""))


def test_sale_snapshot_product_id_is_parsed():
    product = uuid4()
    client = http_client(lambda request: httpx.Response(200, json={"variantId": str(uuid4()), "productId": str(product)}))
    assert client.variant_product_id(uuid4()) == product
    assert isinstance(client.variant_product_id(uuid4()), UUID)
