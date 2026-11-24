import copy
import json
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from scripts.generate_synthetic_interactions import generate, jsonl_bytes, load_products

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = REPO_ROOT / "datasets/recommendation/synthetic-v1/config.json"


@pytest.fixture(scope="module")
def config():
    data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    data["users"]["count"] = 120  # keep the test fast; behaviour rules do not depend on the count
    return data


@pytest.fixture(scope="module")
def products(config):
    snapshot = json.loads((REPO_ROOT / config["catalogSnapshot"]).read_text(encoding="utf-8"))
    return load_products(snapshot, config)


@pytest.fixture(scope="module")
def generated(config, products):
    return generate(config, products)


def parse(timestamp: str) -> datetime:
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00"))


def test_same_seed_gives_identical_files(config, products, generated):
    again = generate(copy.deepcopy(config), products)
    assert jsonl_bytes(again.interactions) == jsonl_bytes(generated.interactions)
    assert jsonl_bytes(again.baskets) == jsonl_bytes(generated.baskets)


def test_different_seed_changes_data(config, products, generated):
    other = copy.deepcopy(config)
    other["seed"] += 1
    assert jsonl_bytes(generate(other, products).baskets) != jsonl_bytes(generated.baskets)


def test_only_catalog_products_and_synthetic_rows(products, generated):
    ids = {p.id for p in products}
    assert {row["productId"] for row in generated.interactions} <= ids
    assert {pid for basket in generated.baskets for pid in basket["productIds"]} <= ids
    assert {row["dataSource"] for row in generated.interactions} == {"SYNTHETIC"}
    assert {row["eventType"] for row in generated.interactions} <= {"VIEW", "WISHLIST", "ADD_TO_CART"}
    assert len({row["eventId"] for row in generated.interactions}) == len(generated.interactions)


def test_events_stay_inside_window(config, generated):
    start = parse(config["t0"])
    end = start + timedelta(weeks=config["weeks"])
    for row in generated.interactions + generated.baskets:
        assert start <= parse(row["occurredAt"]) < end + timedelta(hours=2)  # a session may run past the end


def test_funnel_order_view_then_cart_then_purchase(generated):
    first_view = {}
    carted = defaultdict(list)
    for row in generated.interactions:
        key = (row["userRef"], row["productId"])
        if row["eventType"] == "VIEW":
            first_view.setdefault(key, row["occurredAt"])
        if row["eventType"] == "ADD_TO_CART":
            assert key in first_view and first_view[key] <= row["occurredAt"]
            carted[key].append(row["occurredAt"])
    for basket in generated.baskets:
        assert basket["productIds"] == sorted(set(basket["productIds"]))
        for product_id in basket["productIds"]:
            times = carted[(basket["userRef"], product_id)]
            assert any(t <= basket["occurredAt"] for t in times)


def test_new_arrivals_absent_before_launch(config, products, generated):
    launch = parse(config["t0"]) + timedelta(days=config["newArrivals"]["launchDay"])
    new_ids = {p.id for p in products if p.launch_day > 0}
    assert len(new_ids) == len(config["newArrivals"]["slugs"])
    for row in generated.interactions:
        if row["productId"] in new_ids:
            assert parse(row["occurredAt"]) >= launch


def test_noise_share_matches_config(config, generated):
    picks = generated.picks
    share = picks["noise"] / sum(picks.values())
    assert abs(share - config["session"]["noiseRate"] * (1 - picks["complement"] / sum(picks.values()))) < 0.05


def test_complement_purchases_create_multi_item_baskets(generated):
    assert generated.picks["complement"] > 0
    assert any(len(basket["productIds"]) >= 2 for basket in generated.baskets)


def test_products_missing_attributes_are_rejected(config):
    snapshot = {"products": [{"productId": "p1", "slug": "x", "status": "ACTIVE", "minPrice": 1.0,
                              "productType": None, "gender": "MEN", "sportCodes": ["RUNNING"], "brand": "nike"}]}
    with pytest.raises(ValueError):
        load_products(snapshot, {**config, "newArrivals": {"launchDay": 49, "slugs": []}})
