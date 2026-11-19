# Image Search TEST v3

Phiên bản `image-search-test-v3:3.0.0` đã khóa ở trạng thái `FROZEN` ngày
19/11/2026 sau khi người dùng chuyển xác nhận duyệt của Quyền (QuyenNDD).
Hồ sơ duyệt gắn với đúng bộ checksum tại [review-approval.json](review-approval.json).
Bộ dữ liệu gồm 90 ảnh Wikimedia Commons được chọn theo nội dung và giấy phép
trước khi chạy model trên TEST.

| Áo | Quần | Đồng phục | Giày | Phụ kiện | Unknown |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 13 | 15 | 13 | 17 | 10 | 22 |

`manifest.json` mô tả ảnh và vùng cắt. `labels.json` ghi nhóm kỳ vọng và
sản phẩm catalog có kiểu dáng tương thích. Phụ kiện chủ yếu là ảnh cắt từ
bối cảnh sử dụng. Nhóm unknown có cả vật dụng ngoài catalog trông gần với
đồ thể thao, chẳng hạn ba lô.

`source-inventory.json` lưu nguồn và giấy phép từng ảnh. Kiểm tra tự động
đối chiếu SHA-256, trang nguồn, chuỗi nguồn và dHash với 549 ảnh catalog,
DEV và TEST trước đây. Các ảnh được đối chiếu với nhau ngay trong bộ mới.
Người duyệt kiểm tra thêm các loạt ảnh có liên hệ dù tên tệp khác nhau.

## Review độc lập

Từ thư mục gốc, dùng Python có Pillow để kiểm tra bộ ảnh và tạo ảnh xem trước
trong `assets/_review`. Có thể thêm `--download-missing` để khôi phục các ảnh
đã chốt trong hồ sơ nguồn vì Git bỏ qua các tệp ảnh.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --test-v3 --contact-sheets
```

Đối chiếu ảnh với nhãn, nguồn, giấy phép, phân bố nhóm và báo cáo trùng lặp.
Khi thành viên độc lập chấp thuận, ghi xác nhận thực tế vào `review-approval.json`.
Tệp cần có `status` là `APPROVED`, `independent` là `true`, `reviewer` là tên
người duyệt, `reviewedAt` là thời điểm duyệt theo ISO 8601 và `bindings` sao
chép nguyên từ `review-packet.json`. Các checksum của tệp văn bản trong
`bindings` dùng UTF-8 với xuống dòng LF.

## Lệnh dùng trong lần khóa và chạy chính thức

Lần chạy chính thức dùng môi trường Python chứa model SigLIP2 đã cache và
đúng phiên bản Torch/Transformers trong profile HAI-14. Dùng lệnh kiểm tra ảnh
ở trên khi cần xác minh bộ dữ liệu. Đối chiếu kết quả qua evidence đã lưu.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --test-v3 --freeze
$env:PYTHONPATH = (Resolve-Path 'services/search-service').Path
$env:OMP_NUM_THREADS = '4'
$env:MKL_NUM_THREADS = '4'
python -m scripts.benchmark_test_v3 --final
```

Runner gửi ảnh qua HTTP loopback vào endpoint Search nội bộ và tính hạng
từ snapshot 85 vector đã khóa. URL ảnh kết quả mang định danh snapshot.
Độ trễ đo được thuộc lần chạy loopback trên máy benchmark. Runner dùng DEV
để warm up, ghi `run-started.json` ngay trước request TEST đầu tiên, và giữ
bằng chứng tại `evidence/image-search-v3/test-v3-final`.

Lần chạy ngày 19/11/2026 đã hoàn tất với kết quả **FAILED / NO-GO**. Giữ bộ TEST
v3 và kết quả đã khóa để đối chiếu. [Báo cáo chính thức](../../../evidence/image-search-v3/test-v3-final/final-report.md)
