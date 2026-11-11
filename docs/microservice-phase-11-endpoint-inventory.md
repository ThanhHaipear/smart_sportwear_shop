# Phase 11 - Frontend endpoint inventory

Kiểm kê ngày 11/11/2026. Mỗi dòng là một contract theo **method + path pattern**; gateway chỉ route các pattern
trong allowlist và trả `404` cho API ngoài inventory. Không còn catch-all hay `legacy_upstream`.

| Consumer | Method | Path/pattern | Owner | Quyết định |
|---|---|---|---|---|
| Storefront | POST | `/api/v1/auth/{register,login,refresh,logout,google,forgot-password,reset-password}` | Identity | Giữ |
| Storefront | GET, PATCH | `/api/v1/me` | Identity | Giữ |
| Storefront | GET, POST | `/api/v1/me/addresses` | Identity | Giữ |
| Storefront | PATCH, DELETE | `/api/v1/me/addresses/{id}`; `PATCH .../{id}/default` | Identity | Giữ |
| Storefront | GET | `/api/v1/products`, `/products/{slugOrId}`, `/products/search-suggestions`, `/products/hybrid-search` | Catalog | Giữ |
| Storefront | GET | `/api/v1/categories`, `/categories/tree`, `/categories/{slugOrId}`, `/brands`, `/promotions/active` | Catalog | Giữ |
| Storefront | GET | `/api/v1/collections`, `/collections/{slug}`, `/banners/active`, `/pages/{slug}`, `/visual-search/status` | Catalog | Giữ; empty/not-found contract có chủ đích |
| Storefront | POST | `/api/v1/products/search-by-image` | Catalog | Giữ; trả `503` khi visual search bị khóa |
| Storefront | GET, POST | `/api/v1/products/{slugOrId}/reviews` | Catalog | Mới Phase 11; POST kiểm tra delivered purchase qua Sales internal API |
| Storefront | GET, POST, DELETE | `/api/v1/wishlist`, `/wishlist/items`, `/wishlist/items/{productId}` | Catalog | Giữ |
| Storefront | GET, POST, PATCH, DELETE | `/api/v1/cart/**` | Sales | Giữ |
| Storefront | POST | `/api/v1/checkout/preview`, `/api/v1/orders` | Sales | Giữ |
| Storefront | GET | `/api/v1/orders/me`, `/orders/{id}`, `/orders/{id}/invoice`, `/orders/{id}/refunds` | Sales | Giữ |
| Storefront | POST, PUT | `/api/v1/orders/{id}/cancel`, `/orders/{id}/refund-destination` | Sales | Mới Phase 11 |
| Storefront | POST, GET | `/api/v1/returns`, `/returns/me`, `/returns/{id}`, `/returns/{id}/cancel` | Sales | Mới Phase 11 |
| Storefront | POST, GET | `/api/v1/payments/vietqr`, `/payments/vietqr/{id}`, `/payments/{orderId}` | Sales | Giữ |
| Storefront | GET, POST | `/api/v1/notifications/me`, `/unread-count`, `/{id}/read`, `/read-all` | Sales | Giữ |
| Storefront | GET (SSE) | `/api/v1/notifications/me/stream` | Sales | Mới Phase 11; EventSource token chỉ được gateway nhận và không forward trong query |
| Storefront | GET | `/api/v1/settings/public` | Sales | Giữ |
| Storefront | GET | `/api/v1/products/{id}/frequently-bought-together` | Recommendation | Giữ |
| Admin | GET, PATCH | `/api/v1/admin/users`, `/users/{id}`, `/users/{id}/{role,status}` | Identity | Giữ |
| Admin | GET, POST, PATCH, DELETE | `/api/v1/admin/{categories,brands,products,variants,inventory}/**` | Catalog | Giữ |
| Admin | POST multipart | `/api/v1/admin/products/{id}/images/upload` | Catalog | Mới Phase 11; file JPEG/PNG/WebP tối đa 10 MB, volume riêng |
| Admin | GET, PATCH | `/api/v1/admin/reviews`, `/reviews/{id}`, `/reviews/{id}/status` | Catalog | Mới Phase 11 |
| Admin | GET, PATCH, POST | `/api/v1/admin/orders/**` (gồm packing-slip, cancel, refunds) | Sales | Giữ/mở rộng Phase 11 |
| Admin | GET, PATCH, POST | `/api/v1/admin/returns/**`, `/api/v1/admin/refunds/**` | Sales | Mới Phase 11; manual confirmation hoàn tất refund idempotent theo trạng thái |
| Admin | GET | `/api/v1/admin/reports/{overview,orders,revenue,products,customers,inventory}` | Sales | Giữ |
| Admin | GET, PUT | `/api/v1/admin/settings`, `/settings/{key}` | Sales | Giữ |
| Admin | GET | `/api/v1/admin/audit-logs` | Sales | Giữ |
| Both | — | `/api/v1/realtime/stream`, `/api/v1/inventory/stream` | Loại bỏ | Frontend chuyển sang polling 30 giây; notification vẫn dùng SSE theo user |
| Admin | — | `/api/v1/admin/orders/{id}/cancellation-rejection` | Loại bỏ | Không còn được UI gọi; contract cancel là immediate, không có trạng thái chờ duyệt |

Internal API không public qua gateway: Catalog gọi `GET /internal/v1/reviews/eligibility`; Sales tiếp tục gọi Catalog
reservation/snapshot. `/internal/**`, actuator và docs luôn bị gateway chặn.
