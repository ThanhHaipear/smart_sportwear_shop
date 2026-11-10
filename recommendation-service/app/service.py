from uuid import UUID

from app.domain import mine_association_rules, rank_rules
from app.repository import Repository
from app.schemas import RecommendationItem, RecommendationResponse


class RecommendationService:
    def __init__(self, repository: Repository, fallback_mode: str = "POPULAR"):
        self.repository = repository
        self.fallback_mode = fallback_mode

    def recommend(self, sources: set[UUID], limit: int, kind: str) -> RecommendationResponse:
        rules = rank_rules(self.repository.rules_for(sources), sources, limit)
        products = {product.id: product for product in self.repository.products([rule.consequent for rule in rules])}
        items = [RecommendationItem(product=products[rule.consequent], score=rule.confidence * rule.lift,
                algorithm="ASSOCIATION_RULE", reason="Frequently purchased together", support=rule.support,
                confidence=rule.confidence, lift=rule.lift, pairCount=rule.pair_count)
                 for rule in rules if rule.consequent in products]
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

    def rebuild(self, model_version: str, min_support=.01, min_confidence=.1, min_lift=1.0) -> int:
        rules = mine_association_rules(self.repository.baskets(), model_version, min_support, min_confidence, min_lift)
        self.repository.replace_rules(model_version, rules)
        return len(rules)
