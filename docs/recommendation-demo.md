# Kịch bản demo Recommendation

Mục tiêu: cho hội đồng thấy luồng **xem sản phẩm → thấy gợi ý → bấm → hệ thống ghi lại**, và mỗi gợi ý ghi rõ tầng (luật kết hợp, content, popularity) đã sinh ra nó.

> **Lưu ý dữ liệu.** Demo ghi vào Supabase dùng chung. `user_interactions` là bảng **chỉ thêm** (append-only, có trigger chặn sửa/xóa), nên sự kiện demo không xóa được. Vì vậy luôn chạy storefront với `NEXT_PUBLIC_INTERACTION_DATA_SOURCE=DEMO` để tách khỏi dữ liệu thật (`REAL`) và dữ liệu đánh giá (`SYNTHETIC`, vốn chỉ nằm trong file).

## 1. Chuẩn bị (một lần)

1. Đặt trong `frontend/storefront/.env.local`:
   ```
   NEXT_PUBLIC_INTERACTION_DATA_SOURCE=DEMO
   ```
2. `RECOMMENDATION_ENABLED=true` và `RECOMMENDATION_CONTENT_MODE=COMPLEMENT` (mặc định) cho recommendation-service.
3. (Nếu muốn tầng luật kết hợp có dữ liệu) cần có đơn hàng trong `order_baskets`, rồi chạy:
   ```
   python scripts/rebuild_model.py association-demo
   ```
   Hiện Supabase chưa có đơn nào, nên khi demo trên dữ liệu thật, gợi ý đến từ **content** và **popularity**. Nói rõ điều này khi demo. Phần chứng minh hiệu quả của luật kết hợp nằm ở kết quả offline (`docs/recommendation-results.md`).
4. Chạy toàn hệ thống: `.\scripts\run\start-all-local.cmd`, rồi kiểm tra `http://localhost:8091/health/ready` trả `ready`.

## 2. Các bước trên giao diện

| Bước | Thao tác | Điều cần chỉ cho hội đồng |
|---|---|---|
| 1 | Mở một sản phẩm, ví dụ `http://localhost:3000/sanpham/giay-chay-bo-nam-cobalt-ride` | Có sự kiện `VIEW` (tối đa 1 lần/10 phút) |
| 2 | Cuộn xuống khối **"Thường mua cùng nhau"** | Khi khối hiện ≥ 50% thì có 1 request lô chứa 4 `RECOMMENDATION_IMPRESSION` (tab Network: `interactions/batch`) |
| 3 | Bấm một sản phẩm trong khối | `RECOMMENDATION_CLICK` cùng `requestId`, rồi `VIEW` của trang mới |
| 4 | Thêm sản phẩm vào giỏ, mở `/gio-hang` | `ADD_TO_CART`; dưới giỏ có khối **"Có thể bạn cũng cần"** (gợi ý cho cả giỏ) |
| 5 | Hỏi chatbot "cho mình xem giày chạy bộ nam Cobalt Ride", rồi hỏi tiếp "gợi ý đồ mua kèm sản phẩm này" (chatbot nhớ sản phẩm từ câu trả lời trước, không đọc trang đang mở) | Tool `recommend_products` (related) trả sản phẩm kèm lý do: "Thường được mua cùng" / "Hợp với sản phẩm bạn đang xem" / "Đang được nhiều người mua" |

## 3. Xem dữ liệu đã ghi

Chạy bằng role đọc schema `recommendation` (ví dụ MCP đọc DB hoặc `psql`):

```sql
-- Các lần gợi ý gần nhất: ngữ cảnh, model, người dùng (nếu đã đăng nhập), độ trễ
select id, context_type, model_version, user_ref, latency_ms, served_at
from recommendation.recommendation_requests
order by served_at desc limit 5;

-- Sản phẩm của một lần gợi ý và tầng đã sinh ra từng sản phẩm
select rank, product_id, round(score::numeric, 3) as score, reason_code
from recommendation.recommendation_items
where request_id = '<id ở câu trên>'
order by rank;

-- Hành vi demo vừa tạo, ghép với lần gợi ý qua request_id
select occurred_at, event_type, product_id, request_id, source, metadata->>'position' as position
from recommendation.user_interactions
where data_source = 'DEMO'
order by occurred_at desc limit 20;

-- CTR theo tầng của các lần gợi ý demo
select i.reason_code,
       count(*) filter (where u.event_type = 'RECOMMENDATION_IMPRESSION') as impressions,
       count(*) filter (where u.event_type = 'RECOMMENDATION_CLICK') as clicks
from recommendation.user_interactions u
join recommendation.recommendation_items i on i.request_id = u.request_id and i.product_id = u.product_id
where u.data_source = 'DEMO'
group by i.reason_code;

-- Model luật kết hợp đang dùng: tham số, mốc cắt, checksum dữ liệu train
select algorithm, version, status, config, training_cutoff_at, training_event_checksum
from recommendation.recommendation_models
order by trained_at desc nulls last limit 3;
```

## 4. Phần offline (không cần chạy hệ thống)

Mở `docs/recommendation-results.md` và giải thích:

1. TEST chạy một lần, cấu hình chọn trên DEV, kế hoạch đăng ký trước ở `docs/recommendation-design.md` mục 7.9 (commit `b4f44506` có trước kết quả).
2. Bảng FBT: hybrid NDCG@5 0,350 so với popularity 0,230; với sản phẩm mới, luật kết hợp = 0 còn content 0,257.
3. Bảng so sánh C1–C9: RQ1, RQ3, RQ4 được ủng hộ; RQ2 có xu hướng tốt nhưng chưa có ý nghĩa thống kê (39 ca).

Có thể chạy lại toàn bộ trên máy để chứng minh tái lập. Hai file dữ liệu và các file split ra **cùng checksum**; chỉ các trường thời điểm chạy và commit trong manifest/báo cáo thay đổi (không commit các thay đổi đó):

```
cd recommendation-service
python scripts/generate_synthetic_interactions.py --config ../datasets/recommendation/synthetic-v1/config.json
python scripts/build_evaluation_split.py --dataset ../datasets/recommendation/synthetic-v1
python scripts/evaluate_offline.py --dataset ../datasets/recommendation/synthetic-v1 --split dev --stage final
```
