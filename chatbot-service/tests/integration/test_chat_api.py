"""
Integration tests for POST /chat — exercises the full pipeline via the ASGI app.

Isolation:
  - DB pool: null_db fixture returns None → keyword_search produces empty results
  - Redis: REDIS_URL="" → InMemorySessionStore (fresh per test via lifespan)
  - Backend HTTP: patched at service level for action-branch tests
"""
from __future__ import annotations

import jwt
import pytest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch

from app.schemas.action import CancelOrderResult, AddToCartResult


# ── Helpers ────────────────────────────────────────────────────────────────────

def _req(message: str, session_id: str = "test_sess",
         user_id: str | None = None, access_token: str | None = None) -> dict:
    token = None
    if access_token:
        token = jwt.encode(
            {
                "sub": user_id or "test-user",
                "role": "CUSTOMER",
                "type": "access",
                "exp": datetime.now(timezone.utc) + timedelta(minutes=15),
            },
            "test-access-secret-that-is-at-least-32-bytes",
            algorithm="HS256",
        )
    return {
        "sessionId": session_id,
        "userId": user_id,
        "accessToken": token,
        "message": message,
        "channel": "web",
    }


# ── 1. Health ──────────────────────────────────────────────────────────────────

async def test_health(http_client):
    resp = await http_client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "chatbot-service"


async def test_correlation_id_is_propagated(http_client):
    resp = await http_client.get("/health/live", headers={"X-Correlation-ID": "phase4-test"})
    assert resp.status_code == 200
    assert resp.headers["X-Correlation-ID"] == "phase4-test"


async def test_metrics_endpoint(http_client):
    resp = await http_client.get("/metrics")
    assert resp.status_code == 200
    assert "chatbot_http_requests_total" in resp.text


# ── 2. UNKNOWN intent (no tool) ───────────────────────────────────────────────

async def test_unknown_intent(http_client):
    resp = await http_client.post("/chat", json=_req("xin chào"))
    assert resp.status_code == 200
    body = resp.json()
    assert body["reply"]
    assert body["sessionState"]["intent"] == "UNKNOWN"
    assert body["sessionState"]["selectedTool"] == "none"
    assert not body["sessionState"]["awaitingConfirmation"]


# ── 3. PRODUCT_SEARCH (no DB → empty results) ─────────────────────────────────

async def test_product_search_no_db(http_client):
    resp = await http_client.post("/chat", json=_req("tôi muốn tìm giày chạy bộ"))
    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "PRODUCT_SEARCH"
    assert body["reply"]  # fallback "no results" message


# ── 4. KNOWLEDGE_QA ───────────────────────────────────────────────────────────

async def test_knowledge_qa(http_client):
    resp = await http_client.post("/chat", json=_req("chính sách đổi trả của shop"))
    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "KNOWLEDGE_QA"
    assert body["reply"]


# ── 5. SIZE_ADVISOR — rule-based (no DB needed) ───────────────────────────────

async def test_size_advisor_rule_based(http_client):
    resp = await http_client.post("/chat", json=_req("tôi cao 1m70 nặng 65kg mặc size nào?"))
    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "SIZE_ADVISOR"
    assert body["toolCalls"][0]["result"]["source"] == "rule_based"
    assert body["toolCalls"][0]["result"]["suggestedSize"] == "L"


# ── 6. ORDER_STATUS — no auth token → auth-blocked reply ─────────────────────

async def test_order_status_no_auth(http_client):
    resp = await http_client.post("/chat", json=_req(
        "đơn hàng SP001 của tôi đâu rồi", user_id="u1", access_token=None
    ))
    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "ORDER_STATUS"
    # No token → tool returns error or service returns auth_required
    assert body["sessionState"]["blockedReason"] == "auth_required"


# ── 7. CANCEL_ORDER → awaitingConfirmation ────────────────────────────────────

async def test_cancel_order_creates_pending(http_client):
    resp = await http_client.post("/chat", json=_req(
        "hủy đơn SP001", user_id="u1", access_token="tok1", session_id="sess_cancel"
    ))
    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "CANCEL_ORDER"
    assert body["sessionState"]["awaitingConfirmation"] is True
    assert body["sessionState"]["pendingAction"] == "cancel_order"
    assert "đồng ý" in body["reply"] or "xác nhận" in body["reply"].lower() or "đồng ý" in body["reply"].lower()


# ── 8. CONFIRM_ACTION — executes cancel after confirmation ────────────────────

async def test_confirm_cancel_order(http_client):
    cancel_result = CancelOrderResult(
        success=True,
        orderCode="SP001",
        orderStatus="CANCELLED",
        orderStatusLabel="Đã hủy",
    )
    with patch(
        "app.services.order_action_service.cancel_order",
        new=AsyncMock(return_value=cancel_result),
    ):
        # Step 1: create pending
        await http_client.post("/chat", json=_req(
            "hủy đơn SP001", user_id="u1", access_token="tok1", session_id="sess_confirm"
        ))
        # Step 2: confirm
        resp = await http_client.post("/chat", json=_req(
            "đồng ý", user_id="u1", access_token="tok1", session_id="sess_confirm"
        ))

    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "CONFIRM_ACTION"
    assert not body["sessionState"]["awaitingConfirmation"]
    assert body["reply"]


# ── 9. REJECT_ACTION — cancels pending ────────────────────────────────────────

async def test_reject_cancel_order(http_client):
    # Step 1: create pending
    await http_client.post("/chat", json=_req(
        "hủy đơn SP002", user_id="u1", access_token="tok1", session_id="sess_reject"
    ))
    # Step 2: reject
    resp = await http_client.post("/chat", json=_req(
        "không", user_id="u1", access_token="tok1", session_id="sess_reject"
    ))

    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "REJECT_ACTION"
    assert not body["sessionState"]["awaitingConfirmation"]
    # Reply should confirm cancellation
    assert body["reply"]


# ── 10. EXPIRED_CONFIRMATION ──────────────────────────────────────────────────

async def test_expired_confirmation(http_client):
    from app.memory.base_store import PENDING_ACTION_TTL_SECONDS
    from app.memory import session_store

    # Manually plant an expired pending action in the session store
    old_ts = (datetime.now(timezone.utc) - timedelta(seconds=PENDING_ACTION_TTL_SECONDS + 60)).isoformat()
    await session_store.update_context("user:u1:sess_expired",
        pending_action="cancel_order",
        pending_action_payload={"tool": "cancel_order", "args": {}, "display": "hủy đơn"},
        pending_action_created_at=old_ts,
    )

    resp = await http_client.post("/chat", json=_req(
        "đồng ý", user_id="u1", access_token="tok1", session_id="sess_expired"
    ))

    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionState"]["intent"] == "EXPIRED_CONFIRMATION"
    assert not body["sessionState"]["awaitingConfirmation"]
    assert body["reply"]


# ── 11. Response shape invariants ─────────────────────────────────────────────

async def test_response_always_has_required_fields(http_client):
    resp = await http_client.post("/chat", json=_req("bất kỳ tin nhắn nào"))
    assert resp.status_code == 200
    body = resp.json()
    assert "reply" in body
    assert "toolCalls" in body
    assert "suggestions" in body
    assert "sessionState" in body
    ss = body["sessionState"]
    assert "sessionId" in ss
    assert "intent" in ss
    assert "selectedTool" in ss
    assert "awaitingConfirmation" in ss
