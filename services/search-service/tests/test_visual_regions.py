from PIL import Image

from app.services.visual_regions import ACCESSORY_TYPES, image_regions, narrow_accessory_ranking, rescue_region_ranking


def test_regions_keep_corner_and_center_coverage_without_distorting_aspect_ratio():
    image = Image.new("RGB", (400, 200))
    image.putpixel((399, 199), (255, 0, 0))
    regions = image_regions(image)

    assert len(regions) == 5
    assert all(region.size == (224, 112) for region in regions)
    assert regions[3].getpixel((223, 111)) == (255, 0, 0)
    assert image_regions(Image.new("RGB", (80, 500))) == []


def test_region_must_meet_rescue_score():
    weak = [{"similarity": 0.725}]
    strong = [{"similarity": 0.76}]

    assert rescue_region_ranking([weak]) == []
    assert rescue_region_ranking([weak, strong]) is strong


def test_auto_search_filters_other_accessories_only_when_top_match_and_subtype_agree():
    ids = {subtype: product_id for product_id, subtype in ACCESSORY_TYPES.items()}
    ranked = [
        {"category_group": "accessories", "product_id": ids["accessory-bottle"]},
        {"category_group": "accessories", "product_id": ids["accessory-socks"]},
        {"category_group": "bottoms", "product_id": "other-product"},
    ]
    scores = dict.fromkeys(ids, 0.04)
    assert narrow_accessory_ranking(ranked, scores) is ranked
    assert narrow_accessory_ranking(ranked, {**scores, "accessory-socks": 0.1}) is ranked
    assert narrow_accessory_ranking(ranked, {**scores, "accessory-bottle": 0.1}) == ranked[:1]
