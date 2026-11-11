import httpx
from fastapi.testclient import TestClient

from app.main import Settings, create_app


TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"


def gateway(handler, **overrides):
    values = {
        "gateway_rate_limit_per_minute": 2,
        "gateway_rate_limit_burst": 0,
        "gateway_identity_cutover_enabled": True,
        "gateway_sales_cutover_enabled": True,
        "gateway_chatbot_cutover_enabled": True,
        "gateway_recommendation_cutover_enabled": True,
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


def test_internal_and_operational_upstream_routes_are_never_public():
    client = gateway(lambda _: httpx.Response(500))
    for path in ("/internal/v1/refunds", "/api/v1/internal/refunds", "/actuator/prometheus", "/admin", "/docs"):
        assert client.get(path).status_code == 404


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


def test_cutover_flags_default_to_stable_upstreams():
    hosts = []
    def upstream(request):
        hosts.append((request.url.host, request.url.port))
        return httpx.Response(204)
    client = gateway(upstream, gateway_identity_cutover_enabled=False,
                     gateway_sales_cutover_enabled=False,
                     gateway_chatbot_cutover_enabled=False)
    assert client.post("/api/v1/auth/login").status_code == 204
    assert hosts[-1] == ("host.docker.internal", 8080)
    assert client.post("/api/v1/orders").status_code == 204
    assert hosts[-1] == ("host.docker.internal", 8080)
    # A fresh client avoids coupling this routing assertion to the rate-limit bucket.
    chat = gateway(upstream, gateway_chatbot_cutover_enabled=False)
    assert chat.post("/chat").status_code == 204
    assert hosts[-1] == ("host.docker.internal", 8002)


def test_upstream_failure_is_bounded_and_safe():
    def unavailable(_):
        raise httpx.ConnectError("secret host detail")
    response = gateway(unavailable).post("/api/v1/orders")
    assert response.status_code == 503
    assert response.json()["message"] == "Upstream unavailable"
    assert "secret" not in response.text


def test_metrics_and_health_surface():
    client = gateway(lambda _: httpx.Response(200))
    assert client.get("/health/live").json()["status"] == "ok"
    assert client.get("/health/ready").status_code == 200
    client.post("/chat", json={})
    metrics = client.get("/metrics").text
    assert "gateway_http_requests_total" in metrics
    assert 'route="chatbot_upstream"' in metrics

