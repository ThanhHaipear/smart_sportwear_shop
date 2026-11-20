# Kết quả DEV v4.2

Vòng này bổ sung mô tả riêng cho ảnh áo hoặc quần chụp cận và bốn nhóm phụ kiện. Khi tín hiệu ảnh chụp riêng mạnh hơn tín hiệu bộ đầy đủ, cấu hình có thể giảm điểm hỗ trợ nhóm đồng phục. Tất cả cấu hình dùng chung SigLIP2 768 chiều, exact cosine và snapshot 85 vector của HAI-14.

Runner thử 72 tổ hợp trên 171 ảnh DEV v3/v4 đã có. Sau khi chọn cấu hình, runner chấm 24 ảnh DEV mới, bốn ảnh mỗi nhóm. Hồ sơ kiểm tra trùng với 701 ảnh tham chiếu ghi 0 ca chưa xử lý. [Bộ dữ liệu](../../datasets/image-search/dev-v4-garment/README.md) · [Kết quả từng ảnh](dev-garment-evaluation-2026-11-20.json)

| Cấu hình trên 24 ảnh DEV mới | Recall@5 | Phân nhóm đúng | Từ chối ảnh ngoài catalog |
| --- | ---: | ---: | ---: |
| HAI-14 gốc | 81,25% | 85% (17/20) | 75% (3/4) |
| Đầy đủ mô tả DEV v4.1 | 86,25% | 90% (18/20) | 100% (4/4) |
| DEV v4.2 chọn từ tập hiệu chỉnh | 86,25% | 90% (18/20) | 100% (4/4) |
| Ngưỡng DEV | 80% | 90% | 80% |

Cấu hình DEV v4.2 cũng đạt Recall@5 **84,19%**, phân nhóm **93,65%** và từ chối ảnh ngoài catalog **100%** trên tập hiệu chỉnh. Trên ảnh mới, cả áo, giày và phụ kiện đạt 4/4 ca phân nhóm. Hai ca sai gồm crop quần của bộ Croatia `dev4g-007` và đồng phục tập nữ `dev4g-012`, đều trả áo ở hạng đầu. Cấu hình DEV v4.1 cho cùng kết quả tổng hợp trên bộ nhỏ này.

**Quyết định:** Khóa cấu hình chọn từ tập hiệu chỉnh cho TEST v4. Mỗi nhóm chỉ có bốn ảnh và kết quả phân nhóm vừa chạm ngưỡng. HAI-16 tiếp tục `BLOCKED / NO-GO` trong khi chuẩn bị bộ TEST độc lập. [Cấu hình đã khóa](../../datasets/image-search/dev-v4-garment/locked-profile.json) · [Quy trình TEST v4](../../datasets/image-search/test-v4/README.md)

Chạy lại 24 ảnh DEV qua Search API nội bộ bằng HTTP loopback cho thứ tự top 5 khớp 24/24, 0 lỗi request và p95 258,458 ms. [Kết quả kiểm tra API](dev-garment-preflight-2026-11-20/metrics.json)
