from __future__ import annotations

from collections import Counter, defaultdict
import json
from pathlib import Path
from statistics import median
from typing import Any


GROUPS = ["tops", "bottoms", "uniforms", "shoes", "accessories"]
PRODUCT_GROUP = {
    "SPORTS_TOP": "tops",
    "SPORTS_BOTTOM": "bottoms",
    "SPORTS_UNIFORM": "uniforms",
    "SPORTS_SHOES": "shoes",
    "SPORTS_ACCESSORY": "accessories",
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((len(ordered) * fraction) + 0.999999) - 1))
    return round(ordered[index], 5)


def product_catalog(workspace: Path) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for directory in (
        workspace / "catalog" / "pilot-v1",
        workspace / "catalog" / "world-cup-2026-store-staging-v1",
    ):
        manifest = load_json(directory / "manifest.json")
        imported = load_json(directory / "import-result.json")
        by_slug = {item["slug"]: item for item in imported["products"]}
        for product in manifest["products"]:
            item = by_slug[product["slug"]]
            result[item["id"]] = {
                "id": item["id"],
                "slug": product["slug"],
                "name": product["name"],
                "group": PRODUCT_GROUP[product["productType"]],
            }
    return result


def diagnose_query(query: dict[str, Any], sample: dict[str, Any]) -> dict[str, Any]:
    expected = set(query["expectedProductIds"])
    results = query["results"]
    top = results[0] if results else None
    relevant_ranks = [item["rank"] for item in results if item["productId"] in expected]
    first_relevant_rank = min(relevant_ranks) if relevant_ranks else None
    raw_hit_5 = first_relevant_rank is not None and first_relevant_rank <= 5
    raw_hit_10 = first_relevant_rank is not None and first_relevant_rank <= 10
    accepted_hit_5 = any(
        item["rank"] <= 5 and item["accepted"] and item["productId"] in expected
        for item in results
    )
    reasons: list[str] = []
    if top is None:
        reasons.append("NO_RESULTS")
    else:
        if not top["accepted"]:
            reasons.append("LOCKED_THRESHOLD_REJECTED_TOP1")
        if top["categoryGroup"] != query["group"]:
            reasons.append("TOP1_CATEGORY_CONFUSION")
        if not raw_hit_5:
            reasons.append("NO_RELEVANT_PRODUCT_IN_RAW_TOP5")
        if raw_hit_5 and not accepted_hit_5:
            reasons.append("RELEVANT_RESULT_BELOW_THRESHOLD")
    return {
        "queryId": query["queryId"],
        "group": query["group"],
        "sourceCategory": sample.get("sourceCategory"),
        "sourceTitle": sample.get("source", {}).get("title"),
        "top1ProductId": top["productId"] if top else None,
        "top1Category": top["categoryGroup"] if top else None,
        "top1Similarity": round(float(top["similarity"]), 5) if top else None,
        "top1Accepted": bool(top and top["accepted"]),
        "top1Relevant": bool(top and top["productId"] in expected),
        "firstRelevantRank": first_relevant_rank,
        "rawHitAt5": raw_hit_5,
        "rawHitAt10": raw_hit_10,
        "acceptedHitAt5": accepted_hit_5,
        "reasons": reasons,
    }


def representative_cases(
    diagnostics: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    for group in GROUPS:
        items = [item for item in diagnostics if item["group"] == group]
        ranking_misses = [item for item in items if not item["rawHitAt5"]]
        threshold_misses = [
            item for item in items
            if item["top1Relevant"] and not item["top1Accepted"]
        ]
        if ranking_misses:
            selected.append(min(ranking_misses, key=lambda item: item["top1Similarity"] or 0.0))
        if threshold_misses:
            candidate = max(threshold_misses, key=lambda item: item["top1Similarity"] or 0.0)
            if candidate["queryId"] not in {item["queryId"] for item in selected}:
                selected.append(candidate)
    return selected


def markdown_report(analysis: dict[str, Any], product_by_id: dict[str, dict[str, str]]) -> str:
    lines = [
        "# HAI-08 Image Search Failure Analysis",
        "",
        "## Kết luận",
        "",
        "TEST v1 không đạt acceptance. Báo cáo này chỉ phân tích `ranked-results.json` đã khóa; không gọi lại endpoint, không đổi threshold và không dùng TEST v1 để tune.",
        "",
        "Nguyên nhân chính là tổ hợp của ba vấn đề: threshold chọn từ DEV synthetic không chuyển được sang ảnh thật; ranking theo category còn yếu; và một phần TEST dùng category nguồn rộng nên chủ thể/nhãn relevance chưa đủ sát sản phẩm catalog.",
        "",
        "## Tổng hợp định lượng",
        "",
        "| Chỉ số | Kết quả | Diễn giải |",
        "|---|---:|---|",
        f"| Acceptance Recall@5 | {analysis['acceptance']['recallAt5']:.2%} | Sau threshold đã khóa |",
        f"| Acceptance category accuracy | {analysis['acceptance']['categoryAccuracy']:.2%} | Sau threshold đã khóa |",
        f"| Raw top-1 relevant | {analysis['raw']['top1RelevantRate']:.2%} | Chẩn đoán trước threshold |",
        f"| Raw top-5 hit | {analysis['raw']['hitAt5Rate']:.2%} | Có ít nhất một relevant product trong top 5 |",
        f"| Raw top-10 hit | {analysis['raw']['hitAt10Rate']:.2%} | Có ít nhất một relevant product trong top 10 |",
        f"| Raw top-1 category | {analysis['raw']['top1CategoryRate']:.2%} | Category của kết quả đầu tiên |",
        f"| Positive vượt threshold ở top 1 | {analysis['threshold']['positiveAcceptedTop1']}/100 | Threshold `0.71233916` |",
        f"| Unknown rejection | {analysis['acceptance']['unknownRejectionRate']:.2%} | 20/20 unknown bị loại |",
        "",
        "Positive top-score median là `0.59598`, P95 là `0.70537`, vẫn thấp hơn threshold `0.71233916`; unknown top-score lớn nhất là `0.60379`. Threshold giải thích phần lớn retrieval failure, nhưng raw top-5 hit 79% và raw category 67% cho thấy chỉ hạ threshold vẫn không đạt mục tiêu.",
        "",
        "## Theo nhóm",
        "",
        "| Nhóm | Raw top-1 relevant | Raw hit@5 | Raw hit@10 | Raw category | Top-1 vượt threshold |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for group in GROUPS:
        item = analysis["groups"][group]
        lines.append(
            f"| {group} | {item['rawTop1Relevant']}/20 | {item['rawHitAt5']}/20 | "
            f"{item['rawHitAt10']}/20 | {item['rawTop1Category']}/20 | {item['acceptedTop1']}/20 |"
        )
    lines.extend([
        "",
        "Uniforms có raw hit@5 tốt nhất nhưng gần như toàn bộ bị threshold loại. Shoes và accessories có ranking yếu hơn; bottoms có nhiều ảnh người/toàn cảnh nên tín hiệu quần bị phân tán. Tops lẫn ảnh sản phẩm, người mặc và cảnh sự kiện. Contact-sheet review cũng phát hiện một số query không lấy sản phẩm làm chủ thể chính.",
        "",
        "## Confusion matrix raw top-1",
        "",
        "Hàng là nhóm kỳ vọng, cột là category của top-1 trước threshold.",
        "",
        "| Expected \\ Predicted | tops | bottoms | uniforms | shoes | accessories |",
        "|---|---:|---:|---:|---:|---:|",
    ])
    for expected in GROUPS:
        row = analysis["confusionMatrix"][expected]
        lines.append(
            f"| {expected} | " + " | ".join(str(row.get(predicted, 0)) for predicted in GROUPS) + " |"
        )
    lines.extend([
        "",
        "## Failure taxonomy",
        "",
        "| Loại lỗi | Số query | Ý nghĩa |",
        "|---|---:|---|",
    ])
    meanings = {
        "LOCKED_THRESHOLD_REJECTED_TOP1": "Top-1 không vượt threshold DEV đã khóa.",
        "TOP1_CATEGORY_CONFUSION": "Top-1 thuộc category khác nhóm kỳ vọng.",
        "NO_RELEVANT_PRODUCT_IN_RAW_TOP5": "Không có relevant product trong năm kết quả thô đầu.",
        "RELEVANT_RESULT_BELOW_THRESHOLD": "Có relevant product trong top 5 nhưng bị threshold loại.",
    }
    for reason, count in analysis["failureTaxonomy"].items():
        lines.append(f"| `{reason}` | {count} | {meanings.get(reason, '')} |")

    lines.extend([
        "",
        "## Case đại diện",
        "",
        "| Query | Nhóm/nguồn | Expected | Top-1 | Score | Relevant rank đầu | Nhận xét |",
        "|---|---|---|---|---:|---:|---|",
    ])
    for item in analysis["representativeCases"]:
        top_product = product_by_id.get(item["top1ProductId"], {})
        expected_names = ", ".join(item["expectedProductNames"][:3])
        if len(item["expectedProductNames"]) > 3:
            expected_names += ", …"
        top_name = top_product.get("name", item["top1ProductId"] or "N/A")
        reasons = ", ".join(item["reasons"])
        lines.append(
            f"| `{item['queryId']}` | {item['group']} / {item['sourceCategory']} | "
            f"{expected_names} | {top_name} ({item['top1Category']}) | "
            f"{item['top1Similarity']:.5f} | {item['firstRelevantRank'] or 'N/A'} | {reasons} |"
        )

    lines.extend([
        "",
        "## Review hình ảnh và nhãn",
        "",
        "- Tops: `test-003`, `test-010`, `test-011`, `test-014` và `test-015` là cảnh có nhiều người/vật thể hoặc áo chiếm diện tích nhỏ; `test-001`, `test-002`, `test-004`, `test-013` gần với ảnh sản phẩm hơn.",
        "- Bottoms: nhiều query là toàn thân hoặc hoạt động thể thao (`test-021`, `test-024`, `test-026`, `test-030`–`test-040`), khiến quần không phải chủ thể duy nhất; một số crop eo/quần (`test-023`, `test-033`, `test-035`) lại khác mạnh ảnh catalog nền sạch.",
        "- Uniforms: ảnh bóng rổ, bóng chày, bóng chuyền và cheerleading được gán relevant tới catalog đồng phục bóng đá. Đây là category-level substitute hợp protocol hiện tại nhưng tạo ground truth rộng và không đồng nhất về môn thể thao.",
        "- Shoes: có ảnh giày sản phẩm rõ (`test-064`, `test-068`, `test-069`, `test-071`–`test-075`, `test-078`–`test-080`) nhưng cũng có cảnh lịch sử/cửa hàng/người mang giày nhỏ (`test-061`, `test-063`, `test-067`, `test-070`, `test-076`, `test-077`).",
        "- Accessories: vật thể thường nhỏ hoặc ở trên người; bình nước và wristband có hình dáng gần nhiều vật dụng phổ thông. Nhãn subtype tốt hơn category-level nhưng catalog chỉ có một sản phẩm relevant cho mỗi subtype.",
        "- Color: N/A. TEST v1 không có expected color labels nên HAI-08.4 không thể tính confusion màu một cách hợp lệ; không suy đoán màu hậu nghiệm.",
        "",
        "## Raw candidates và grouped Commerce",
        "",
        "Ranked output đã lưu chứa kết quả sau khi gom theo product và mỗi query có tối đa một dòng/product; không có raw image-level candidates. Vì vậy có thể xác nhận invariant không trùng product, nhưng không thể tính quality delta raw-vs-grouped trên TEST v1 mà không gọi lại endpoint hoặc bổ sung telemetry hậu nghiệm. HAI-08 ghi nhận mục này là `N/A_NOT_RECORDED`; runner v2 phải lưu cả hai tầng trước khi mở TEST v2.",
        "",
        "## So sánh baseline",
        "",
        "| Run | Model/dataset | Recall@5 | Category | Kết luận |",
        "|---|---|---:|---:|---|",
        "| Voyage Phase-0 | `voyage-multimodal-3.5`, 4 alternate catalog views, 2 category | 100% | 100% | Feasibility-only, catalog-adjacent, không so trực tiếp |",
        "| Legacy Phase-10 | 100 synthetic query, chỉ tops/bottoms/shoes | 100% | 100% | Model không được ghi trong report; không có uniforms/accessories/unknown |",
        "| SigLIP2 DEV v1 | 50 catalog-derived positive + 5 unknown | 100% | 100% | Tuning-only, synthetic |",
        "| SigLIP2 TEST v1 | 100 ảnh độc lập + 20 unknown | 1.67% | 4% | Final acceptance FAILED |",
        "",
        "Các con số legacy không chứng minh Voyage tốt hơn SigLIP2 vì khác catalog, ground truth, số mẫu và mức độc lập. Không chạy Voyage hậu nghiệm trên TEST v1 để chọn model.",
        "",
        "## Synthetic so với ảnh độc lập",
        "",
        "DEV v1 biến đổi trực tiếp ảnh catalog nên giữ hình dáng, bố cục và phong cách nền của catalog; TEST v1 có người mặc, nhiều vật thể, nền phức tạp, subject nhỏ, ảnh lịch sử và nén camera. Chênh lệch 100% → 1.67% Recall@5 là bằng chứng DEV v1 đánh giá transformation robustness chứ không đại diện domain generalization.",
        "",
        "## Đề xuất không sửa TEST v1",
        "",
        "1. Giữ TEST v1 và kết quả FAILED bất biến; public rollout tiếp tục tắt.",
        "2. Tạo DEV v2 từ ảnh thực độc lập, cân bằng query-focus/context và có nhãn sport/subtype rõ; dùng DEV v2 để thử threshold, crop/segmentation và category-aware retrieval.",
        "3. Bổ sung runner v2 lưu raw image candidates trước grouping và grouped product results để đo ablation hợp lệ.",
        "4. Rà lại taxonomy uniforms theo môn thể thao; thêm nhãn `queryFocus` và `visualDifficulty`; chỉ gán color khi reviewer đồng thuận.",
        "5. Sau khi khóa pipeline bằng DEV v2, tạo TEST v2 mới, độc lập và khóa protocol trước thu thập; không chạy lại TEST v1 như một acceptance mới.",
        "6. Chỉ mở rollout khi TEST version mới đạt Recall@5, category accuracy, leakage và latency gates.",
        "",
        "## Trạng thái HAI-08",
        "",
        "HAI-08.1, HAI-08.3, HAI-08.5, HAI-08.6, HAI-08.7 và HAI-08.8 hoàn tất. HAI-08.2 được kết luận `N/A_NOT_RECORDED` có bằng chứng; HAI-08.4 là `N/A_MISSING_EXPECTED_COLOR_LABELS`. Không có phép đo nào được bịa hoặc suy ra từ việc chạy lại TEST.",
    ])
    return "\n".join(lines) + "\n"


def main() -> None:
    workspace = Path(__file__).resolve().parents[2]
    output_dir = workspace / "evidence" / "image-search-v2" / "test-v1-final"
    ranked = load_json(output_dir / "ranked-results.json")
    test_metrics = load_json(output_dir / "metrics.json")
    dev_metrics = load_json(
        workspace / "evidence" / "image-search-v2" / "dev-v1-benchmark-2026-10-24" / "metrics.json"
    )
    benchmark_config = load_json(output_dir / "benchmark-config.json")
    manifest = load_json(workspace / "datasets" / "image-search" / "test-v1" / "manifest.json")
    labels = load_json(workspace / "datasets" / "image-search" / "test-v1" / "labels.json")
    voyage = load_json(workspace / "evidence" / "visual-search" / "phase0-poc-report.json")
    legacy = load_json(workspace / "evidence" / "visual-search" / "phase10-benchmark-report.json")
    products = product_catalog(workspace)

    sample_by_id = {item["id"]: item for item in manifest["samples"]}
    label_by_id = {item["queryId"]: item for item in labels["cases"]}
    queries = ranked["queries"]
    positive_queries = [item for item in queries if item["group"] != "unknown"]
    unknown_queries = [item for item in queries if item["group"] == "unknown"]
    if len(positive_queries) != 100 or len(unknown_queries) != 20:
        raise ValueError("expected frozen TEST v1 distribution")

    diagnostics = [diagnose_query(item, sample_by_id[item["queryId"]]) for item in positive_queries]
    diagnostic_by_id = {item["queryId"]: item for item in diagnostics}
    confusion: dict[str, Counter[str]] = defaultdict(Counter)
    taxonomy: Counter[str] = Counter()
    group_summary: dict[str, dict[str, Any]] = {}
    for item in diagnostics:
        confusion[item["group"]][item["top1Category"] or "none"] += 1
        taxonomy.update(item["reasons"])

    for group in GROUPS:
        items = [item for item in diagnostics if item["group"] == group]
        group_summary[group] = {
            "sampleCount": len(items),
            "rawTop1Relevant": sum(item["top1Relevant"] for item in items),
            "rawHitAt5": sum(item["rawHitAt5"] for item in items),
            "rawHitAt10": sum(item["rawHitAt10"] for item in items),
            "rawTop1Category": sum(item["top1Category"] == group for item in items),
            "acceptedTop1": sum(item["top1Accepted"] for item in items),
            "topScoreP50": percentile([item["top1Similarity"] for item in items], 0.50),
            "topScoreP95": percentile([item["top1Similarity"] for item in items], 0.95),
        }

    representatives = representative_cases(diagnostics)
    for item in representatives:
        label = label_by_id[item["queryId"]]
        item["expectedProductNames"] = [product["productName"] for product in label["relevantProducts"]]

    positive_scores = [float(item["results"][0]["similarity"]) for item in positive_queries]
    unknown_scores = [float(item["results"][0]["similarity"]) for item in unknown_queries]
    analysis = {
        "schemaVersion": 1,
        "generatedFrom": benchmark_config["generatedAt"],
        "policy": {
            "offlineOnly": True,
            "testRerun": False,
            "testTuning": False,
            "lockedThreshold": benchmark_config["threshold"]["selectedSimilarityThreshold"],
        },
        "acceptance": {
            "passed": test_metrics["acceptance"]["passed"],
            "recallAt5": test_metrics["positive"]["recall"]["at5"],
            "categoryAccuracy": test_metrics["positive"]["categoryAccuracy"]["value"],
            "unknownRejectionRate": test_metrics["unknown"]["rejectionRate"],
            "requestFailureRate": test_metrics["requestFailureRate"]["value"],
            "latencyP95Ms": test_metrics["latencyMs"]["p95"],
        },
        "raw": {
            "top1Relevant": sum(item["top1Relevant"] for item in diagnostics),
            "top1RelevantRate": sum(item["top1Relevant"] for item in diagnostics) / 100,
            "hitAt5": sum(item["rawHitAt5"] for item in diagnostics),
            "hitAt5Rate": sum(item["rawHitAt5"] for item in diagnostics) / 100,
            "hitAt10": sum(item["rawHitAt10"] for item in diagnostics),
            "hitAt10Rate": sum(item["rawHitAt10"] for item in diagnostics) / 100,
            "top1Category": sum(item["top1Category"] == item["group"] for item in diagnostics),
            "top1CategoryRate": sum(item["top1Category"] == item["group"] for item in diagnostics) / 100,
        },
        "threshold": {
            "positiveAcceptedTop1": sum(item["top1Accepted"] for item in diagnostics),
            "positiveTopScore": {
                "minimum": round(min(positive_scores), 5),
                "p50": percentile(positive_scores, 0.50),
                "p95": percentile(positive_scores, 0.95),
                "maximum": round(max(positive_scores), 5),
            },
            "unknownTopScore": {
                "minimum": round(min(unknown_scores), 5),
                "p50": percentile(unknown_scores, 0.50),
                "p95": percentile(unknown_scores, 0.95),
                "maximum": round(max(unknown_scores), 5),
            },
        },
        "groups": group_summary,
        "confusionMatrix": {
            expected: {predicted: confusion[expected][predicted] for predicted in GROUPS}
            for expected in GROUPS
        },
        "failureTaxonomy": dict(sorted(taxonomy.items())),
        "representativeCases": representatives,
        "allPositiveDiagnostics": diagnostics,
        "ablation": {
            "groupedResultsUniqueByProduct": all(
                len({item["productId"] for item in query["results"]}) == len(query["results"])
                for query in queries
            ),
            "rawImageCandidatesRecorded": False,
            "rawVsGroupedQualityDelta": "N/A_NOT_RECORDED",
        },
        "colorAnalysis": "N/A_MISSING_EXPECTED_COLOR_LABELS",
        "baseline": {
            "voyagePhase0": {
                "model": voyage["model"],
                "sampleCount": voyage["metrics"]["queryCount"],
                "recallAt5": voyage["metrics"]["recallAt5"],
                "categoryAccuracy": voyage["metrics"]["categoryAccuracy"],
                "warning": "Alternate catalog views; feasibility-only and not comparable to independent TEST v1.",
            },
            "legacyPhase10": {
                "model": "NOT_RECORDED_IN_REPORT",
                "sampleCount": legacy["metrics"]["sample_count"],
                "recallAt5": legacy["metrics"]["recall_at_5"],
                "categoryAccuracy": legacy["metrics"]["category_accuracy"],
                "warning": "Synthetic tops/bottoms/shoes only; no uniforms, accessories or unknowns.",
            },
            "siglip2DevV1": {
                "sampleCount": dev_metrics["sampleCount"],
                "recallAt5": dev_metrics["positive"]["recall"]["at5"],
                "categoryAccuracy": dev_metrics["positive"]["categoryAccuracy"]["value"],
                "warning": "Catalog-derived synthetic tuning split; not final acceptance.",
            },
        },
    }
    write_json(output_dir / "failure-analysis.json", analysis)
    (output_dir / "failure-cases.md").write_text(
        markdown_report(analysis, products), encoding="utf-8"
    )
    print(json.dumps({
        "output": [
            str(output_dir / "failure-analysis.json"),
            str(output_dir / "failure-cases.md"),
        ],
        "rawHitAt5": analysis["raw"]["hitAt5Rate"],
        "rawTop1Category": analysis["raw"]["top1CategoryRate"],
        "testRerun": False,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
