from __future__ import annotations

import contextvars
import re
import time
from collections import Counter
from uuid import uuid4

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

correlation_id_var: contextvars.ContextVar[str] = contextvars.ContextVar(
    "correlation_id", default=""
)

_SAFE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
request_count: Counter[tuple[str, str, int]] = Counter()
request_latency_seconds: Counter[tuple[str, str]] = Counter()


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        supplied = request.headers.get("X-Correlation-ID", "")
        correlation_id = supplied if _SAFE_ID.fullmatch(supplied) else str(uuid4())
        token = correlation_id_var.set(correlation_id)
        started = time.perf_counter()
        try:
            response = await call_next(request)
            request_count[(request.method, request.url.path, response.status_code)] += 1
            request_latency_seconds[(request.method, request.url.path)] += time.perf_counter() - started
            response.headers["X-Correlation-ID"] = correlation_id
            return response
        finally:
            correlation_id_var.reset(token)


def current_correlation_id() -> str:
    return correlation_id_var.get()
