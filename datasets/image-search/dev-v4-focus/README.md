# DEV v4 kiểm tra đồng phục và ảnh ngoài catalog

Bộ kiểm tra gồm 20 ảnh Wikimedia Commons được chọn theo nội dung trước khi chạy mô hình. Tám ảnh đồng phục có áo và quần của bộ bóng đá cùng xuất hiện, gồm bốn ca đội nam và bốn ca đội nữ. Mỗi ca có bốn nhãn sản phẩm thay thế theo nhóm nam hoặc nữ và unisex. Mười hai ảnh ngoài catalog gồm tám cảnh đường phố, chợ hoặc quảng trường và bốn ảnh cốc.

[Hồ sơ nguồn và giấy phép](source-inventory.json) pin trang nguồn, tác giả, checksum ảnh tải và checksum ảnh JPEG đã chuẩn hóa. [Báo cáo trùng lặp](duplicate-report.json) đối chiếu SHA-256, trang nguồn, chuỗi nguồn và dHash với catalog cùng các tập DEV/TEST trước đây. Các tập TEST chỉ được dùng làm tham chiếu chống trùng.

Bộ này đo lại đúng các cấu hình đã ghi trong kết quả calibration. Hai nhóm được lấy mẫu có chủ đích, vì vậy chỉ số tổng hợp của bộ này mô tả nhóm đồng phục và ảnh ngoài catalog. Kết quả đánh giá tiếp theo cần một bộ DEV mới có đủ sáu nhóm.

Từ thư mục gốc, xác minh ảnh đã có hoặc tải lại ảnh thiếu từ nguồn đã ghi.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --dev-v4-focus
python datasets/image-search/dev-v3/build_dataset.py --dev-v4-focus --download-missing
```

Từ `services/search-service`, chạy bằng môi trường có đúng phiên bản Torch và Transformers của profile HAI-14 và model đã cache. `--output` dùng đường dẫn mới để giữ hồ sơ các lần chạy.

```powershell
$env:HF_HUB_OFFLINE = '1'
$env:TRANSFORMERS_OFFLINE = '1'
python -m scripts.evaluate_dev_v4 --output ../../tmp/dev-v4-diagnostic.json
python -m scripts.evaluate_dev_v4 --check-fresh ../../tmp/dev-v4-diagnostic.json --output ../../tmp/dev-v4-focus.json
```
