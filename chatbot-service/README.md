# chatbot-service

## V2 1C.2 local product embeddings

Canonical V2 embeddings use schema `ai_retrieval`, not the legacy OpenAI/Voyage tables described later in this file.

```powershell
cd chatbot-service
.venv\Scripts\python.exe -m pip install -r requirements-embeddings.txt
cd ..
.\backfill-product-embeddings.ps1 -Modality text
.\backfill-product-embeddings.ps1 -Modality image
.\backfill-product-embeddings.ps1 -Verify
```

- BGE-M3 `5617a9f61b028005a4858fdac845db406aefb181`: `vector(1024)` for product documents.
- SigLIP2 `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`: `vector(768)` from versioned Cloudinary originals.
- Run modalities sequentially on low-memory machines. Jobs are resumable; repeated runs mark current rows `SKIPPED` without loading the model.
- Pilot search remains exact cosine; no HNSW/IVFFlat index is created.

AI chatbot service cho sport shop — độc lập với Spring Boot backend.

**Phase hiện tại: Phase 10 — Test Suite + Reliability Hardening**

---

## Cách chạy local

```bash
cd chatbot-service
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env
# Điền DB_READ_URL và BACKEND_API_BASE_URL vào .env
python run.py
```

Service chạy tại `http://localhost:8002`

---

## Chạy test (Phase 10)

```bash
cd chatbot-service
pip install -r requirements-test.txt

# Chạy toàn bộ test suite
pytest

# Chỉ unit tests
pytest tests/unit/

# Chỉ integration tests (cần ASGI stack, không cần DB / Redis)
pytest tests/integration/

# Contract tests (mock backend HTTP)
pytest tests/contract/

# Với output chi tiết
pytest -v

# Dừng ở lỗi đầu tiên
pytest -x
```

### Phân loại test

| Loại | Thư mục | Mô tả |
|------|---------|-------|
| Unit | `tests/unit/` | Pure function tests — không I/O, không network |
| Integration | `tests/integration/` | POST /chat qua ASGI app — DB=None, Redis=in-memory |
| Contract | `tests/contract/` | Service layer vs backend HTTP — mock ở client level |

### Yêu cầu

- Không cần DB, không cần Redis, không cần OpenAI key để chạy test
- `DB_READ_URL=""` → keyword search trả kết quả rỗng (an toàn)
- `REDIS_URL=""` → InMemorySessionStore (fallback tự động)
- `OPENAI_API_KEY=""` → vector branch bị skip (an toàn)

### Coverage đã có

| File | Tests |
|------|-------|
| `retrieval/query_parser.py` | `test_query_parser.py` |
| `retrieval/synonym_rewriter.py` | `test_synonym_rewriter.py` |
| `retrieval/rrf_fusion.py` | `test_rrf_fusion.py` |
| `retrieval/heuristic_reranker.py` | `test_heuristic_reranker.py` |
| `retrieval/result_guard.py` | `test_result_guard.py` |
| `memory/context_resolver.py` | `test_context_resolver.py` |
| `memory/base_store.py` + `in_memory_store.py` | `test_session_store.py` |
| `graph/nodes/intent_router.py` | `test_intent_router.py` |
| `services/size_advisor_service.py` | `test_size_advisor.py` |
| `api/chat.py` (E2E flows) | `test_chat_api.py` |
| `services/order_action_service.py` + `cart_action_service.py` | `test_action_branch.py` |

---

## Endpoints

| Method | Path                   | Mô tả                              |
|--------|------------------------|------------------------------------|
| GET    | /health                | Health check                       |
| POST   | /chat                  | Chat — đi qua orchestration graph  |
| GET    | /admin/capabilities    | Capability on/off (mock)           |
| POST   | /admin/capabilities    | Toggle capability                  |
| GET    | /admin/tools           | Tool metadata                      |
| GET    | /docs                  | Swagger UI                         |

---

## PRODUCT_SEARCH Pipeline (Phase 8 — Hybrid Retrieval)

```
search_products_tool
  -> ProductSearchService.search(query)
  ├─ synonym_rewriter.rewrite(query)     — controlled synonym rewrite (no LLM)
  ├─ parse_query(query)                  — type / sport / gender / price / features
  ├─ ProductFilter(limit × 2)            — wider candidate pool for fusion
  │
  ├─ keyword_retrieve(filter)            — SQL ILIKE + structured filters (Phase 3, always runs)
  ├─ vector_retrieve(query, filter)      — OpenAI embedding + pgvector cosine search (fail-safe)
  │
  ├─ rrf_fusion(keyword, vector)         — Reciprocal Rank Fusion, k=60
  │     skip if vector empty → keyword-only (Phase 3 path preserved)
  │
  ├─ enrich(fused)                       — variant data, clean lists
  ├─ apply_guards(enriched)              — stock / type / gender hard guards
  ├─ heuristic_rerank(guarded, parsed)   — sport match +0.3, token overlap, vector score bonus
  │
  └─ format_results(reranked)            — same ProductSearchResult schema
        (retry with rewritten query if result is empty)
```

### Vector Branch Requirements

| Requirement | Status |
|-------------|--------|
| `OPENAI_API_KEY` in `.env` | Required for `text-embedding-3-small` |
| `openai` Python package | Required |
| `product_embeddings` table with pgvector | Required (DDL below) |
| pgvector extension on Supabase | Required (`CREATE EXTENSION vector`) |

Flyway `V16__product_embeddings_openai_1536.sql` enforces the final 1536-dimension
schema and clears incompatible vectors. Run the indexing script after migration.

```sql
-- Supabase migration to enable vector search
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE product_embeddings (
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    embedding   vector(1536) NOT NULL,
    document_text TEXT,
    updated_at  TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (product_id)
);

-- IVFFlat index for approximate nearest neighbor search
CREATE INDEX ON product_embeddings
  USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);
```

Document format for embedding (1 product = 1 document):
```
{name} {category} {sport_type} {gender} {brand} {short_description}
```

**Without `OPENAI_API_KEY` or without the table**: vector branch is silently skipped and the system runs keyword-only retrieval.

SKU is not embedded. Queries containing an explicit SKU use `lookup_product_by_sku`
and query `product_variants.sku` directly before any semantic retrieval.

## Graph Flow (Phase 9)

```
POST /chat
  -> load session_context (Redis or in-memory, per sessionId)
  -> intent_router       — 11 intents: 8 + CONFIRM_ACTION / REJECT_ACTION / EXPIRED_CONFIRMATION (Phase 9)
  -> tool_selector
     ├─ PRODUCT_DETAIL:    context_resolver → product_id + size/color hints
     ├─ RECOMMEND_PRODUCTS: context → "related" or "need_based" mode
     ├─ SIZE_ADVISOR:      context_resolver → product_id (optional)
     └─ others: existing arg extraction
  -> policy_guard        — capability / auth / confirmation
  -> tool_executor
     ├─ search_products      → ProductSearchService  → SQL
     ├─ get_product_detail   → ProductDetailService  → SQL
     ├─ recommend_products   → RecommendationService → SQL (related) | search pipeline (need_based)
     ├─ size_advisor         → SizeAdvisorService    → variant DB | rule-based | knowledge corpus
     ├─ answer_knowledge     → KnowledgeSearchService → file-based corpus
     ├─ get_order_status     → OrderActionService → backend
     ├─ add_to_cart          → CartActionService  → backend
     └─ cancel_order         → OrderActionService → backend
  -> response_generator  — grounded replies for all 8 intents
  -> update session_context
  -> ChatResponse
```

---

## Transaction Branch (Phase 5)

### Backend endpoints đã map

| Tool | Method | Path | Ghi chú |
|------|--------|------|---------|
| get_order_status | GET | `/api/v1/orders/me?limit=100` | Tìm theo orderCode client-side |
| add_to_cart | POST | `/api/v1/cart/items` | Body: `{variantId, quantity}` |
| cancel_order | POST | `/api/v1/orders/{id}/cancel` | Body: `{reason?}` (optional) |

### Luồng ORDER_STATUS

1. User: "cho xem đơn DH123"
2. tool_selector trích xuất `order_code = "DH123"` bằng regex `DH\d+`
3. policy_guard: `requires_auth=True` → nếu không có token → blocked
4. OrderActionService → order_api_client → `GET /api/v1/orders/me?limit=100`
5. Filter theo orderCode client-side
6. Response: "Đơn hàng DH123: Đang giao hàng. Thanh toán: Đã thanh toán. Tổng tiền: 350,000đ."

### Luồng ADD_TO_CART

1. User: "thêm sản phẩm vào giỏ"
2. tool_selector: `variant_id = None` (không parse được từ text tự do)
3. CartActionService: nếu `variant_id` absent → trả lỗi rõ ràng
4. **Tradeoff**: chatbot không thể tự resolve UUID variant từ text tự do.
   Frontend phải inject `variant_id` vào args khi user đã chọn sản phẩm từ kết quả search.
5. Nếu có `variant_id` → `POST /api/v1/cart/items` → reply với số sản phẩm trong giỏ và tạm tính.

### Luồng CANCEL_ORDER (Phase 9 — multi-turn confirmation)

1. User: "hủy đơn DH123"
2. policy_guard: `requires_confirmation=True` → block + lưu `pending_action` vào session
3. Reply: "Bạn có chắc muốn hủy đơn DH123 không? Nhập **'đồng ý'** để tiếp tục hoặc **'không'** để hủy bỏ."
4. User: "đồng ý"
5. intent_router: phát hiện `CONFIRM_ACTION` (session có pending_action + chưa hết hạn 5 phút)
6. tool_selector: rehydrate `pending_action_payload`, inject `access_token` từ turn hiện tại
7. policy_guard: bypass confirmation check (intent == CONFIRM_ACTION)
8. tool_executor: gọi `cancel_order` → backend thật:
   - Tìm order UUID từ code
   - `POST /api/v1/orders/{id}/cancel`
9. Reply: "Tôi đã gửi yêu cầu hủy đơn DH123 thành công. Trạng thái hiện tại: ..."

Nếu user nói "không" → `REJECT_ACTION` → xóa pending_action → "Tôi đã hủy thao tác trước đó."
Nếu quá 5 phút → `EXPIRED_CONFIRMATION` → "Tôi không còn thấy thao tác chờ xác nhận..."

---

## Auth / Token Forwarding

ChatRequest nhận `accessToken` từ client:

```json
{
  "sessionId": "sess_001",
  "userId": "u_123",
  "accessToken": "eyJhbGc...",
  "message": "cho xem đơn DH123",
  "channel": "web"
}
```

- `accessToken` được inject vào `AgentState.access_token`
- `tool_selector` pass `access_token` vào `tool_args` cho action tools
- `backend_api_client` dùng `Authorization: Bearer <token>` header
- Token **không được log** (chỉ log status code)

---

## Error Handling

| HTTP Status | errorCode | Reply |
|-------------|-----------|-------|
| 401 | auth_required | "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại." |
| 403 | forbidden | "Bạn không có quyền thực hiện thao tác này." |
| 404 | not_found | "Không tìm thấy đơn hàng {code}." |
| 409 | conflict | "Không thể thực hiện do xung đột nghiệp vụ." |
| 400/422 | invalid | "Yêu cầu không hợp lệ." |
| timeout | timeout | "Hệ thống không phản hồi. Vui lòng thử lại sau." |
| connect error | unavailable | "Không thể kết nối đến hệ thống..." |

---

## Biến môi trường

| Biến                        | Default                | Dùng từ phase |
|-----------------------------|------------------------|---------------|
| CHATBOT_PORT                | 8002                   | Phase 0       |
| DB_READ_URL                 | (trống)                | Phase 3       |
| DB_WRITE_URL                | (trống)                | Durable chat history; disabled when empty |
| BACKEND_API_BASE_URL        | localhost:8082         | Phase 5       |
| JWT_ACCESS_SECRET           | (trống)                | Phải giống backend; xác minh JWT và khóa API admin |
| LOG_LEVEL                   | INFO                   | Phase 0       |
| MODEL_PROVIDER              | anthropic              | `anthropic` hoặc `openai` |
| MODEL_NAME                  | claude-sonnet-4-6      | Model used by the configured provider |
| ANTHROPIC_API_KEY           | (trống)                | Required when provider is Anthropic |
| OPENAI_API_KEY              | (trống)                | Required for embeddings and when provider is OpenAI |
| EMBEDDING_MODEL             | text-embedding-3-small | Product/query embedding model |
| EMBEDDING_DIMS              | 1536                   | Must match `product_embeddings.embedding` |
| REDIS_URL                   | redis://localhost:6379 | **Phase 9** — session persistence; fallback in-memory nếu trống/lỗi |
| SESSION_TTL_SECONDS         | 3600                   | **Phase 9** — TTL session Redis + pending action expiry (5 phút hardcoded) |
| OBSERVABILITY_ENABLED       | true                   | **Phase 9** — bật/tắt trace logging |
| EVALUATION_LOGGING_ENABLED  | true                   | **Phase 9** — bật/tắt evaluation event logging |

---

## Session Memory (Phase 9 — Redis-backed)

Short-term context per `sessionId`. Redis-backed khi `REDIS_URL` được cấu hình; fallback về in-memory nếu Redis không sẵn sàng (service không bao giờ fail cứng vì thiếu Redis).

| Field | Lưu khi nào | Dùng để |
|-------|-------------|---------|
| `last_product_ids` | Sau PRODUCT_SEARCH | Resolve "mẫu thứ 2" |
| `last_products_summary` | Sau PRODUCT_SEARCH | Resolve tên gần đúng |
| `selected_product_id` | Sau PRODUCT_SEARCH / PRODUCT_DETAIL | Resolve "mẫu này" |
| `selected_product_name` | Tương tự | Debug / log |
| `selected_variant_hints` | Sau PRODUCT_DETAIL có hint | Nền cho ADD_TO_CART sau này |
| `last_intent` | Mỗi turn | Context chung |
| `pending_action` | Khi action bị block (confirmation_required) | Tool name cần xác nhận |
| `pending_action_payload` | Cùng lúc với pending_action | Tool + args (không có access_token) |
| `pending_action_created_at` | Cùng lúc với pending_action | TTL check 5 phút |

## Follow-up Resolver (Phase 6)

Các pattern được support trong `context_resolver.py`:

| Pattern | Ví dụ | Cách resolve |
|---------|-------|--------------|
| Ordinal | "mẫu thứ 2", "cái thứ ba" | `last_product_ids[1]` |
| Ordinal đầu | "mẫu đầu tiên", "mẫu thứ nhất" | `last_product_ids[0]` |
| Proximal | "mẫu này", "cái này", "sản phẩm này" | `selected_product_id` → `last_product_ids[0]` |
| Proximal xa | "mẫu đó", "cái đó", "giày đó" | Như proximal |
| Tên gần đúng | "cái áo Dry-Fit" | Substring match trong `last_products_summary` |
| Không resolve | context trống hoặc ambiguous | Trả về None → chatbot hỏi lại |

## Cấu trúc thư mục

```
knowledge/         — Markdown knowledge source (Phase 4)
app/
  memory/
    base_store.py            — BaseSessionStore ABC + default_context() + is_pending_expired()
    in_memory_store.py       — InMemorySessionStore (default / fallback)
    redis_store.py           — RedisSessionStore với TTL
    store_factory.py         — init_store() singleton; chọn backend khi startup
    session_store.py         — module-level async API (get_context, update_context, clear_pending)
    context_resolver.py      — product reference resolver + variant hint parser
  clients/
    backend_api_client.py   — base HTTP wrapper (httpx, error mapping)
    order_api_client.py     — GET orders/me, cancel
    cart_api_client.py      — POST cart/items
  services/
    product_search_service.py
    product_detail_service.py    — Phase 6: detail + variants
    recommendation_service.py    — Phase 7: related (SQL) | need_based (search pipeline)
    size_advisor_service.py      — Phase 7: product variants | rule_based | knowledge corpus
    knowledge_search_service.py
    order_action_service.py
    cart_action_service.py
  schemas/
    chat.py, product.py, knowledge.py
    action.py               — OrderStatusResult, AddToCartResult, CancelOrderResult
  graph/
    state.py         — AgentState (+ pending_action_display Phase 9)
    chat_graph.py
    nodes/           — intent_router (11 intents), tool_selector, policy_guard, tool_executor, response_generator
  tools/             — 8 tools (+ get_product_detail Phase 6, recommend_products + size_advisor Phase 7)
  retrieval/         — product (keyword/vector/fusion/rerank/query_rewrite), knowledge
  repositories/      — product_repository, vector_repository (Phase 8), knowledge
  policy/            — capability, auth, confirmation
  observability/
    trace_logger.py          — cơ bản: chat_request, tool_call, pending_action events
    tool_call_logger.py      — structured tool dispatch log (Phase 9)
    evaluation_logger.py     — evaluation turn record (Phase 9)
  db/pool.py         — asyncpg pool
```

---

## Roadmap

| Phase | Nội dung                                           | Status   |
|-------|----------------------------------------------------|----------|
| 0     | Scaffold, mock endpoint                            | ✅ Done   |
| 1     | Orchestration nền, tool mock                       | ✅ Done   |
| 2     | Policy layer                                       | ✅ Done   |
| 3     | Product retrieval v1                               | ✅ Done   |
| 4     | Knowledge retrieval v1                             | ✅ Done   |
| 5     | Transaction / action qua Spring Boot backend       | ✅ Done   |
| 6     | Product detail + variant-aware follow-up           | ✅ Done   |
| 7     | Recommendation v1 + Size Advisor v1                | ✅ Done   |
| 8     | Hybrid retrieval: vector + RRF + rerank + rewrite  | ✅ Done   |
| 9     | Confirmation flow + Redis session + Observability  | ✅ Done   |
| 10    | Test suite + reliability hardening                 | ✅ Done   |
| 11    | Long-term memory, personalization...               | Planned  |
