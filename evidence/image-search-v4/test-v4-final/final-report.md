# Kết quả nghiệm thu TEST v4

**Ngày chạy:** 20/11/2026

**Quyết định nghiệm thu:** **PASS**

Bộ `image-search-test-v4:4.0.0` gồm 100 ảnh được khóa sau khi người dùng chuyển xác nhận duyệt độc lập của Dũng. Benchmark chính thức chạy một lần qua Search API nội bộ bằng HTTP loopback, với cấu hình DEV v4.2 đã khóa và snapshot cục bộ 85 vector. Kiểm tra trước khi chạy cho thứ tự top 5 khớp 24/24 ảnh DEV. [Hồ sơ duyệt](../../../datasets/image-search/test-v4/review-approval.json) · [Cấu hình lần chạy](benchmark-config.json)

| Chỉ số | Kết quả | Ngưỡng | Đánh giá |
| --- | ---: | ---: | --- |
| Recall@5 | 87,92% | ≥ 80% | Đạt |
| Phân loại đúng nhóm | 93,75% (75/80) | ≥ 90% | Đạt |
| Từ chối ảnh ngoài catalog | 95% (19/20) | ≥ 80% | Đạt |
| Lỗi request | 0% (0/100) | 0% | Đạt |
| Độ trễ p95 | 241,561 ms | ≤ 3.000 ms | Đạt |
| Ca rò rỉ chưa xử lý | 0 | 0 | Đạt |

## Phân tích theo nhóm

| Nhóm | Recall@5 | Phân nhóm đúng |
| --- | ---: | ---: |
| Áo | 80,21% | 16/16 |
| Quần | 100% | 16/16 |
| Đồng phục | 84,38% | 15/16 |
| Giày | 93,75% | 15/16 |
| Phụ kiện | 81,25% | 13/16 |

Hai ảnh tất trả đồng phục ở hạng đầu và một ảnh bảo vệ ống chân bị từ chối. Ca ngoài catalog duy nhất trả kết quả là vòng silicone `test4-046`, được xếp vào phụ kiện. [Chỉ số chi tiết](metrics.json) · [Kết quả từng ảnh](ranked-results.json)

## Điều kiện mở thử nghiệm

HAI-16 đã qua ngưỡng chất lượng của benchmark. Tiếp theo là tích hợp cấu hình đã nghiệm thu vào Search Service hiện hành, đối chiếu dữ liệu index dùng để phục vụ, rồi kiểm tra luồng Storefront → Gateway → Catalog → Search trước khi chạy shadow và mở cho nhóm người dùng nhỏ. Endpoint Catalog công khai hiện trả HTTP 503.

Tên Dũng trong hai tệp JSON do runner ghi lúc chạy xuất hiện dưới dạng `D?ng` vì lỗi mã hóa khi tạo hồ sơ. Tên đã được sửa trong hồ sơ duyệt. Các checksum ràng buộc, mốc giờ và kết quả thực thi vẫn khớp với lần chạy. [Dấu bắt đầu lần chạy](run-started.json)
