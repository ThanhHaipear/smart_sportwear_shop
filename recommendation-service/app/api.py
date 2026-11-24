from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query, Response, status

from app.config import Settings, get_settings
from app.dependencies import enricher, interaction_limiter, internal_auth, repository, service
from app.domain import precision_recall_at_k
from app.interactions import sanitize_browser_batch, sanitize_browser_interaction
from app.metrics import case_metrics, mean
from app.schemas import (CartRecommendationRequest, EvaluationRequest, EvaluationResult,
                         IntegrationEvent, InteractionBatch, InteractionEvent, RecommendationResponse)
from app.service import DEFAULT_MIN_CONFIDENCE, DEFAULT_MIN_SUPPORT

router = APIRouter()


def resolved_limit(value: int | None, settings: Settings) -> int:
    return min(value or settings.recommendation_default_limit, settings.recommendation_max_limit)


@router.get("/api/v1/recommendations/products/{product_id}", response_model=RecommendationResponse, tags=["recommendations"])
@router.get("/api/v1/products/{product_id}/frequently-bought-together", response_model=RecommendationResponse, tags=["compatibility"])
def product_recommendations(product_id: UUID, limit: int | None = Query(default=None, ge=1), engine=Depends(service),
                            settings: Settings = Depends(get_settings),
                            x_user_id: str | None = Header(default=None, alias="X-User-Id")):
    # X-User-Id is set by the Gateway after JWT verification; it is only logged, never echoed back.
    return engine.recommend({product_id}, resolved_limit(limit, settings), "FREQUENTLY_BOUGHT_TOGETHER",
                            user_ref(x_user_id) if settings.interaction_real_enabled else None)


@router.post("/api/v1/recommendations/cart", response_model=RecommendationResponse, tags=["recommendations"])
def cart_recommendations(body: CartRecommendationRequest, engine=Depends(service), settings: Settings = Depends(get_settings),
                         x_user_id: str | None = Header(default=None, alias="X-User-Id")):
    return engine.recommend(set(body.sourceProductIds), resolved_limit(body.limit, settings), "CART_RECOMMENDATION",
                            user_ref(x_user_id) if settings.interaction_real_enabled else None)


def user_ref(x_user_id: str | None) -> str | None:
    value = (x_user_id or "").strip()
    return value[:100] if value else None


@router.post("/api/v1/recommendations/interactions", status_code=202, tags=["interactions"])
def record_interaction(body: InteractionEvent, repo=Depends(repository), limiter=Depends(interaction_limiter),
                       settings: Settings = Depends(get_settings),
                       x_user_id: str | None = Header(default=None, alias="X-User-Id"),
                       x_forwarded_for: str | None = Header(default=None, alias="X-Forwarded-For")):
    """Public browser endpoint. Identity comes only from the Gateway-verified JWT header."""
    event = sanitize_browser_interaction(body, x_user_id, datetime.now(timezone.utc),
                                         accept_real=settings.interaction_real_enabled,
                                         accept_demo=settings.interaction_demo_enabled)
    limiter.check(event, client_ip(x_forwarded_for))
    return {"accepted": repo.save_interaction(event), "eventId": str(event.event_id)}


@router.post("/api/v1/recommendations/interactions/batch", status_code=202, tags=["interactions"])
def record_interaction_batch(body: InteractionBatch, repo=Depends(repository), limiter=Depends(interaction_limiter),
                             settings: Settings = Depends(get_settings),
                             x_user_id: str | None = Header(default=None, alias="X-User-Id"),
                             x_forwarded_for: str | None = Header(default=None, alias="X-Forwarded-For")):
    """Up to 10 events from one browser in a single request and DB transaction.

    Same rules as the single endpoint; the whole batch is rejected if any event is invalid,
    and the rate limit is charged per event.
    """
    events = sanitize_browser_batch(body.events, x_user_id, datetime.now(timezone.utc),
                                    accept_real=settings.interaction_real_enabled,
                                    accept_demo=settings.interaction_demo_enabled)
    limiter.check(events, client_ip(x_forwarded_for))
    accepted = repo.save_interactions(events)
    return {"accepted": accepted, "duplicates": len(events) - accepted,
            "eventIds": [str(event.event_id) for event in events]}


def client_ip(x_forwarded_for: str | None) -> str | None:
    # The Gateway overwrites X-Forwarded-For with the real peer address.
    return (x_forwarded_for or "").split(",")[0].strip() or None


@router.post("/internal/v1/events", dependencies=[Depends(internal_auth)], tags=["internal"])
def consume_event(body: IntegrationEvent, repo=Depends(repository), events=Depends(enricher)):
    return {"processed": repo.apply_event(events.enrich(body)), "eventId": str(body.event_id)}


@router.post("/internal/v1/models/rebuild", dependencies=[Depends(internal_auth)], tags=["internal"])
def rebuild_model(modelVersion: str = Query(min_length=1, max_length=50),
                  minSupport: float = Query(DEFAULT_MIN_SUPPORT, ge=0, le=1),
                  minConfidence: float = Query(DEFAULT_MIN_CONFIDENCE, ge=0, le=1), minLift: float = Query(1, ge=0),
                  engine=Depends(service)):
    return {"modelVersion": modelVersion, "ruleCount": engine.rebuild(modelVersion, minSupport, minConfidence, minLift)}


@router.post("/internal/v1/evaluations", response_model=EvaluationResult, dependencies=[Depends(internal_auth)], tags=["internal"])
def evaluate(body: EvaluationRequest, repo=Depends(repository)):
    cases = [(list(case["predicted"]), set(case["relevant"])) for case in body.cases]
    precision, recall = precision_recall_at_k(cases, body.k)
    per_case = [case_metrics(predicted, dict.fromkeys(relevant, 1), body.k) for predicted, relevant in cases]
    result = EvaluationResult(modelVersion=body.modelVersion, datasetVersion=body.datasetVersion,
                              k=body.k, precisionAtK=precision, recallAtK=recall,
                              hitRateAtK=mean([row["hit_rate"] for row in per_case]),
                              ndcgAtK=mean([row["ndcg"] for row in per_case]), caseCount=len(cases))
    repo.save_evaluation(result)
    return result


@router.get("/health/live", tags=["health"])
def live(settings: Settings = Depends(get_settings)):
    return {"status": "ok", "service": settings.service_name}


@router.get("/health/ready", tags=["health"])
def ready(response: Response, settings: Settings = Depends(get_settings)):
    if not settings.recommendation_enabled:
        return {"status": "disabled", "enabled": False}
    from app.repository import PostgresRepository
    database = PostgresRepository(settings.recommendation_database_url).ping()
    if not database: response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"status": "ready" if database else "not_ready", "enabled": True,
            "checks": {"database": database, "rabbitmq": "worker-only"}}


@router.get("/metrics", tags=["health"])
def metrics(settings: Settings = Depends(get_settings)):
    return Response("# HELP recommendation_service_enabled Whether recommendation is enabled.\n"
                    "# TYPE recommendation_service_enabled gauge\n"
                    f"recommendation_service_enabled {1 if settings.recommendation_enabled else 0}\n",
                    media_type="text/plain; version=0.0.4")
