"""Content-based recommendation from product attributes (docs/recommendation-design.md, section 5.4).

Option B of the design: no embeddings. A product is a set of prefixed features per group
(sport, gender, type, category, brand, price band, colour); similarity is a weighted mean of per-group
Jaccard indexes. Pure functions, shared by the offline evaluator and the serving path.

Two item-item variants:
- SIMILAR: all groups; recommends look-alike products.
- COMPLEMENT: drops the type and category groups and only keeps candidates of a different product type,
  so a running shoe leads to running socks or shorts rather than another running shoe.
"""
from __future__ import annotations

import math
from collections.abc import Hashable, Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime

from app.domain import EVENT_WEIGHTS, WeightedEvent, drop_views_after_clicks

GROUP_WEIGHTS: dict[str, float] = {"sport": 3.0, "gender": 2.0, "type": 1.0, "category": 1.0, "brand": 1.0,
                                   "price": 1.0, "color": 0.5}
COMPLEMENT_GROUP_WEIGHTS: dict[str, float] = {g: w for g, w in GROUP_WEIGHTS.items() if g not in ("type", "category")}
MODES = ("SIMILAR", "COMPLEMENT")


@dataclass(frozen=True)
class ContentItem:
    id: Hashable
    product_type: str | None
    features: Mapping[str, frozenset[str]]

    def all_features(self) -> set[tuple[str, str]]:
        return {(group, value) for group, values in self.features.items() for value in values}


def price_thresholds(prices: Iterable[float]) -> list[float]:
    """Quartile cut points of the candidate prices (nearest-rank), giving four price bands."""
    values = sorted(price for price in prices if price is not None)
    if not values:
        return []
    return [values[min(len(values) - 1, math.ceil(q * len(values)) - 1)] for q in (0.25, 0.5, 0.75)]


def price_band(price: float | None, thresholds: list[float]) -> str | None:
    if price is None or not thresholds:
        return None
    return f"Q{1 + sum(1 for cut in thresholds if price > cut)}"


def build_items(rows: Iterable[Mapping], thresholds: list[float] | None = None) -> dict[Hashable, ContentItem]:
    """Rows need `id`, `productType`, `category`, `brand`, `gender`, `sportCodes`, `colorFamilies`, `minPrice`.

    Missing values give an empty group, which never counts as a match.
    """
    rows = list(rows)
    if thresholds is None:
        thresholds = price_thresholds(row.get("minPrice") for row in rows)
    items = {}
    for row in rows:
        def one(value):
            return frozenset({str(value)}) if value not in (None, "") else frozenset()
        band = price_band(row.get("minPrice"), thresholds)
        items[row["id"]] = ContentItem(row["id"], row.get("productType"), {
            "sport": frozenset(str(v) for v in row.get("sportCodes") or []),
            "gender": one(row.get("gender")),
            "type": one(row.get("productType")),
            "category": one(row.get("category")),
            "brand": one(row.get("brand")),
            "price": one(band),
            "color": frozenset(str(v) for v in row.get("colorFamilies") or []),
        })
    return items


def jaccard(left: frozenset, right: frozenset) -> float:
    union = left | right
    return len(left & right) / len(union) if union else 0.0


def similarity(left: ContentItem, right: ContentItem, weights: Mapping[str, float] = GROUP_WEIGHTS) -> float:
    total = sum(weights.values())
    return sum(w * jaccard(left.features[g], right.features[g]) for g, w in weights.items()) / total


def item_scores(sources: Iterable[Hashable], items: Mapping[Hashable, ContentItem], candidates: Iterable[Hashable],
                mode: str = "SIMILAR") -> dict[Hashable, float]:
    """Score each candidate by its best similarity to any source (a cart has several sources)."""
    if mode not in MODES:
        raise ValueError(f"unknown content mode {mode}")
    source_items = [items[s] for s in sources if s in items]
    source_ids = {item.id for item in source_items}
    weights = GROUP_WEIGHTS if mode == "SIMILAR" else COMPLEMENT_GROUP_WEIGHTS
    scores = {}
    for candidate in candidates:
        item = items.get(candidate)
        if item is None or candidate in source_ids:
            continue
        usable = [s for s in source_items
                  if mode == "SIMILAR" or s.product_type is None or item.product_type != s.product_type]
        best = max((similarity(s, item, weights) for s in usable), default=0.0)
        if best > 0:  # zero similarity is no evidence; leave the slot to the next layer
            scores[candidate] = best
    return scores


def user_profile(events: Iterable[WeightedEvent], items: Mapping[Hashable, ContentItem], cutoff: datetime,
                 half_life_days: float | None = None,
                 weights: Mapping[str, float] = EVENT_WEIGHTS) -> dict[tuple[str, str], float]:
    """p_u(f) = sum of w(e) * decay(e) over the user's events whose product has feature f, normalised to sum 1."""
    decay = math.log(2) / half_life_days if half_life_days else 0.0
    profile: dict[tuple[str, str], float] = {}
    for event in drop_views_after_clicks([e for e in events if e.occurred_at < cutoff]):
        weight = weights.get(event.event_type, 0.0)
        item = items.get(event.product_id)
        if weight == 0 or item is None:
            continue
        age_days = (cutoff - event.occurred_at).total_seconds() / 86400
        for feature in item.all_features():
            profile[feature] = profile.get(feature, 0.0) + weight * math.exp(-decay * age_days)
    total = sum(profile.values())
    return {feature: value / total for feature, value in profile.items()} if total else {}


def profile_scores(profile: Mapping[tuple[str, str], float], items: Mapping[Hashable, ContentItem],
                   candidates: Iterable[Hashable]) -> dict[Hashable, float]:
    """score(j) = sum of p_u(f) over the features of j, divided by the number of features of j."""
    scores = {}
    for candidate in candidates:
        item = items.get(candidate)
        if item is None:
            continue
        features = item.all_features()
        value = sum(profile.get(f, 0.0) for f in features) / len(features) if features else 0.0
        if value > 0:
            scores[candidate] = value
    return scores


def layered(layers: Iterable[list[Hashable]], limit: int) -> list[tuple[Hashable, int]]:
    """Merge ranked lists: each later layer only fills positions left empty by the earlier ones.

    Returns (item, layer index) so callers can attach a reason code per item.
    """
    merged: list[tuple[Hashable, int]] = []
    seen: set = set()
    for index, ranking in enumerate(layers):
        for item in ranking:
            if len(merged) >= limit:
                return merged
            if item not in seen:
                seen.add(item)
                merged.append((item, index))
    return merged
