from fastapi import APIRouter, Depends, Response, status

from app.config import Settings, get_settings
from app.services.readiness import ReadinessChecker

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def live(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    return {"status": "ok", "service": settings.service_name}


@router.get("/health/ready")
async def ready(
    response: Response,
    settings: Settings = Depends(get_settings),
) -> dict[str, object]:
    if not settings.visual_search_enabled:
        return {"status": "disabled", "enabled": False, "provider": settings.visual_embedding_provider}

    result = await ReadinessChecker(settings).check()
    if not result.ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {
        "status": "ready" if result.ready else "not_ready",
        "enabled": True,
        "provider": settings.visual_embedding_provider,
        "checks": {
            "database": result.database,
            "activeModel": result.active_model,
            "coverage": result.coverage,
            "rabbitmq": result.rabbitmq,
        },
    }
