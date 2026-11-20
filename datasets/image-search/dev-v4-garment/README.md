# DEV v4.2 kiểm tra áo, quần và đồng phục

Bộ kiểm tra có 24 ảnh Wikimedia Commons, mỗi nhóm 4 ảnh. Ảnh áo và quần gồm ảnh chụp riêng và ảnh cắt theo món đồ cần tìm. Ảnh đồng phục thấy cả áo và quần. Nhóm phụ kiện gồm một bình nước thể thao, một ảnh tất thể thao và hai ảnh bảo vệ ống chân trong bóng chày. Nhãn sản phẩm thể hiện món tương đồng trong catalog. [Nguồn, giấy phép và vùng cắt](source-inventory.json)

Toàn bộ ảnh thuộc `DEV-check`. Việc chọn ảnh, vùng cắt và nhãn hoàn thành trước khi chạy mô hình. Trình xác minh đối chiếu SHA-256, trang nguồn, chuỗi nguồn và dHash với catalog, ảnh tham chiếu thử nghiệm và các bộ DEV/TEST trước. Các tập TEST chỉ tham gia kiểm tra trùng lặp. [Báo cáo trùng lặp](duplicate-report.json)

Từ thư mục gốc, chạy lệnh dưới đây để đối chiếu ảnh và hồ sơ. Có thể thêm `--download-missing` để khôi phục các ảnh thiếu từ nguồn đã ghi.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --dev-v4-garment
```

Runner chọn cấu hình trên các bộ DEV v3/v4 đã có, sau đó mới suy luận 24 ảnh của bộ này. Chạy từ `services/search-service` với đúng phiên bản Torch, Transformers và model SigLIP2 đã cache của HAI-14.

```powershell
$env:HF_HUB_OFFLINE = '1'
$env:TRANSFORMERS_OFFLINE = '1'
$env:OMP_NUM_THREADS = '4'
$env:MKL_NUM_THREADS = '4'
python -m scripts.evaluate_dev_v4_garment --output ../../tmp/dev-v4-garment-replay.json
```
