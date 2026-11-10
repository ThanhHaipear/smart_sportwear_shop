import argparse
import json
import math
from pathlib import Path


def metrics(rows: list[dict], k: int) -> dict[str, float]:
    recalls, reciprocal, ndcgs = [], [], []
    for row in rows:
        relevant = set(row["relevantProductIds"])
        ranked = row["rankedProductIds"][:k]
        hits = [1 if product_id in relevant else 0 for product_id in ranked]
        recalls.append(sum(hits) / len(relevant) if relevant else 0)
        reciprocal.append(next((1 / (i + 1) for i, hit in enumerate(hits) if hit), 0))
        dcg = sum(hit / math.log2(i + 2) for i, hit in enumerate(hits))
        ideal = sum(1 / math.log2(i + 2) for i in range(min(len(relevant), k)))
        ndcgs.append(dcg / ideal if ideal else 0)
    mean = lambda values: sum(values) / len(values) if values else 0
    return {f"recall@{k}": mean(recalls), "mrr": mean(reciprocal), f"ndcg@{k}": mean(ndcgs)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--k", type=int, default=10)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = metrics(json.loads(args.input.read_text(encoding="utf-8")), args.k)
    output = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
