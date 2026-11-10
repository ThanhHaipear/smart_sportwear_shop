from fastapi import APIRouter, Depends, Response, status

from app.config import Settings, get_settings
from app.persistence.repository import SearchRepository

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def live(settings: Settings = Depends(get_settings)) -> dict:
    return {"status": "ok", "service": settings.service_name}


@router.get("/health/ready")
async def ready(response: Response, settings: Settings = Depends(get_settings)) -> dict:
    if not settings.search_enabled:
        return {"status": "disabled", "enabled": False}
    database = await SearchRepository(settings).ping()
    if not database:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"status": "ready" if database else "not_ready", "enabled": True,
            "checks": {"database": database, "catalogApi": "event-time", "rabbitmq": "worker-only"}}


@router.get("/metrics")
async def metrics(settings: Settings = Depends(get_settings)) -> Response:
    body = (
        "# HELP search_service_enabled Whether unified search is enabled.\n"
        "# TYPE search_service_enabled gauge\n"
        f"search_service_enabled {1 if settings.search_enabled else 0}\n"
    )
    return Response(content=body, media_type="text/plain; version=0.0.4")
