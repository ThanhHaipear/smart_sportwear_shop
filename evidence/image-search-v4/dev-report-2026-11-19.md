# Vòng phát triển DEV v4 sau TEST v3

Ngày 19/11/2026, thử bổ sung mô tả về bộ đồng phục đầy đủ, ảnh bối cảnh và vật dụng gia đình vào xếp hạng SigLIP2. Các cấu hình dùng chung snapshot 85 vector, exact cosine, model và tiền xử lý đã khóa ở HAI-14. Thử 16 cấu hình trên 50 ảnh calibration rồi đo trên 59 ảnh DEV-check đã có. Sau khi lưu các cấu hình, kiểm tra lại trên 20 ảnh mới được chọn theo nội dung. [Chẩn đoán trên DEV có sẵn](dev-prompt-diagnostic-2026-11-19.json) · [Kiểm tra trên ảnh DEV mới](dev-focus-check-2026-11-19.json)

## DEV-check có sẵn, đủ sáu nhóm

| Cấu hình | Recall@5 | Đúng nhóm | Từ chối ảnh ngoài catalog |
| --- | ---: | ---: | ---: |
| HAI-14 gốc | 86,74% | 93,18% | 86,67% |
| Thêm mô tả đồng phục | 86,36% | 90,91% | 86,67% |
| Thêm mô tả ảnh ngoài catalog | 84,47% | 90,91% | 93,33% |
| Thêm cả hai bộ mô tả | 84,09% | 88,64% | 93,33% |
| Cấu hình chọn từ calibration, hạ tỷ lệ unknown từ 1,2 xuống 1,0 | 84,47% | 90,91% | 100% |

Cả năm cấu hình đều phân nhóm đúng 6/6 ca đồng phục trong tập này. Cấu hình chọn từ calibration loại đúng thêm hai ảnh ngoài catalog và loại nhầm một ảnh phụ kiện so với cấu hình gốc.

## DEV-check mới, tập trung vào hai nhóm

| Cấu hình | Recall@5 đồng phục | Đúng nhóm đồng phục | Từ chối ảnh ngoài catalog |
| --- | ---: | ---: | ---: |
| HAI-14 gốc | 53,13% | 5/8 | 7/12 |
| Thêm mô tả đồng phục | 75,00% | 7/8 | 7/12 |
| Thêm mô tả ảnh ngoài catalog | 53,13% | 5/8 | 12/12 |
| Thêm cả hai bộ mô tả | 75,00% | 7/8 | 12/12 |
| Cấu hình chọn từ calibration | 53,13% | 5/8 | 11/12 |

Bộ ảnh mới có tám ca bộ bóng đá và mười hai ca ngoài catalog. Đối chiếu với 639 ảnh tham chiếu qua SHA-256, trang nguồn, chuỗi nguồn và dHash cho kết quả 0 ca trùng chưa xử lý. [Hồ sơ ảnh, nguồn và giấy phép](../../datasets/image-search/dev-v4-focus/README.md)

## Quyết định

Chưa khóa cấu hình mới. Cấu hình kết hợp cải thiện hai nhóm mục tiêu trên ảnh mới nhưng độ chính xác phân nhóm trên DEV đủ nhóm xuống 88,64%, dưới mốc 90%. Cấu hình chọn từ calibration cải thiện từ chối ảnh ngoài catalog nhưng vẫn nhầm ba ca đồng phục với áo trong bộ ảnh mới. Vòng tiếp theo cần calibration riêng để điều chỉnh tín hiệu đồng phục và phát hiện ngoài catalog, với nhóm áo và phụ kiện làm đối chứng, rồi kiểm tra trên một bộ DEV mới đủ sáu nhóm. HAI-16 vẫn `BLOCKED / NO-GO`.
