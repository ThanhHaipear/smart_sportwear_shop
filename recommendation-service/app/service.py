from uuid import UUID

from app.content import MODES, build_items, item_scores, price_thresholds
from app.domain import mine_association_rules, rank_rules
from app.repository import Repository
from app.schemas import ProductSnapshot, RecommendationItem, RecommendationResponse


def content_row(product: ProductSnapshot) -> dict:
    """Content features of a stored snapshot. Category and brand are compared by id."""
    return {"id": product.id, "productType": product.productType,
            "category": str(product.categoryId) if product.categoryId else None,
            "brand": str(product.brandId) if product.brandId else None, "gender": product.gender,
            "sportCodes": product.sportCodes, "colorFamilies": product.colorFamilies, "minPrice": product.minPrice}


class RecommendationService:
    """Layered hybrid (docs/recommendation-design.md 5.5): association rules, then content, then popularity.

    Each layer only fills positions left empty by the previous ones, and every item keeps the reason code of
    the layer that produced it. `content_mode="OFF"` skips the content layer.
    """

    def __init__(self, repository: Repository, fallback_mode: str = "POPULAR", content_mode: str = "OFF"):
        if content_mode != "OFF" and content_mode not in MODES:
            raise ValueError(f"unknown content mode {content_mode}")
        self.repository = repository
        self.fallback_mode = fallback_mode
        self.content_mode = content_mode

    def recommend(self, sources: set[UUID], limit: int, kind: str) -> RecommendationResponse:
        rules = rank_rules(self.repository.rules_for(sources), sources, limit)
        products = {product.id: product for product in self.repository.products([rule.consequent for rule in rules])}
        items = [RecommendationItem(product=products[rule.consequent], score=rule.confidence * rule.lift,
                algorithm="ASSOCIATION_RULE", reason="Frequently purchased together", support=rule.support,
                confidence=rule.confidence, lift=rule.lift, pairCount=rule.pair_count)
                 for rule in rules if rule.consequent in products]
        if len(items) < limit and self.content_mode != "OFF":
            items.extend(self._content(sources, sources | {item.product.id for item in items}, limit - len(items)))
        if len(items) < limit and self.fallback_mode == "POPULAR":
            excluded = sources | {item.product.id for item in items}
            items.extend(RecommendationItem(product=product, score=0, algorithm="POPULARITY",
                         reason="Popular active product") for product in self.repository.popular(excluded, limit-len(items)))
        versions = {rule.model_version for rule in rules}
        response = RecommendationResponse(sourceProductId=next(iter(sources)) if len(sources)==1 else None,
            sourceProductIds=sorted(sources, key=str), type=kind,
            modelVersion=next(iter(versions)) if len(versions)==1 else "popularity-v1", items=items[:limit])
        self.repository.record_response(response)
        return response

    def _content(self, sources: set[UUID], excluded: set[UUID], limit: int) -> list[RecommendationItem]:
        candidates = [p for p in self.repository.candidates() if p.id not in excluded]
        source_products = self.repository.snapshots(sorted(sources, key=str))
        if not candidates or not source_products:
            return []
        thresholds = price_thresholds(p.minPrice for p in candidates)
        items = build_items([content_row(p) for p in candidates + source_products], thresholds)
        scores = item_scores([p.id for p in source_products], items, [p.id for p in candidates], self.content_mode)
        by_id = {p.id: p for p in candidates}
        ranked = sorted(scores, key=lambda pid: (-scores[pid], str(pid)))[:limit]
        return [RecommendationItem(product=by_id[pid], score=scores[pid], algorithm="SIMILAR_CONTENT",
                                   reason="Matches the sport and style of the product") for pid in ranked]

    def rebuild(self, model_version: str, min_support=.01, min_confidence=.1, min_lift=1.0) -> int:
        rules = mine_association_rules(self.repository.baskets(), model_version, min_support, min_confidence, min_lift)
        self.repository.replace_rules(model_version, rules)
        return len(rules)
