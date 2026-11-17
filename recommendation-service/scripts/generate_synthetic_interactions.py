"""Generate synthetic shopping behaviour for offline recommendation evaluation.

Reads a frozen catalog snapshot and a parameter file, and writes:
- `interactions.jsonl`: VIEW / WISHLIST / ADD_TO_CART rows shaped like `recommendation.user_interactions`;
- `order_baskets.jsonl`: orders shaped like `recommendation.order_baskets` (PURCHASE comes from here,
  exactly as in production where purchases arrive through `order.created`);
- a manifest with checksums and summary statistics.

Nothing is written to a database. The same config and snapshot always produce byte-identical files.

Behaviour model (docs/recommendation-design.md, section 7.1). Each session mixes four mechanisms so
that no algorithm wins by construction:
- persona preference: products are sampled by affinity to the user's sport, gender, budget and brand;
- complementary purchase: after adding type X to the cart the user may add a complementary type Y of
  the same sport (shoes -> accessory, top -> bottom, ...);
- popularity: every product has a latent popularity that multiplies everyone's affinity;
- noise: a share of picks is uniform over the catalog.
Products listed in `newArrivals.slugs` are released on `newArrivals.launchDay` (start of the test week), so
they are new (cold) items at test time.

Usage:
    python scripts/generate_synthetic_interactions.py --config ../datasets/recommendation/synthetic-v1/config.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import subprocess
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID

REPO_ROOT = Path(__file__).resolve().parents[2]
DAY = 86400


@dataclass(frozen=True)
class Product:
    id: str
    slug: str
    product_type: str
    gender: str
    sports: frozenset[str]
    brand: str
    price: float
    launch_day: float = 0.0


@dataclass
class Persona:
    sport: str
    gender: str
    budget: str
    max_price: float | None
    brand: str | None
    active_from_day: float


@dataclass
class Generated:
    interactions: list[dict] = field(default_factory=list)
    baskets: list[dict] = field(default_factory=list)
    personas: Counter = field(default_factory=Counter)
    picks: Counter = field(default_factory=Counter)


def poisson(rng: random.Random, mean: float) -> int:
    """Knuth's method; enough for the small means used here and needs no numpy."""
    limit, k, p = math.exp(-mean), 0, 1.0
    while True:
        p *= rng.random()
        if p <= limit:
            return k
        k += 1


def weighted_key(rng: random.Random, weights: dict[str, float]) -> str:
    keys = sorted(weights)
    return rng.choices(keys, weights=[weights[k] for k in keys])[0]


def seeded_uuid(rng: random.Random) -> str:
    return str(UUID(int=rng.getrandbits(128), version=4))


def load_products(snapshot: dict, config: dict) -> list[Product]:
    launch = config["newArrivals"]
    new_slugs = set(launch["slugs"])
    products = []
    for row in snapshot["products"]:
        if row["status"] != "ACTIVE" or row["minPrice"] is None:
            continue
        if not (row["productType"] and row["gender"] and row["sportCodes"]):
            raise ValueError(f"product {row['slug']} lacks productType/gender/sportCodes")
        products.append(Product(
            id=row["productId"], slug=row["slug"], product_type=row["productType"], gender=row["gender"],
            sports=frozenset(row["sportCodes"]), brand=row["brand"] or "", price=float(row["minPrice"]),
            launch_day=float(launch["launchDay"]) if row["slug"] in new_slugs else 0.0))
    unknown = new_slugs - {p.slug for p in products}
    if unknown:
        raise ValueError(f"newArrivals slugs not in catalog: {sorted(unknown)}")
    return sorted(products, key=lambda p: p.id)


def gender_fits(product: Product, gender: str) -> bool:
    return product.gender in ("UNISEX", gender)


def affinity(product: Product, persona: Persona, popularity: float, config: dict) -> float:
    weights = config["affinity"]
    score = popularity
    if persona.sport not in product.sports:
        score *= weights["sportMismatch"]
    if not gender_fits(product, persona.gender):
        score *= weights["genderMismatch"]
    if persona.max_price is not None and product.price > persona.max_price:
        score *= weights["overBudget"]
    if persona.brand and product.brand == persona.brand:
        score *= config["personas"]["brandLoyalBoost"]
    return score


def make_persona(rng: random.Random, config: dict, total_days: float, brands: list[str]) -> Persona:
    personas, users = config["personas"], config["users"]
    budget = weighted_key(rng, {k: v["share"] for k, v in personas["budget"].items()})
    brand = rng.choice(brands) if rng.random() < personas["brandLoyalShare"] else None
    newcomer = rng.random() < users["newcomerShare"]
    return Persona(
        sport=weighted_key(rng, personas["sport"]), gender=weighted_key(rng, personas["gender"]),
        budget=budget, max_price=personas["budget"][budget]["maxPrice"], brand=brand,
        # Newcomers join during the last two weeks, so some users have no history at test time.
        active_from_day=rng.uniform(total_days - 14, total_days) if newcomer else 0.0)


def generate(config: dict, products: list[Product]) -> Generated:
    rng = random.Random(config["seed"])
    session_cfg = config["session"]
    total_days = 7.0 * config["weeks"]
    t0 = datetime.fromisoformat(config["t0"].replace("Z", "+00:00"))
    popularity = {p.id: rng.lognormvariate(0.0, config["popularity"]["logNormalSigma"]) for p in products}
    brands = sorted({p.brand for p in products if p.brand})
    out = Generated()

    for index in range(1, config["users"]["count"] + 1):
        user_ref = f"syn-u{index:04d}"
        persona = make_persona(rng, config, total_days, brands)
        out.personas[f"{persona.sport}/{persona.gender}/{persona.budget}"] += 1
        # Same session rate per day for every user: a newcomer active for 10 of 56 days gets 10/56 of the sessions.
        active_share = (total_days - persona.active_from_day) / total_days
        sessions = max(config["users"]["minSessions"],
                       poisson(rng, config["users"]["sessionsPoissonMean"] * active_share))
        starts = sorted(rng.uniform(persona.active_from_day, total_days) for _ in range(sessions))
        for start_day in starts:
            simulate_session(rng, out, user_ref, persona, start_day, t0, products, popularity, config,
                             session_cfg)

    out.interactions.sort(key=lambda row: (row["occurredAt"], row["eventId"]))
    out.baskets.sort(key=lambda row: (row["occurredAt"], row["orderId"]))
    return out


def simulate_session(rng, out: Generated, user_ref: str, persona: Persona, start_day: float, t0: datetime,
                     products: list[Product], popularity: dict, config: dict, session_cfg: dict) -> None:
    available = [p for p in products if p.launch_day <= start_day]
    session_id = seeded_uuid(rng)
    clock = [t0 + timedelta(seconds=round(start_day * DAY))]
    gap = session_cfg["secondsBetweenEvents"]

    def emit(event_type: str, product: Product) -> None:
        out.interactions.append({
            "eventId": seeded_uuid(rng), "occurredAt": clock[0].strftime("%Y-%m-%dT%H:%M:%SZ"),
            "userRef": user_ref, "anonymousId": None, "eventType": event_type, "productId": product.id,
            "variantId": None, "requestId": None, "source": "SYNTHETIC_GENERATOR", "dataSource": "SYNTHETIC",
            "metadata": {"sessionId": session_id}})
        clock[0] += timedelta(seconds=rng.randint(gap["min"], gap["max"]))

    def pick(exclude: set[str]) -> tuple[Product, bool] | None:
        pool = [p for p in available if p.id not in exclude]
        if not pool:
            return None
        if rng.random() < session_cfg["noiseRate"]:
            return rng.choice(pool), True
        weights = [affinity(p, persona, popularity[p.id], config) for p in pool]
        return rng.choices(pool, weights=weights)[0], False

    seen: set[str] = set()
    cart: list[Product] = []
    for _ in range(1 + poisson(rng, session_cfg["extraViewsPoissonMean"])):
        chosen = pick(seen)
        if chosen is None:
            break
        product, noisy = chosen
        seen.add(product.id)
        out.picks["noise" if noisy else "persona"] += 1
        emit("VIEW", product)
        if rng.random() < session_cfg["wishlistProb"]:
            emit("WISHLIST", product)
        if rng.random() < (session_cfg["noiseAddToCartProb"] if noisy else session_cfg["addToCartProb"]):
            emit("ADD_TO_CART", product)
            cart.append(product)
            if not noisy and rng.random() < session_cfg["complementProb"]:
                complement = pick_complement(rng, product, persona, available, seen, popularity, config)
                if complement is not None:
                    seen.add(complement.id)
                    out.picks["complement"] += 1
                    emit("VIEW", complement)
                    if rng.random() < session_cfg["complementAddToCartProb"]:
                        emit("ADD_TO_CART", complement)
                        cart.append(complement)

    if cart and rng.random() < session_cfg["purchaseProb"]:
        out.baskets.append({
            "orderId": seeded_uuid(rng), "userRef": user_ref,
            "productIds": sorted({p.id for p in cart}), "occurredAt": clock[0].strftime("%Y-%m-%dT%H:%M:%SZ")})


def pick_complement(rng, product: Product, persona: Persona, available: list[Product], seen: set[str],
                    popularity: dict, config: dict) -> Product | None:
    types = config["complements"].get(product.product_type) or []
    if not types:
        return None
    target = rng.choice(types)
    pool = [p for p in available if p.product_type == target and p.id not in seen
            and p.sports & product.sports and gender_fits(p, persona.gender)]
    if not pool:
        return None
    return rng.choices(pool, weights=[popularity[p.id] for p in pool])[0]


def jsonl_bytes(rows: list[dict]) -> bytes:
    return "".join(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
                   for row in rows).encode("utf-8")


def summarize(out: Generated, config: dict) -> dict:
    t0 = datetime.fromisoformat(config["t0"].replace("Z", "+00:00"))

    def week(timestamp: str) -> int:
        moment = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        return int((moment - t0).total_seconds() // (7 * DAY)) + 1

    events_by_week: dict[int, Counter] = {}
    for row in out.interactions:
        events_by_week.setdefault(week(row["occurredAt"]), Counter())[row["eventType"]] += 1
    orders_by_week = Counter(week(b["occurredAt"]) for b in out.baskets)
    multi_by_week = Counter(week(b["occurredAt"]) for b in out.baskets if len(b["productIds"]) >= 2)
    sizes = Counter(len(b["productIds"]) for b in out.baskets)
    picks = sum(out.picks.values())
    return {
        "users": config["users"]["count"],
        "activeUsers": len({row["userRef"] for row in out.interactions}),
        "eventsByType": dict(sorted(Counter(r["eventType"] for r in out.interactions).items())),
        "orders": len(out.baskets),
        "basketSizeDistribution": {str(k): sizes[k] for k in sorted(sizes)},
        "byWeek": [{"week": w, "events": dict(sorted(events_by_week.get(w, Counter()).items())),
                    "orders": orders_by_week[w], "multiItemOrders": multi_by_week[w]}
                   for w in range(1, config["weeks"] + 1)],
        "pickShares": {k: round(v / picks, 4) for k, v in sorted(out.picks.items())},
        "personas": dict(sorted(out.personas.items())),
    }


def display_path(path: Path) -> str:
    """Repo-relative path when possible, so manifests do not record a local directory layout."""
    resolved = path.resolve()
    return resolved.relative_to(REPO_ROOT).as_posix() if resolved.is_relative_to(REPO_ROOT) else resolved.as_posix()


def git_commit() -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=REPO_ROOT / "evidence/recommendation/synthetic-v1-manifest.json")
    args = parser.parse_args()

    config_bytes = args.config.read_bytes()
    config = json.loads(config_bytes)
    snapshot = json.loads((REPO_ROOT / config["catalogSnapshot"]).read_text(encoding="utf-8"))
    if snapshot["productsSha256"] != config["catalogProductsSha256"]:
        parser.error("catalog snapshot checksum does not match config.catalogProductsSha256")

    out = generate(config, load_products(snapshot, config))
    files = {"interactions.jsonl": jsonl_bytes(out.interactions), "order_baskets.jsonl": jsonl_bytes(out.baskets)}
    outputs = {}
    for name, data in files.items():
        (args.config.parent / name).write_bytes(data)
        outputs[name] = {"sha256": hashlib.sha256(data).hexdigest(), "rows": data.count(b"\n")}

    manifest = {
        "dataset": config["version"],
        "dataSource": "SYNTHETIC",
        "generator": "recommendation-service/scripts/generate_synthetic_interactions.py",
        "gitCommit": git_commit(),
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "seed": config["seed"],
        "config": {"path": display_path(args.config),
                   "sha256": hashlib.sha256(config_bytes).hexdigest()},
        "catalog": {"path": config["catalogSnapshot"], "productsSha256": snapshot["productsSha256"],
                    "products": snapshot["productCount"]},
        "window": {"t0": config["t0"], "weeks": config["weeks"]},
        "outputs": outputs,
        "summary": summarize(out, config),
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"outputs": outputs, "summary": manifest["summary"]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
