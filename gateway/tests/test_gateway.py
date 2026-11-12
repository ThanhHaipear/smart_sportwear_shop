import base64
import hashlib
import hmac
import json
import time
import inspect

import httpx
from fastapi.testclient import TestClient

from app import main as gateway_main
from app.main import Settings, create_app


TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
JWT_SECRET = "test-access-secret-that-is-at-least-32-bytes-long"


def access_token(user_id="00000000-0000-0000-0000-000000000123", role="CUSTOMER"):
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()
    header = encode({"alg": "HS256", "typ": "JWT"})
    payload = encode({"sub": user_id, "role": role, "type": "access", "exp": int(time.time()) + 300})
    signature = base64.urlsafe_b64encode(hmac.new(JWT_SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    return f"{header}.{payload}.{signature}"


def gateway(handler, **overrides):
    values = {
        "gateway_rate_limit_per_minute": 2,
        "gateway_rate_limit_burst": 0,
        "jwt_access_secret": JWT_SECRET,
    }
    values.update(overrides)
    settings = Settings(**values)
    return TestClient(create_app(settings, httpx.MockTransport(handler)))


def test_allowlisted_route_propagates_trace_and_correlation():
    def upstream(request):
        assert request.url.host == "identity-service"
        assert request.headers["x-correlation-id"] == "phase9-test"
        assert request.headers["traceparent"] == TRACE
        assert "x-internal-service-token" not in request.headers
        return httpx.Response(200, json={"ok": True})
    response = gateway(upstream).post("/api/v1/auth/login", headers={"X-Correlation-ID": "phase9-test", "traceparent": TRACE}, json={})
    assert response.status_code == 200
    assert response.headers["traceparent"] == TRACE
    assert response.headers["x-correlation-id"] == "phase9-test"


def test_browser_preflight_allows_storefront_auth_requests_without_hitting_upstream():
    upstream_calls = []
    client = gateway(lambda request: upstream_calls.append(request) or httpx.Response(500))
    response = client.options(
        "/api/v1/auth/google",
        headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "http://localhost:3000"
    assert "POST" in response.headers["access-control-allow-methods"]
    assert upstream_calls == []


def test_cors_rejects_unconfigured_origins():
    client = gateway(lambda _: httpx.Response(200))
    response = client.options(
        "/api/v1/auth/login",
        headers={
            "Origin": "https://untrusted.example",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )
    assert response.status_code == 400
    assert "access-control-allow-origin" not in response.headers


def test_internal_and_operational_upstream_routes_are_never_public():
    client = gateway(lambda _: httpx.Response(500))
    for path in ("/internal/v1/refunds", "/api/v1/internal/refunds", "/actuator/prometheus", "/admin", "/docs"):
        assert client.get(path).status_code == 404


def test_catalog_reads_cut_over_without_capturing_recommendation_route():
    hosts = []
    def upstream(request):
        hosts.append(request.url.host)
        return httpx.Response(200, json={"ok": True})
    client = gateway(upstream, gateway_rate_limit_per_minute=20)
    assert client.get("/api/v1/products").status_code == 200
    assert hosts[-1] == "catalog-service"
    assert client.get("/api/v1/categories/tree").status_code == 200
    assert hosts[-1] == "catalog-service"
    assert client.get("/api/v1/products/00000000-0000-0000-0000-000000000000/frequently-bought-together").status_code == 200
    assert hosts[-1] == "recommendation-service"


def test_gateway_strips_spoofed_identity_and_injects_verified_claims():
    observed = []
    def upstream(request):
        observed.append(dict(request.headers))
        return httpx.Response(200)
    client = gateway(upstream, gateway_rate_limit_per_minute=20)
    client.get("/api/v1/products", headers={"X-User-Id": "spoof", "X-User-Role": "ADMIN"})
    assert "x-user-id" not in observed[-1]
    client.get("/api/v1/products", headers={"Authorization": "Bearer " + access_token()})
    assert observed[-1]["x-user-id"] == "00000000-0000-0000-0000-000000000123"
    assert observed[-1]["x-user-role"] == "CUSTOMER"


def test_method_allowlist_and_rate_limit():
    hosts = []
    def upstream(request):
        hosts.append((request.url.host, request.url.port))
        return httpx.Response(204)
    client = gateway(upstream)
    assert client.get("/api/v1/auth/login").status_code == 404
    assert client.post("/api/v1/auth/login").status_code == 204
    assert hosts[-1] == ("identity-service", 8093)
    assert client.post("/api/v1/auth/login").status_code == 204
    limited = client.post("/api/v1/auth/login")
    assert limited.status_code == 429
    assert limited.headers["retry-after"] == "60"


def test_phase11_routes_have_explicit_service_owners_and_unknown_api_is_blocked():
    hosts = []
    def upstream(request):
        hosts.append((request.url.host, request.url.port))
        return httpx.Response(204)
    client = gateway(upstream, gateway_rate_limit_per_minute=20)
    assert client.post("/api/v1/auth/login").status_code == 204
    assert hosts[-1] == ("identity-service", 8093)
    assert client.get("/api/v1/products").status_code == 204
    assert hosts[-1] == ("catalog-service", 8092)
    assert client.post("/api/v1/orders", headers={"Authorization": "Bearer " + access_token()}).status_code == 204
    assert hosts[-1] == ("sales-service", 8094)
    assert client.get("/api/v1/not-in-inventory").status_code == 404
    assert all(host != "host.docker.internal" for host, _ in hosts)


def test_phase11_gateway_has_no_legacy_fallback_code_or_configuration():
    source = inspect.getsource(gateway_main)
    assert "legacy_upstream" not in source
    assert "chatbot_legacy_upstream" not in source
    assert "cutover_enabled" not in source


def test_phase11_new_workflow_routes_reach_their_owners():
    observed = []
    def upstream(request):
        observed.append(request.url.host)
        return httpx.Response(204)
    client = gateway(upstream, gateway_rate_limit_per_minute=50)
    customer = {"Authorization": "Bearer " + access_token()}
    admin = {"Authorization": "Bearer " + access_token(role="ADMIN")}
    cases = (
        ("POST", "/api/v1/orders/00000000-0000-0000-0000-000000000001/cancel", customer, "sales-service"),
        ("PUT", "/api/v1/orders/00000000-0000-0000-0000-000000000001/refund-destination", customer, "sales-service"),
        ("POST", "/api/v1/returns", customer, "sales-service"),
        ("POST", "/api/v1/admin/refunds/00000000-0000-0000-0000-000000000001/manual-confirmation", admin, "sales-service"),
        ("POST", "/api/v1/admin/products/00000000-0000-0000-0000-000000000001/images/upload", admin, "catalog-service"),
        ("GET", "/api/v1/products/sample/reviews", {}, "catalog-service"),
        ("PATCH", "/api/v1/admin/reviews/00000000-0000-0000-0000-000000000001/status", admin, "catalog-service"),
    )
    for method, path, headers, owner in cases:
        assert client.request(method, path, headers=headers).status_code == 204
        assert observed[-1] == owner


def test_upstream_failure_is_bounded_and_safe():
    def unavailable(_):
        raise httpx.ConnectError("secret host detail")
    response = gateway(unavailable).post("/api/v1/orders", headers={"Authorization": "Bearer " + access_token()})
    assert response.status_code == 503
    assert response.json()["message"] == "Upstream unavailable"
    assert "secret" not in response.text


def test_sales_customer_routes_require_verified_access_token():
    client = gateway(lambda _: httpx.Response(200), gateway_rate_limit_per_minute=20)
    # GET /orders must continue past the POST-only exact route and match the
    # broader sales query route before authentication is evaluated.
    assert client.get("/api/v1/orders").status_code == 401
    assert client.get("/api/v1/orders/me").status_code == 401
    assert client.get("/api/v1/orders/me", headers={"Authorization": "Bearer invalid"}).status_code == 401
    assert client.get("/api/v1/me").status_code == 401
    assert client.get("/api/v1/me/addresses").status_code == 401


def test_notification_sse_accepts_eventsource_token_without_forwarding_it():
    observed = []
    def upstream(request):
        observed.append(request)
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=b"event: ready\ndata: ok\n\n")
    token = access_token()
    response = gateway(upstream, gateway_rate_limit_per_minute=20).get(f"/api/v1/notifications/me/stream?access_token={token}")
    assert response.status_code == 200
    assert observed[-1].headers["x-user-id"] == "00000000-0000-0000-0000-000000000123"
    assert "access_token" not in str(observed[-1].url)


def test_metrics_and_health_surface():
    client = gateway(lambda _: httpx.Response(200))
    assert client.get("/health/live").json()["status"] == "ok"
    assert client.get("/health/ready").status_code == 200
    client.post("/chat", json={})
    metrics = client.get("/metrics").text
    assert "gateway_http_requests_total" in metrics
    assert 'route="chatbot_upstream"' in metrics

