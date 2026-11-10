from fastapi import APIRouter, Response, status
from app.schemas.chat import HealthResponse
from app.config.settings import settings
from app.db.pool import write_ready
from app.clients import search_api_client, catalog_api_client
from app.observability.http_middleware import request_count, request_latency_seconds

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(status="ok", service="chatbot-service")


@router.get("/health/live")
async def live() -> dict:
    return {"status": "ok", "service": "chatbot-service"}


@router.get("/health/ready")
async def ready(response: Response) -> dict:
    checks = {
        "database": await write_ready(),
        "searchApi": await search_api_client.ready(),
        "catalogApi": await catalog_api_client.ready(),
    }
    is_ready = all(checks.values())
    if not is_ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"status": "ready" if is_ready else "not_ready", "checks": checks}


@router.get("/metrics")
async def metrics() -> Response:
    lines = [
        "# HELP chatbot_http_requests_total HTTP requests handled.",
        "# TYPE chatbot_http_requests_total counter",
    ]
    for (method, path, code), value in sorted(request_count.items()):
        lines.append(
            f'chatbot_http_requests_total{{method="{method}",path="{path}",status="{code}"}} {value}'
        )
    lines.extend([
        "# HELP chatbot_http_request_duration_seconds_total Cumulative request latency.",
        "# TYPE chatbot_http_request_duration_seconds_total counter",
    ])
    for (method, path), value in sorted(request_latency_seconds.items()):
        lines.append(
            f'chatbot_http_request_duration_seconds_total{{method="{method}",path="{path}"}} {value:.6f}'
        )
    return Response("\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")
