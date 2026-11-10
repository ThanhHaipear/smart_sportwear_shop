from collections import Counter, defaultdict
from dataclasses import dataclass
from itertools import combinations
from uuid import UUID


@dataclass(frozen=True)
class Rule:
    antecedent: UUID
    consequent: UUID
    support: float
    confidence: float
    lift: float
    pair_count: int
    model_version: str


def mine_association_rules(
    baskets: list[set[UUID]], model_version: str, min_support: float = 0.01,
    min_confidence: float = 0.1, min_lift: float = 1.0,
) -> list[Rule]:
    baskets = [set(basket) for basket in baskets if basket]
    if not baskets:
        return []
    product_counts: Counter[UUID] = Counter()
    pair_counts: Counter[tuple[UUID, UUID]] = Counter()
    for basket in baskets:
        product_counts.update(basket)
        pair_counts.update(combinations(sorted(basket, key=str), 2))
    total = len(baskets)
    rules: list[Rule] = []
    for (left, right), pair_count in pair_counts.items():
        for antecedent, consequent in ((left, right), (right, left)):
            support = pair_count / total
            confidence = pair_count / product_counts[antecedent]
            lift = confidence / (product_counts[consequent] / total)
            if support >= min_support and confidence >= min_confidence and lift >= min_lift:
                rules.append(Rule(antecedent, consequent, support, confidence, lift, pair_count, model_version))
    return sorted(rules, key=lambda rule: (-rule.confidence, -rule.lift, -rule.support, str(rule.consequent)))


def rank_rules(rules: list[Rule], sources: set[UUID], limit: int) -> list[Rule]:
    best: dict[UUID, Rule] = {}
    for rule in rules:
        if rule.antecedent not in sources or rule.consequent in sources:
            continue
        current = best.get(rule.consequent)
        key = lambda value: (value.confidence, value.lift, value.support, value.pair_count)
        if current is None or key(rule) > key(current):
            best[rule.consequent] = rule
    return sorted(best.values(), key=lambda rule: (-rule.confidence, -rule.lift, -rule.support, str(rule.consequent)))[:limit]


def precision_recall_at_k(cases: list[tuple[list[UUID], set[UUID]]], k: int) -> tuple[float, float]:
    precision = []
    recall = []
    for predicted, relevant in cases:
        hits = len(set(predicted[:k]) & relevant)
        precision.append(hits / k)
        recall.append(hits / len(relevant) if relevant else 0.0)
    return sum(precision) / len(precision), sum(recall) / len(recall)
