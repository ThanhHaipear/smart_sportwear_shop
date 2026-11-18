from scripts.evaluate import metrics
from scripts.evaluate_dev_v3_followup import PRIOR_CANDIDATE, adjusted_scores


def test_recall_mrr_and_ndcg():
    result = metrics([{
        "relevantProductIds": ["p1", "p2"],
        "rankedProductIds": ["p1", "x", "p2"],
    }], 3)
    assert result["recall@3"] == 1
    assert result["mrr"] == 1
    assert 0 < result["ndcg@3"] <= 1


def test_pair_consensus_requires_corroboration_from_two_views():
    scores = {"tops": 0.31, "uniforms": 0.40, "shoes": 0.17, "unknown": 0.12}
    top = [{"similarity": 0.7, "product_type": "SPORTS_TOP"}]
    uniform = [{"similarity": 0.8, "product_type": "SPORTS_UNIFORM"}]
    views = {
        "full": {"scores": scores, "raw": top},
        "center": {"raw": uniform},
        "upper": {"raw": uniform},
    }
    profile = {**PRIOR_CANDIDATE, "pairMode": "retrieval", "pairBoost": 0.50}

    assert adjusted_scores(views, profile) == scores
    views["upper"]["raw"] = top
    adjusted = adjusted_scores(views, profile)
    assert adjusted["tops"] > adjusted["uniforms"]
    assert adjusted["unknown"] == scores["unknown"]
    assert scores["tops"] == 0.31
