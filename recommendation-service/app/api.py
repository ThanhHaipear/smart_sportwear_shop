from uuid import UUID

from fastapi import APIRouter, Depends, Query, Response, status

from app.config import Settings, get_settings
from app.dependencies import internal_auth, repository, service
from app.domain import precision_recall_at_k
from app.schemas import (CartRecommendationRequest, EvaluationRequest, EvaluationResult,
                         IntegrationEvent, InteractionEvent, RecommendationResponse)

router = APIRouter()


def resolved_limit(value: int | None, settings: Settings) -> int:
    return min(value or settings.recommendation_default_limit, settings.recommendation_max_limit)


@router.get("/api/v1/recommendations/products/{product_id}", response_model=RecommendationResponse, tags=["recommendations"])
@router.get("/api/v1/products/{product_id}/frequently-bought-together", response_model=RecommendationResponse, tags=["compatibility"])
def product_recommendations(product_id: UUID, limit: int | None = Query(default=None, ge=1), engine=Depends(service), settings: Settings = Depends(get_settings)):
    return engine.recommend({product_id}, resolved_limit(limit, settings), "FREQUENTLY_BOUGHT_TOGETHER")


@router.post("/api/v1/recommendations/cart", response_model=RecommendationResponse, tags=["recommendations"])
def cart_recommendations(body: CartRecommendationRequest, engine=Depends(service), settings: Settings = Depends(get_settings)):
    return engine.recommend(set(body.sourceProductIds), resolved_limit(body.limit, settings), "CART_RECOMMENDATION")


@router.post("/api/v1/recommendations/interactions", status_code=202, tags=["interactions"])
def record_interaction(body: InteractionEvent, repo=Depends(repository)):
    return {"accepted": repo.save_interaction(body), "eventId": str(body.event_id)}


@router.post("/internal/v1/events", dependencies=[Depends(internal_auth)], tags=["internal"])
def consume_event(body: IntegrationEvent, repo=Depends(repository)):
    return {"processed": repo.apply_event(body), "eventId": str(body.event_id)}


@router.post("/internal/v1/models/rebuild", dependencies=[Depends(internal_auth)], tags=["internal"])
def rebuild_model(modelVersion: str, minSupport: float = Query(.01, ge=0, le=1),
                  minConfidence: float = Query(.1, ge=0, le=1), minLift: float = Query(1, ge=0),
                  engine=Depends(service)):
    return {"modelVersion": modelVersion, "ruleCount": engine.rebuild(modelVersion, minSupport, minConfidence, minLift)}


@router.post("/internal/v1/evaluations", response_model=EvaluationResult, dependencies=[Depends(internal_auth)], tags=["internal"])
def evaluate(body: EvaluationRequest, repo=Depends(repository)):
    cases = [(list(case["predicted"]), set(case["relevant"])) for case in body.cases]
    precision, recall = precision_recall_at_k(cases, body.k)
    result = EvaluationResult(modelVersion=body.modelVersion, datasetVersion=body.datasetVersion,
                              k=body.k, precisionAtK=precision, recallAtK=recall)
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
