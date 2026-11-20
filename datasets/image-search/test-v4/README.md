# Quy trình nghiệm thu TEST v4

**Trạng thái ngày 20/11/2026:** DEV v4.2 đã khóa cấu hình. Danh sách nguồn sơ bộ có 47 ảnh đã sàng lọc, gồm 6 áo, 9 quần, 6 đồng phục, 4 giày, 2 phụ kiện và 20 ảnh ngoài catalog. Các nhóm sản phẩm cần bổ sung 53 ảnh để đạt tối thiểu 100 ảnh. Wikimedia Commons trả HTTP 429 trong đợt thu thập bổ sung, tập trung ở truy vấn về giày, phụ kiện và đồng phục. Hồ sơ duyệt độc lập sẽ được tạo khi bộ ảnh đủ số lượng. [Danh sách nguồn sơ bộ](source-inventory.json)

Chọn ít nhất 100 ảnh truy vấn độc lập, bao phủ áo, quần, đồng phục, giày, phụ kiện và ảnh ngoài catalog. Trong nhóm quần và đồng phục, lấy cả ảnh cắt sát một món và ảnh thấy đủ áo quần. Nhóm phụ kiện cần bao phủ nhiều loại trong catalog. Nhóm ngoài catalog cần có cả cảnh và vật dụng dễ bị nhầm. Chọn ảnh, vùng cắt và nhãn sản phẩm tương đồng trước khi xem kết quả mô hình. Trình dữ liệu kiểm tra ảnh, trang nguồn và chuỗi nguồn với catalog cùng toàn bộ các bộ DEV/TEST trước.

Ghi ảnh và hồ sơ nguồn vào `assets/` và `source-inventory.json`, dùng cấu trúc của TEST v3 với phiên bản `4.0.0` và `partition` là `TEST` cho từng ảnh. Từ thư mục gốc, tạo hồ sơ duyệt và ảnh xem trước.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --test-v4 --write --contact-sheets
```

Thành viên dự án độc lập kiểm tra nhãn, nguồn, phân bố và các loạt ảnh có liên hệ. Sau khi có xác nhận thực tế, ghi `review-approval.json` gồm `status: APPROVED`, `independent: true`, `reviewer`, `reviewedAt` và `bindings` từ `review-packet.json`. Khóa bộ dữ liệu bằng lệnh sau.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --test-v4 --freeze
```

Từ `services/search-service`, chạy bằng môi trường chứa model SigLIP2 đã cache và đúng phiên bản Torch/Transformers của HAI-14.

```powershell
$env:OMP_NUM_THREADS = '4'
$env:MKL_NUM_THREADS = '4'
python -m scripts.benchmark_test_v4 --final
```

Runner đối chiếu lại 24 kết quả DEV qua HTTP nội bộ trước khi ghi dấu bắt đầu lần chạy chính thức. Ngưỡng TEST gồm Recall@5 ≥ 80%, phân nhóm ≥ 90%, từ chối ảnh ngoài catalog ≥ 80%, lỗi request 0%, p95 ≤ 3 giây và 0 ca rò rỉ chưa xử lý. Kết quả ghi ở `evidence/image-search-v4/test-v4-final/`. Giữ kết quả lần chạy để đối chiếu và dùng bộ DEV mới nếu cần điều chỉnh sau đó. [Cấu hình DEV đã khóa](../dev-v4-garment/locked-profile.json)
