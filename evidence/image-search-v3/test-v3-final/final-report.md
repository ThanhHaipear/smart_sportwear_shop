# Kết quả nghiệm thu TEST v3

**Ngày chạy:** 19/11/2026

**Quyết định:** **FAILED / NO-GO**

Bộ `image-search-test-v3:3.0.0` được khóa sau xác nhận duyệt của Quyền (QuyenNDD) do người dùng chuyển lại. Thời điểm trong hồ sơ là lúc ghi nhận xác nhận. Runner chạy chính thức một lần với SigLIP2, cấu hình DEV v3 đã khóa và snapshot cục bộ 85 vector. Độ trễ đo qua HTTP loopback vào API Search nội bộ. [Hồ sơ duyệt](../../../datasets/image-search/test-v3/review-approval.json) · [Cấu hình lần chạy](benchmark-config.json)

| Chỉ số | Kết quả | Ngưỡng | Đánh giá |
| --- | ---: | ---: | --- |
| Recall@5 | 83,82% | ≥ 80% | Đạt |
| Phân loại đúng nhóm | 88,24% (60/68) | ≥ 90% | Chưa đạt |
| Từ chối ảnh ngoài catalog | 72,73% (16/22) | ≥ 80% | Chưa đạt |
| Lỗi request | 0% (0/90) | 0% | Đạt |
| Độ trễ p95 | 290,794 ms | ≤ 3.000 ms | Đạt |
| Ca rò rỉ chưa xử lý | 0 | 0 | Đạt |

## Phân tích từ kết quả đã lưu

- Đồng phục là nhóm yếu nhất, với Recall@5 **53,85%** và phân loại đúng nhóm **61,54%**. Cả 5 ca phân nhóm sai đều trả áo ở vị trí đầu (`test3-030`, `035`, `038`, `040`, `041`).
- Trong 6 ca ảnh ngoài catalog trả kết quả, 5 ca mang subtype `scene` và 1 ca là cốc. Theo thứ tự đầu, 4 ca trả áo, 1 ca trả quần và 1 ca trả phụ kiện.

HAI-16 tiếp tục ở trạng thái `BLOCKED / NO-GO`. Giữ TEST v3 và đầu ra đã khóa để đối chiếu. Dùng các nhóm lỗi làm đầu vào cho một vòng DEV mới, sau đó đánh giá bằng một TEST độc lập khác. [Chỉ số chi tiết](metrics.json) · [Kết quả từng ca](ranked-results.json)
