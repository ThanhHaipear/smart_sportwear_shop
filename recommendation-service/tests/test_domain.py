from uuid import UUID

from app.domain import mine_association_rules, precision_recall_at_k, rank_rules


def uid(value): return UUID(f"00000000-0000-0000-0000-{value:012d}")


def test_mines_directed_rules_without_future_or_catalog_access():
    rules = mine_association_rules([{uid(1), uid(2)}, {uid(1), uid(2)}, {uid(1), uid(3)}],
                                   "association-v1", min_support=.5, min_confidence=.5, min_lift=0)
    assert {(rule.antecedent, rule.consequent) for rule in rules} == {(uid(1), uid(2)), (uid(2), uid(1))}
    selected = rank_rules(rules, {uid(1)}, 8)
    assert [rule.consequent for rule in selected] == [uid(2)]


def test_precision_recall_at_k_gate():
    precision, recall = precision_recall_at_k([([uid(1), uid(2)], {uid(2), uid(3)})], 2)
    assert precision == .5
    assert recall == .5
