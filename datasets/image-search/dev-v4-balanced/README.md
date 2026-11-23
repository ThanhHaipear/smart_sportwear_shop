# DEV v4.1 đủ sáu nhóm

Bộ gồm 42 ảnh Wikimedia Commons được chọn và kiểm tra vùng cắt trước khi chạy mô hình. Calibration có 18 ảnh, mỗi nhóm 3. DEV-check có 24 ảnh, mỗi nhóm 4. Mỗi ảnh có mục đích truy vấn riêng, nhãn tương đồng kiểu dáng ở catalog và hồ sơ nguồn. [Danh sách nguồn và giấy phép](source-inventory.json)

| Nhóm | Nội dung chính |
| --- | --- |
| Áo | Áo tập nhìn từ thân trên và áo đấu chụp rời |
| Quần | Quần đùi thể thao chụp rời hoặc crop theo vùng quần |
| Đồng phục | Bộ bóng đá hiện cả áo và quần, gồm ảnh cá nhân và ảnh đội |
| Giày | Giày chạy bộ và giày bóng đá |
| Phụ kiện | Tất, bảo vệ chân và bình nước. Bốn ảnh DEV-check đều là bình nước cầm trong bối cảnh thể thao |
| Ngoài catalog | Cảnh phố, cốc và ba lô |

Trình xác minh kiểm tra SHA-256, trang nguồn, chuỗi nguồn và dHash với catalog và các bộ ảnh trước đó. Mỗi ảnh tải được pin bằng SHA-256, và manifest lưu tọa độ crop cho ảnh có cắt. Dùng tệp nguồn để kiểm tra giấy phép của từng ảnh khi tái sử dụng. [Báo cáo trùng lặp](duplicate-report.json)

Từ thư mục gốc, xác minh bộ ảnh đã có hoặc tải lại ảnh thiếu.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --dev-v4-balanced
python datasets/image-search/dev-v3/build_dataset.py --dev-v4-balanced --download-missing
```

Từ `services/search-service`, dùng môi trường có model SigLIP2 đã cache và đúng phiên bản Torch và Transformers của HAI-14.

```powershell
$env:HF_HUB_OFFLINE = '1'
$env:TRANSFORMERS_OFFLINE = '1'
python -m scripts.evaluate_dev_v4_balanced --output ../../tmp/dev-v4-balanced-replay.json
```

Runner chọn tham số trên 68 ảnh calibration, gồm 50 ảnh từ DEV v3 và 18 ảnh mới. Ba cấu hình so sánh được cố định trước khi suy luận 24 ảnh DEV-check. Các tệp TEST trước chỉ tham gia kiểm tra trùng ảnh và nguồn.
