from scripts.evaluate import metrics


def test_recall_mrr_and_ndcg():
    result = metrics([{
        "relevantProductIds": ["p1", "p2"],
        "rankedProductIds": ["p1", "x", "p2"],
    }], 3)
    assert result["recall@3"] == 1
    assert result["mrr"] == 1
    assert 0 < result["ndcg@3"] <= 1
