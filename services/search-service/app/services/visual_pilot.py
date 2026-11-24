"""The frozen TEST v4 ranking profile for the opt-in catalog pilot."""

from importlib.metadata import version
import json
import math
from pathlib import Path

from app.config import Settings
from app.services.visual_ranking import rerank_visual_candidates


PILOT = json.loads(Path(__file__).with_name("visual-pilot-v4.json").read_text(encoding="utf-8"))
PROFILE = PILOT["profile"]
PROMPTS = {key: tuple(value) for key, value in PILOT["prompts"].items()}
ADDITIONAL_UNIFORMS = ("uniforms:complete-kit", "uniforms:team")
ADDITIONAL_UNKNOWN = ("unknown:scene", "unknown:built-environment", "unknown:household")
ACCESSORIES = tuple(key for key in PROMPTS if key.startswith("accessory-"))


def validate_runtime(settings: Settings) -> None:
    model = PILOT["model"]
    if (
        (settings.visual_embedding_provider, settings.visual_embedding_device) != ("hugging_face_local", "cpu")
        or settings.visual_embedding_model != model["name"]
        or settings.visual_embedding_revision != model["revision"]
        or settings.visual_embedding_preprocessing != model["preprocessing"]
        or settings.visual_embedding_dims != model["dimensions"]
        or (settings.target_image_max_width, settings.target_image_max_height) != (1024, 1024)
        or any(version(package) != model[package] for package in ("torch", "transformers"))
    ):
        raise ValueError("Visual pilot v4 requires the evaluated SigLIP2 CPU model and preprocessing")


def pilot_image_rows(base: list[dict]) -> list[dict]:
    rows = [row for row in base if row["model"] == PILOT["model"]["name"] and row["image_url"]]
    live_products = {(row["product_id"], row["product_type"]) for row in rows}
    return rows + [
        row for row in PILOT["referenceVectors"]
        if (row["product_id"], row["product_type"]) in live_products
    ]


def rank_pilot(rows: list[dict], vector: tuple[float, ...], scores: dict[str, float], limit: int) -> list[dict]:
    """Rank with the TEST v4 parameters and use a catalog image for reference-only matches."""
    support = {
        key: score for key, score in scores.items()
        if key not in (*ADDITIONAL_UNIFORMS, *ADDITIONAL_UNKNOWN, *ACCESSORIES, "isolated-top", "isolated-bottom")
    }
    complete_kit = max(scores[key] for key in ADDITIONAL_UNIFORMS)
    isolated = max(scores["isolated-top"], scores["isolated-bottom"])
    isolation_margin = max(0.0, 1.0 - complete_kit / isolated)
    support["uniforms"] = blend(support["uniforms"], complete_kit, PROFILE["uniformBlend"])
    support["uniforms"] *= 1.0 - PROFILE["isolationWeight"] * isolation_margin
    support["accessories"] = blend(
        support["accessories"], max(scores[key] for key in ACCESSORIES), PROFILE["accessoryBlend"],
    )
    unknown = [key for key in support if key.startswith("unknown:")]
    strongest_unknown = max(unknown, key=support.__getitem__)
    support[strongest_unknown] = blend(
        support[strongest_unknown], max(scores[key] for key in ADDITIONAL_UNKNOWN), PROFILE["unknownBlend"],
    )
    known: dict[str, float] = {}
    for key, value in support.items():
        group = key.partition(":")[0]
        if group != "unknown":
            known[group] = max(known.get(group, 0.0), value)
    if max(support[key] for key in unknown) >= PROFILE["unknownRatio"] * max(known.values()):
        return []

    norm = math.hypot(*vector)
    best: dict[str, dict] = {}
    display_images: dict[str, dict] = {}
    for row in rows:
        image_vector = json.loads(row["vector"])
        similarity = math.fsum(a * b for a, b in zip(vector, image_vector, strict=True)) / (norm * math.hypot(*image_vector))
        candidate = {**row, "similarity": similarity}
        product_id = row["product_id"]
        if product_id not in best or order(candidate) < order(best[product_id]):
            best[product_id] = candidate
        if row.get("image_url") and (product_id not in display_images or order(candidate) < order(display_images[product_id])):
            display_images[product_id] = candidate

    ranked = rerank_visual_candidates(list(best.values()), category_weight=PROFILE["categoryWeight"],
                                      query_category_scores=known)
    for row in ranked:
        subtype = PILOT["topSubtypes"].get(row["product_id"])
        if row["category_group"] == "tops" and subtype:
            row["similarity"] += PROFILE["subtypeWeight"] * (support[subtype] / known["tops"] - 1.0)
    ranked.sort(key=lambda row: (-row["similarity"], row["product_id"], row["image_id"]))
    results = []
    for row in ranked[:min(limit, PILOT["candidateLimit"])]:
        if row["similarity"] < PROFILE["minSimilarity"]:
            break
        if not row.get("image_url"):
            display = display_images[row["product_id"]]
            row.update(image_id=display["image_id"], image_url=display["image_url"])
        results.append(row)
    return results


def blend(prior: float, added: float, weight: float) -> float:
    return prior + weight * (max(prior, added) - prior)


def order(row: dict) -> tuple[float, str]:
    return -row["similarity"], row["image_id"]
