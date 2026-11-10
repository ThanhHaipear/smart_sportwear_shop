import logging
import time
import uuid

from fastapi import FastAPI, Request

from app.api import router


def create_app() -> FastAPI:
    application = FastAPI(title="Smart Sportswear Recommendation Service", version="1.0.0")
    application.include_router(router)

    @application.middleware("http")
    async def correlation(request: Request, call_next):
        value = request.headers.get("X-Correlation-ID", "")
        try: correlation_id = str(uuid.UUID(value))
        except ValueError: correlation_id = str(uuid.uuid4())
        started = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Correlation-ID"] = correlation_id
        logging.getLogger("recommendation.access").info(
            "request method=%s path=%s status=%s correlationId=%s durationMs=%s",
            request.method, request.url.path, response.status_code, correlation_id,
            round((time.perf_counter() - started) * 1000))
        return response
    return application


app = create_app()
