from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import re
import secrets
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Iterable

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    identity_upstream: str = "http://identity-service:8093"
    catalog_upstream: str = "http://catalog-service:8092"
    sales_upstream: str = "http://sales-service:8094"
    chatbot_upstream: str = "http://chatbot-service:8002"
    recommendation_upstream: str = "http://recommendation-service:8091"
    legacy_upstream: str = "http://host.docker.internal:8080"
    chatbot_legacy_upstream: str = "http://host.docker.internal:8002"
    gateway_identity_cutover_enabled: bool = False
    gateway_catalog_cutover_enabled: bool = False
    gateway_sales_cutover_enabled: bool = False
    gateway_chatbot_cutover_enabled: bool = False
    gateway_recommendation_cutover_enabled: bool = False
    gateway_request_timeout_seconds: float = 10.0
    gateway_rate_limit_per_minute: int = 120
    gateway_rate_limit_burst: int = 30
    jwt_access_secret: str = "dev-only-access-secret-change-me-please-32-bytes-min"
    jwt_previous_access_secret: str = ""
    otel_exporter_otlp_endpoint: str = ""


@dataclass(frozen=True)
class Route:
    methods: frozenset[str]
    pattern: re.Pattern[str]
    upstream_setting: str
    cutover_setting: str | None = None


ROUTES = (
    Route(frozenset({"POST"}), re.compile(r"^/api/v1/auth/(register|login|refresh|logout|google|forgot-password|reset-password)$"), "identity_upstream", "gateway_identity_cutover_enabled"),
    Route(frozenset({"GET", "PATCH"}), re.compile(r"^/api/v1/(?:users/)?me$"), "identity_upstream", "gateway_identity_cutover_enabled"),
    Route(frozenset({"GET", "POST"}), re.compile(r"^/api/v1/(?:users/)?me/addresses$"), "identity_upstream", "gateway_identity_cutover_enabled"),
    Route(frozenset({"PATCH", "DELETE"}), re.compile(r"^/api/v1/(?:users/)?me/addresses/[0-9a-fA-F-]+(?:/default)?$"), "identity_upstream", "gateway_identity_cutover_enabled"),
    Route(frozenset({"PATCH"}), re.compile(r"^/api/v1/admin/users/[0-9a-fA-F-]+/(role|status)$"), "identity_upstream", "gateway_identity_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/admin/users(?:/[0-9a-fA-F-]+)?$"), "identity_upstream", "gateway_identity_cutover_enabled"),
    Route(frozenset({"GET", "POST", "PATCH", "DELETE"}), re.compile(r"^/api/v1/admin/(categories|brands|products|variants|inventory)(?:/.*)?$"), "catalog_upstream", "gateway_catalog_cutover_enabled"),
    Route(frozenset({"GET", "POST", "PUT", "PATCH", "DELETE"}), re.compile(r"^/api/v1/admin/(orders|returns|refunds|reports|notifications|notification-templates|settings|audit-logs)(?:/.*)?$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"POST"}), re.compile(r"^/api/v1/orders$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"GET", "POST", "PUT"}), re.compile(r"^/api/v1/orders(?:/.*)?$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"GET", "POST"}), re.compile(r"^/api/v1/payments(?:/.*)?$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"GET", "POST"}), re.compile(r"^/api/v1/notifications(?:/.*)?$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"GET", "POST", "PATCH", "DELETE"}), re.compile(r"^/api/v1/cart(?:/.*)?$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"POST"}), re.compile(r"^/api/v1/checkout/preview$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/settings/public$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"POST"}), re.compile(r"^/api/v1/webhooks/sepay$"), "sales_upstream", "gateway_sales_cutover_enabled"),
    Route(frozenset({"POST"}), re.compile(r"^/chat(?:/stream)?$"), "chatbot_upstream", "gateway_chatbot_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/recommendations/products/[0-9a-fA-F-]+$"), "recommendation_upstream", "gateway_recommendation_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/products/[0-9a-fA-F-]+/frequently-bought-together$"), "recommendation_upstream", "gateway_recommendation_cutover_enabled"),
    Route(frozenset({"POST"}), re.compile(r"^/api/v1/recommendations/(cart|interactions)$"), "recommendation_upstream", "gateway_recommendation_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/(categories(?:/.*)?|brands|promotions/active)$"), "catalog_upstream", "gateway_catalog_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/products(?:/.*)?$"), "catalog_upstream", "gateway_catalog_cutover_enabled"),
    Route(frozenset({"GET", "POST", "DELETE"}), re.compile(r"^/api/v1/wishlist(?:/.*)?$"), "catalog_upstream", "gateway_catalog_cutover_enabled"),
    Route(frozenset({"GET"}), re.compile(r"^/api/v1/(collections(?:/.*)?|banners/active|pages/[^/]+|visual-search/status)$"), "catalog_upstream", "gateway_catalog_cutover_enabled"),
    Route(frozenset({"POST"}), re.compile(r"^/api/v1/products/search-by-image$"), "catalog_upstream", "gateway_catalog_cutover_enabled"),
    # Controlled strangler fallback: keeps the last stable core release reachable
    # through the single entrypoint until each public bounded-context route cuts over.
    Route(frozenset({"GET", "POST", "PUT", "PATCH", "DELETE"}), re.compile(r"^/api(?:/.*)?$"), "legacy_upstream"),
)

PROTECTED_PATHS = re.compile(r"^/api/v1/(orders|payments|notifications|checkout|wishlist|me)(?:/|$)")
ADMIN_PATHS = re.compile(r"^/api/v1/admin(?:/|$)")

SAFE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,100}$")
TRACEPARENT = re.compile(r"^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"}


class WindowLimiter:
    def __init__(self, per_minute: int, burst: int):
        self.limit = max(1, per_minute + burst)
        self.hits: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str, now: float) -> bool:
        bucket = self.hits[key]
        while bucket and bucket[0] <= now - 60:
            bucket.popleft()
        if len(bucket) >= self.limit:
            return False
        bucket.append(now)
        return True


class Metrics:
    def __init__(self):
        self.requests: dict[tuple[str, str, int], int] = defaultdict(int)
        self.duration_sum: dict[tuple[str, str], float] = defaultdict(float)
        self.upstream_failures: dict[str, int] = defaultdict(int)

    def render(self) -> str:
        lines = [
            "# HELP gateway_http_requests_total Gateway requests.",
            "# TYPE gateway_http_requests_total counter",
        ]
        for (method, route, status), value in sorted(self.requests.items()):
            lines.append(f'gateway_http_requests_total{{method="{method}",route="{route}",status="{status}"}} {value}')
        lines += ["# HELP gateway_http_request_duration_seconds_sum Total request duration.", "# TYPE gateway_http_request_duration_seconds_sum counter"]
        for (method, route), value in sorted(self.duration_sum.items()):
            lines.append(f'gateway_http_request_duration_seconds_sum{{method="{method}",route="{route}"}} {value:.6f}')
        lines += ["# HELP gateway_upstream_failures_total Upstream transport failures.", "# TYPE gateway_upstream_failures_total counter"]
        for upstream, value in sorted(self.upstream_failures.items()):
            lines.append(f'gateway_upstream_failures_total{{upstream="{upstream}"}} {value}')
        return "\n".join(lines) + "\n"


def _route(method: str, path: str) -> Route | None:
    if path.startswith("/internal/") or "/internal/" in path or path.startswith("/actuator"):
        return None
    matched_specific_path = False
    for route in ROUTES:
        if route.upstream_setting == "legacy_upstream" and matched_specific_path:
            return None
        if route.pattern.fullmatch(path):
            if method in route.methods:
                return route
            matched_specific_path = True
    return None


def _upstream_setting(route: Route, path: str, config: Settings) -> str:
    if not route.cutover_setting or getattr(config, route.cutover_setting):
        return route.upstream_setting
    return "legacy_upstream" if path.startswith("/api/") else "chatbot_legacy_upstream"


def _access_claims(authorization: str, settings: Settings) -> dict | None:
    if not authorization.startswith("Bearer "):
        return None
    token = authorization[7:]
    parts = token.split(".")
    if len(parts) != 3:
        return None
    try:
        header = json.loads(base64.urlsafe_b64decode(parts[0] + "=" * (-len(parts[0]) % 4)))
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
        signature = base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4))
    except (ValueError, json.JSONDecodeError):
        return None
    if header.get("alg") != "HS256" or claims.get("type") != "access" or claims.get("exp", 0) <= time.time():
        return None
    signed = f"{parts[0]}.{parts[1]}".encode()
    secrets_to_try = (settings.jwt_access_secret, settings.jwt_previous_access_secret)
    if not any(secret and hmac.compare_digest(signature, hmac.new(secret.encode(), signed, hashlib.sha256).digest())
               for secret in secrets_to_try):
        return None
    subject, role = str(claims.get("sub", "")), str(claims.get("role", ""))
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", subject) or role not in {"CUSTOMER", "SALES_STAFF", "WAREHOUSE_STAFF", "ADMIN"}:
        return None
    return {"sub": subject, "role": role}


def _request_headers(request: Request, correlation_id: str, traceparent: str, settings: Settings) -> dict[str, str]:
    protected = {"x-internal-service-token", "x-forwarded-for", "x-user-id", "x-user-role"}
    headers = {key: value for key, value in request.headers.items() if key.lower() not in HOP_BY_HOP and key.lower() not in protected}
    headers["x-correlation-id"] = correlation_id
    headers["traceparent"] = traceparent
    peer = request.client.host if request.client else "unknown"
    headers["x-forwarded-for"] = peer
    headers["x-forwarded-proto"] = request.url.scheme
    claims = _access_claims(request.headers.get("authorization", ""), settings)
    if claims:
        headers["x-user-id"] = claims["sub"]
        headers["x-user-role"] = claims["role"]
    return headers


def create_app(settings: Settings | None = None, transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    config = settings or Settings()
    limiter = WindowLimiter(config.gateway_rate_limit_per_minute, config.gateway_rate_limit_burst)
    metrics = Metrics()
    logger = logging.getLogger("gateway.access")
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    client = httpx.AsyncClient(transport=transport, timeout=config.gateway_request_timeout_seconds, follow_redirects=False)
    telemetry_client = httpx.AsyncClient(timeout=2.0) if config.otel_exporter_otlp_endpoint else None
    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await client.aclose()
        if telemetry_client:
            await telemetry_client.aclose()
    app = FastAPI(title="Smart Sportswear API Gateway", version="1.0.0", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "ok", "service": "api-gateway"}

    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        return {"status": "ready", "service": "api-gateway"}

    @app.get("/metrics", include_in_schema=False)
    async def prometheus_metrics() -> Response:
        return Response(metrics.render(), media_type="text/plain; version=0.0.4")

    @app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"])
    async def proxy(request: Request, path: str):
        started = time.monotonic()
        raw_id = request.headers.get("x-correlation-id", "")
        correlation_id = raw_id if SAFE_ID.fullmatch(raw_id) else secrets.token_hex(16)
        raw_trace = request.headers.get("traceparent", "").lower()
        traceparent = raw_trace if TRACEPARENT.fullmatch(raw_trace) else f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"
        route = _route(request.method, request.url.path)
        upstream_setting = _upstream_setting(route, request.url.path, config) if route else "blocked"
        label = upstream_setting
        status_code = 404
        response: Response
        peer = request.client.host if request.client else "unknown"
        if not route:
            response = JSONResponse({"status": 404, "message": "Route not found", "correlationId": correlation_id}, status_code=404)
        elif (PROTECTED_PATHS.match(request.url.path) or ADMIN_PATHS.match(request.url.path)) and _access_claims(request.headers.get("authorization", ""), config) is None:
            status_code = 401
            response = JSONResponse({"status": 401, "message": "Authentication required", "correlationId": correlation_id}, status_code=401)
        elif ADMIN_PATHS.match(request.url.path) and _access_claims(request.headers.get("authorization", ""), config).get("role") != "ADMIN":
            status_code = 403
            response = JSONResponse({"status": 403, "message": "Admin role required", "correlationId": correlation_id}, status_code=403)
        elif not limiter.allow(peer, time.monotonic()):
            status_code = 429
            response = JSONResponse({"status": 429, "message": "Rate limit exceeded", "correlationId": correlation_id}, status_code=429, headers={"Retry-After": "60"})
        else:
            target = getattr(config, upstream_setting).rstrip("/") + request.url.path
            if request.url.query:
                target += "?" + request.url.query
            try:
                upstream = await client.send(client.build_request(request.method, target, headers=_request_headers(request, correlation_id, traceparent, config), content=await request.body()), stream=True)
                status_code = upstream.status_code
                response_headers = {key: value for key, value in upstream.headers.items() if key.lower() not in HOP_BY_HOP}
                if upstream.is_stream_consumed:
                    response = Response(upstream.content, status_code=status_code, headers=response_headers, media_type=upstream.headers.get("content-type"))
                else:
                    async def body() -> Iterable[bytes]:
                        try:
                            async for chunk in upstream.aiter_raw():
                                yield chunk
                        finally:
                            await upstream.aclose()
                    response = StreamingResponse(body(), status_code=status_code, headers=response_headers, media_type=upstream.headers.get("content-type"))
            except (httpx.TimeoutException, httpx.NetworkError):
                metrics.upstream_failures[upstream_setting] += 1
                status_code = 503
                response = JSONResponse({"status": 503, "message": "Upstream unavailable", "correlationId": correlation_id}, status_code=503)
        response.headers["X-Correlation-ID"] = correlation_id
        response.headers["traceparent"] = traceparent
        duration = time.monotonic() - started
        metrics.requests[(request.method, label, status_code)] += 1
        metrics.duration_sum[(request.method, label)] += duration
        logger.info(json.dumps({"event": "gateway_request", "method": request.method, "path": request.url.path, "status": status_code, "durationMs": round(duration * 1000, 3), "correlationId": correlation_id, "traceId": traceparent.split("-")[1], "upstream": label}, separators=(",", ":")))
        if telemetry_client:
            ended_ns = time.time_ns()
            payload = {"resourceSpans": [{"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "api-gateway"}}]}, "scopeSpans": [{"scope": {"name": "smart-sportswear.gateway"}, "spans": [{"traceId": traceparent.split("-")[1], "spanId": traceparent.split("-")[2], "name": f"{request.method} {label}", "kind": 2, "startTimeUnixNano": str(ended_ns - int(duration * 1_000_000_000)), "endTimeUnixNano": str(ended_ns), "attributes": [{"key": "http.request.method", "value": {"stringValue": request.method}}, {"key": "url.path", "value": {"stringValue": request.url.path}}, {"key": "http.response.status_code", "value": {"intValue": str(status_code)}}, {"key": "correlation.id", "value": {"stringValue": correlation_id}}, {"key": "gateway.upstream", "value": {"stringValue": label}}], "status": {"code": 2 if status_code >= 500 else 1}}]}]}]}
            async def export_span() -> None:
                try:
                    await telemetry_client.post(config.otel_exporter_otlp_endpoint.rstrip("/") + "/v1/traces", json=payload, headers={"Content-Type": "application/json"})
                except httpx.HTTPError:
                    logger.warning(json.dumps({"event": "trace_export_failed", "traceId": traceparent.split("-")[1]}))
            asyncio.create_task(export_span())
        return response

    return app


app = create_app()

