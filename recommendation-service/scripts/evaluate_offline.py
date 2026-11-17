"""Offline evaluation of recommendation algorithms on a frozen split (docs/recommendation-design.md, section 7).

Every algorithm is trained only on rows strictly before the split's `trainUntil` and ranks the split's frozen
candidate set. Results go to `evidence/recommendation/<dataset>-<split>-<stage>-<date>.json`.

Phase 6 (`--stage phase6`) runs on DEV:
1. random baseline;
2. popularity with default weights and half-life in {none, 14, 7} days -> pick best;
3. popularity with the alternative weight set at the picked half-life (sensitivity only, never picked);
4. association rules, pure (no fill), on the 3 x 3 support/confidence grid -> pick best;
5. association rules filled with the picked popularity (the current serving behaviour).
Selection metric: mean NDCG@5 of FREQUENTLY_BOUGHT_TOGETHER cases; ties go to the simpler configuration.

Phase 7 (`--stage phase7`) runs on DEV with the phase 6 choices read from the phase 6 DEV report:
1. content-based item-item, SIMILAR vs COMPLEMENT -> pick (FBT NDCG@5, ties to SIMILAR);
2. layered hybrid for FBT: association rules -> picked content -> popularity;
3. HOME: user-profile content alone, and layered profile -> popularity, against popularity.

The TEST split may only be evaluated once with final configurations, so it needs `--final`.

Usage:
    python scripts/evaluate_offline.py --dataset ../datasets/recommendation/synthetic-v1 --split dev --stage phase6
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.content import (  # noqa: E402
    build_items, item_scores, layered, price_thresholds, profile_scores, user_profile)
from app.domain import (  # noqa: E402
    ALTERNATIVE_EVENT_WEIGHTS, EVENT_WEIGHTS, WeightedEvent, mine_association_rules, popularity_scores,
    rank_by_score, rank_rules)
from app.metrics import METRICS, bootstrap_ci, case_metrics, coverage_at_k, grouped_mean, mean  # noqa: E402
from app.offline_split import before, parse  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
K_VALUES = (5, 10)
MAX_K = max(K_VALUES)
SEED = 20261117
HALF_LIVES = (None, 14.0, 7.0)                      # ordered from simplest to most complex
SUPPORTS = (0.02, 0.01, 0.005)                      # higher threshold = fewer rules = simpler
CONFIDENCES = (0.20, 0.10, 0.05)


@dataclass
class TrainingData:
    events: list[WeightedEvent]
    baskets: list[set[str]]
    bought_by_user: dict[str, set[str]]
    events_by_user: dict[str, list[WeightedEvent]]


@dataclass(frozen=True)
class Config:
    name: str
    algorithm: str
    params: dict
    fbt: Callable[[str, list[str], str], list[str]] | None = None  # (source, candidates, case key) -> ranking
    home: Callable[[str, list[str]], list[str]] | None = None       # (user, candidates) -> ranking


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def training_data(interactions: list[dict], baskets: list[dict], cutoff: datetime) -> TrainingData:
    train_interactions, train_baskets = before(interactions, cutoff), before(baskets, cutoff)
    events = [WeightedEvent(row["productId"], row["eventType"], parse(row["occurredAt"]),
                            row.get("userRef") or row.get("anonymousId")) for row in train_interactions]
    bought: dict[str, set[str]] = {}
    for basket in train_baskets:
        moment = parse(basket["occurredAt"])
        events.extend(WeightedEvent(pid, "PURCHASE", moment, basket["userRef"]) for pid in basket["productIds"])
        bought.setdefault(basket["userRef"], set()).update(basket["productIds"])
    by_user: dict[str, list[WeightedEvent]] = {}
    for event in events:
        if event.actor is not None:
            by_user.setdefault(event.actor, []).append(event)
    return TrainingData(events, [set(b["productIds"]) for b in train_baskets], bought, by_user)


def random_config(data: TrainingData) -> Config:
    def ranking(key: str, candidates: list[str], exclude: set[str]) -> list[str]:
        pool = [item for item in candidates if item not in exclude]
        return random.Random(f"{SEED}:{key}").sample(pool, min(MAX_K, len(pool)))
    return Config("random", "RANDOM", {"seed": SEED},
                  fbt=lambda source, candidates, key: ranking(key, candidates, {source}),
                  home=lambda user, candidates: ranking(user, candidates, data.bought_by_user.get(user, set())))


def popularity_config(data: TrainingData, cutoff: datetime, half_life: float | None,
                      weights: dict[str, float], label: str) -> Config:
    scores = popularity_scores(data.events, cutoff, half_life, weights)
    name = f"popularity-{label}-hl{'none' if half_life is None else int(half_life)}"
    return Config(name, "POPULARITY", {"halfLifeDays": half_life, "weights": label, "eventWeights": weights},
                  fbt=lambda source, candidates, key: rank_by_score(scores, candidates, {source}, MAX_K),
                  home=lambda user, candidates: rank_by_score(scores, candidates,
                                                              data.bought_by_user.get(user, set()), MAX_K))


def association_config(data: TrainingData, min_support: float, min_confidence: float,
                       fill: Config | None = None) -> Config:
    name = f"association-s{min_support}-c{min_confidence}" + (f"+{fill.name}" if fill else "")
    rules = mine_association_rules(data.baskets, name, min_support, min_confidence, 1.0)

    def fbt(source: str, candidates: list[str], key: str) -> list[str]:
        allowed = set(candidates)
        ranked = [rule.consequent for rule in rank_rules(rules, {source}, len(rules))
                  if rule.consequent in allowed][:MAX_K]
        if fill is not None and len(ranked) < MAX_K:
            ranked += [item for item in fill.fbt(source, candidates, key) if item not in ranked][:MAX_K - len(ranked)]
        return ranked

    params = {"minSupport": min_support, "minConfidence": min_confidence, "minLift": 1.0, "rules": len(rules),
              "fill": fill.name if fill else None}
    return Config(name, "ASSOCIATION_RULE" if fill is None else "ASSOCIATION_RULE+POPULARITY", params, fbt=fbt)


def evaluate(config: Config, split: dict) -> dict:
    candidates = split["candidates"]["productIds"]
    result = {"name": config.name, "algorithm": config.algorithm, "params": config.params, "tasks": {}}
    tasks = []
    if config.fbt is not None:
        tasks.append(("fbt", split["fbt"]["cases"], lambda case: config.fbt(case["sourceProductId"], candidates,
                                                                              case["caseId"]),
                      lambda case: {pid: 1 for pid in case["relevant"]}, "orderId"))
    if config.home is not None:
        tasks.append(("home", split["home"]["cases"], lambda case: config.home(case["userRef"], candidates),
                      lambda case: case["relevant"], "userRef"))
    for task, cases, rank, relevant_of, group_key in tasks:
        lists = [rank(case) for case in cases]
        segments = {"all": list(range(len(cases)))}
        for segment in ("warm", "cold"):
            segments[segment] = [i for i, case in enumerate(cases) if case["segment"] == segment]
        task_result = {}
        for segment, indices in segments.items():
            rows = {"n": len(indices)}
            for k in K_VALUES:
                per_case = [case_metrics(lists[i], relevant_of(cases[i]), k) for i in indices]
                groups = [cases[i][group_key] for i in indices]
                for metric in METRICS:
                    values = [row[metric] for row in per_case]
                    low, high = bootstrap_ci(values, groups, SEED)
                    rows[f"{metric}@{k}"] = {"mean": round(mean(values), 4),
                                             "ci95": [round(low, 4), round(high, 4)]}
                    if task == "fbt":
                        rows[f"{metric}@{k}"]["perOrder"] = round(grouped_mean(values, groups), 4)
                rows[f"coverage@{k}"] = round(coverage_at_k([lists[i] for i in indices], candidates, k), 4)
            rows["shortLists"] = sum(1 for i in indices if len(lists[i]) < MAX_K)
            task_result[segment] = rows
        result["tasks"][task] = task_result
    return result


def ranked(scores: dict[str, float], exclude: set[str], limit: int = MAX_K) -> list[str]:
    """Only scored items (content never pads with unrelated products), best first, ties by id."""
    return sorted((i for i in scores if i not in exclude), key=lambda i: (-scores[i], i))[:limit]


def content_items(snapshot: dict, candidates: list[str]) -> dict:
    """Content features of every catalog product; price bands come from the candidate set (design 5.4)."""
    rows = [{**row, "id": row["productId"]} for row in snapshot["products"]]
    allowed = set(candidates)
    return build_items(rows, price_thresholds(row["minPrice"] for row in rows if row["id"] in allowed))


def content_config(items: dict, mode: str) -> Config:
    def fbt(source: str, candidates: list[str], key: str) -> list[str]:
        return ranked(item_scores([source], items, candidates, mode), {source})
    return Config(f"content-{mode.lower()}", "SIMILAR_CONTENT", {"mode": mode, "features": "attribute Jaccard"},
                  fbt=fbt)


def hybrid_fbt_config(rules: Config, content: Config, popularity: Config) -> Config:
    def fbt(source: str, candidates: list[str], key: str) -> list[str]:
        layers = [rules.fbt(source, candidates, key), content.fbt(source, candidates, key),
                  popularity.fbt(source, candidates, key)]
        return [item for item, _ in layered(layers, MAX_K)]
    return Config(f"hybrid-{rules.name}>{content.name}>{popularity.name}", "HYBRID_LAYERED",
                  {"layers": [rules.name, content.name, popularity.name]}, fbt=fbt)


def profile_configs(data: TrainingData, items: dict, cutoff: datetime, half_life: float | None,
                    popularity: Config) -> list[Config]:
    def profile_ranking(user: str, candidates: list[str]) -> list[str]:
        profile = user_profile(data.events_by_user.get(user, []), items, cutoff, half_life)
        if not profile:
            return []
        return ranked(profile_scores(profile, items, candidates), data.bought_by_user.get(user, set()))

    def hybrid(user: str, candidates: list[str]) -> list[str]:
        return [item for item, _ in layered([profile_ranking(user, candidates), popularity.home(user, candidates)],
                                            MAX_K)]
    params = {"halfLifeDays": half_life, "profile": "normalised weighted feature counts"}
    return [Config("content-profile", "SIMILAR_CONTENT", params, home=profile_ranking),
            Config(f"hybrid-content-profile>{popularity.name}", "HYBRID_LAYERED",
                   {**params, "layers": ["content-profile", popularity.name]}, home=hybrid)]


def score(result: dict) -> float:
    return result["tasks"]["fbt"]["all"]["ndcg@5"]["mean"]


def pick(results: list[dict]) -> dict:
    """Best NDCG@5; `results` is ordered simplest first, so the first maximum wins ties."""
    best = results[0]
    for result in results[1:]:
        if score(result) > score(best):
            best = result
    return best


def phase6(data: TrainingData, cutoff: datetime, split: dict) -> tuple[list[dict], dict]:
    results = [evaluate(random_config(data), split)]

    popularity = {}
    for half_life in HALF_LIVES:
        config = popularity_config(data, cutoff, half_life, EVENT_WEIGHTS, "default")
        popularity[config.name] = config
        results.append(evaluate(config, split))
    best_popularity = pick([r for r in results if r["algorithm"] == "POPULARITY"])
    chosen_half_life = best_popularity["params"]["halfLifeDays"]
    sensitivity = evaluate(popularity_config(data, cutoff, chosen_half_life, ALTERNATIVE_EVENT_WEIGHTS,
                                             "alternative"), split)
    sensitivity["sensitivityOnly"] = True
    results.append(sensitivity)

    association = [evaluate(association_config(data, s, c), split) for s in SUPPORTS for c in CONFIDENCES]
    results.extend(association)
    best_association = pick(association)
    filled = association_config(data, best_association["params"]["minSupport"],
                                best_association["params"]["minConfidence"], popularity[best_popularity["name"]])
    results.append(evaluate(filled, split))

    selection = {"metric": "fbt.all.ndcg@5.mean", "tieBreak": "simpler configuration (listed first)",
                 "popularity": best_popularity["name"], "association": best_association["name"],
                 "popularitySensitivity": {"default": score(best_popularity), "alternative": score(sensitivity)}}
    return results, selection


def phase7(data: TrainingData, cutoff: datetime, split: dict, snapshot: dict, phase6_report: dict
           ) -> tuple[list[dict], dict]:
    chosen = phase6_report["selection"]
    by_name = {r["name"]: r for r in phase6_report["results"]}
    pop_params = by_name[chosen["popularity"]]["params"]
    rule_params = by_name[chosen["association"]]["params"]
    popularity = popularity_config(data, cutoff, pop_params["halfLifeDays"], EVENT_WEIGHTS, "default")
    rules = association_config(data, rule_params["minSupport"], rule_params["minConfidence"])
    items = content_items(snapshot, split["candidates"]["productIds"])

    results = [evaluate(popularity, split),
               evaluate(association_config(data, rule_params["minSupport"], rule_params["minConfidence"],
                                           popularity), split)]
    for result in results:
        result["reference"] = "phase 6 choice"
    contents = {mode: content_config(items, mode) for mode in ("SIMILAR", "COMPLEMENT")}
    content_results = [evaluate(config, split) for config in contents.values()]
    results.extend(content_results)
    best_content = pick(content_results)
    hybrid = evaluate(hybrid_fbt_config(rules, contents[best_content["params"]["mode"]], popularity), split)
    results.append(hybrid)
    results.extend(evaluate(config, split)
                   for config in profile_configs(data, items, cutoff, pop_params["halfLifeDays"], popularity))

    singles = [r for r in results if "fbt" in r["tasks"] and r["algorithm"] in ("POPULARITY", "SIMILAR_CONTENT")]
    singles.append(evaluate(rules, split))
    single_best = max(singles, key=score)
    selection = {"metric": "fbt.all.ndcg@5.mean", "phase6Report": phase6_report["_path"],
                 "popularity": popularity.name, "association": rules.name, "content": best_content["name"],
                 "hybridFbt": hybrid["name"],
                 "rq3HybridAtLeastBestSingle": {"hybrid": score(hybrid), "bestSingle": single_best["name"],
                                                "bestSingleScore": score(single_best),
                                                "holds": score(hybrid) >= score(single_best)}}
    return results, selection


def latest_report(dataset: str, split: str, stage: str) -> Path:
    reports = sorted((REPO_ROOT / "evidence/recommendation").glob(f"{dataset}-{split}-{stage}-*.json"))
    if not reports:
        raise SystemExit(f"no {stage} report for {dataset}/{split}; run that stage first")
    return reports[-1]


def git_commit() -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def print_table(results: list[dict]) -> None:
    header = f"{'config':<52}{'P@5':>7}{'R@5':>7}{'HR@5':>7}{'NDCG@5':>8}{'NDCG@10':>9}{'Cov@5':>7}{'short':>7}"
    print(header)
    for result in results:
        if "fbt" not in result["tasks"]:
            continue
        rows = result["tasks"]["fbt"]["all"]
        print(f"{result['name']:<52}{rows['precision@5']['mean']:>7.3f}{rows['recall@5']['mean']:>7.3f}"
              f"{rows['hit_rate@5']['mean']:>7.3f}{rows['ndcg@5']['mean']:>8.3f}{rows['ndcg@10']['mean']:>9.3f}"
              f"{rows['coverage@5']:>7.3f}{rows['shortLists']:>7}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--split", required=True, choices=("dev", "test"))
    parser.add_argument("--stage", required=True, choices=("phase6", "phase7"))
    parser.add_argument("--final", action="store_true", help="required to evaluate the TEST split")
    args = parser.parse_args()
    if args.split == "test" and not args.final:
        parser.error("TEST is evaluated once with final configurations; pass --final deliberately")

    dataset = args.dataset.resolve()
    split_manifest = json.loads((REPO_ROOT / f"evidence/recommendation/{dataset.name}-splits-manifest.json")
                                .read_text(encoding="utf-8"))
    generator_manifest = json.loads((REPO_ROOT / split_manifest["generatorManifest"]).read_text(encoding="utf-8"))
    split_path = dataset / "splits" / f"{args.split}.json"
    if sha256_file(split_path) != split_manifest["files"][f"splits/{args.split}.json"]:
        parser.error(f"{split_path.name} does not match the split manifest")
    for name, output in generator_manifest["outputs"].items():
        if sha256_file(dataset / name) != output["sha256"]:
            parser.error(f"{name} does not match the generator manifest")

    split = json.loads(split_path.read_text(encoding="utf-8"))
    cutoff = parse(split["window"]["trainUntil"])
    started = time.perf_counter()
    data = training_data(read_jsonl(dataset / "interactions.jsonl"), read_jsonl(dataset / "order_baskets.jsonl"),
                         cutoff)
    if args.stage == "phase6":
        results, selection = phase6(data, cutoff, split)
    else:
        config = json.loads((dataset / "config.json").read_text(encoding="utf-8"))
        snapshot = json.loads((REPO_ROOT / config["catalogSnapshot"]).read_text(encoding="utf-8"))
        report_path = latest_report(dataset.name, args.split, "phase6")
        phase6_report = json.loads(report_path.read_text(encoding="utf-8"))
        phase6_report["_path"] = report_path.relative_to(REPO_ROOT).as_posix()
        results, selection = phase7(data, cutoff, split, snapshot, phase6_report)

    run_at = datetime.now(timezone.utc)
    report = {
        "stage": args.stage, "dataset": dataset.name, "split": args.split, "window": split["window"],
        "runAt": run_at.isoformat(timespec="seconds"), "gitCommit": git_commit(),
        "inputs": {"split": {"path": f"splits/{args.split}.json",
                             "sha256": split_manifest["files"][f"splits/{args.split}.json"]},
                   "candidates": {"count": split["candidates"]["count"], "sha256": split["candidates"]["sha256"]},
                   "outputs": {name: output["sha256"] for name, output in generator_manifest["outputs"].items()}},
        "trainingRows": {"events": len(data.events), "baskets": len(data.baskets)},
        "protocol": {"kValues": list(K_VALUES), "seed": SEED, "bootstrap": {"resamples": 1000, "level": 0.95,
                     "unit": {"fbt": "orderId", "home": "userRef"}}, "dcg": "linear gain, log2(i + 1) discount"},
        "selection": selection,
        "results": results,
        "elapsedSeconds": round(time.perf_counter() - started, 2),
    }
    out = REPO_ROOT / f"evidence/recommendation/{dataset.name}-{args.split}-{args.stage}-{run_at:%Y%m%d}.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print_table(results)
    print(json.dumps(selection, indent=2))
    print(f"-> {out.relative_to(REPO_ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
