# Đánh giá DEV v4.1 trên sáu nhóm sản phẩm

Vòng này thử trộn tín hiệu mô tả đồng phục và ảnh ngoài catalog với tín hiệu SigLIP2 gốc. Runner thử 100 tổ hợp trên 68 ca calibration, gồm 50 ca DEV đã có và 18 ca mới. Sau khi chọn tham số, runner đánh giá ba cấu hình trên 24 ảnh DEV-check mới gồm bốn ca cho mỗi nhóm. Cả ba cấu hình dùng cùng SigLIP2, exact cosine và snapshot 85 vector của HAI-14. [Bộ dữ liệu](../../datasets/image-search/dev-v4-balanced/README.md) · [Kết quả từng ca](dev-balanced-evaluation-2026-11-19.json)

| Cấu hình trên 24 ảnh DEV-check mới | Recall@5 | Đúng nhóm | Từ chối ảnh ngoài catalog |
| --- | ---: | ---: | ---: |
| HAI-14 gốc | 75,83% | 70,00% (14/20) | 25,00% (1/4) |
| Dùng đầy đủ hai bộ mô tả mới | 84,58% | 85,00% (17/20) | 100% (4/4) |
| Cấu hình chọn từ calibration | 75,83% | 70,00% (14/20) | 75,00% (3/4) |
| Ngưỡng DEV | 80% | 90% | 80% |

Cấu hình chọn từ calibration giữ trọng số đồng phục ở 0, dùng 0,25 trọng số mô tả ngoài catalog và giữ ngưỡng tỷ lệ unknown ở 1,2. Trên calibration, cấu hình này đạt Recall@5 82,41%, phân loại đúng nhóm 92,59% và từ chối ảnh ngoài catalog 85,71%. Kết quả trên DEV-check mới dưới cả ba ngưỡng.

Khi dùng đầy đủ hai bộ mô tả mới, phân nhóm đúng đồng phục tăng từ 1/4 lên 4/4. Cả bốn ảnh giày và bốn ảnh phụ kiện vẫn được phân nhóm đúng, trong đó phụ kiện chỉ gồm bình nước. Ba lỗi còn lại giống cấu hình gốc.

| Ca | Nhãn | Nhóm ở vị trí đầu |
| --- | --- | --- |
| `dev4b-019` | Áo tập | Đồng phục |
| `dev4b-024` | Quần đùi bóng đá | Đồng phục |
| `dev4b-025` | Quần đùi bóng đá | Áo |

**Chưa khóa cấu hình mới.** Vòng DEV sau cần bổ sung ca phân biệt áo hoặc quần được crop riêng với đồng phục đủ bộ, cùng các loại phụ kiện ngoài bình nước. Các ca và kết quả trên được lưu để đối chiếu. HAI-16 tiếp tục ở trạng thái `BLOCKED / NO-GO`.
