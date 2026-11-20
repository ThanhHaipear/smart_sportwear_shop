# Quy trình nghiệm thu TEST v4

**Trạng thái ngày 20/11/2026:** Đã chuẩn bị 100 ảnh ứng viên, gồm 16 ảnh mỗi nhóm áo, quần, đồng phục, giày, phụ kiện và 20 ảnh ngoài catalog. Hồ sơ đang ở trạng thái `REVIEW_REQUIRED`. [Hồ sơ duyệt](review-packet.json) · [Nhãn](labels.json) · [Nguồn và giấy phép](source-inventory.json) · [Báo cáo trùng lặp](duplicate-report.json)

Ảnh xem trước trên workspace phục vụ người duyệt đối chiếu nhãn và vùng cắt theo mã ảnh. [Áo](assets/_review/tops.jpg) · [Quần](assets/_review/bottoms.jpg) · [Đồng phục](assets/_review/uniforms.jpg) · [Giày](assets/_review/shoes.jpg) · [Phụ kiện](assets/_review/accessories.jpg) · [Ngoài catalog](assets/_review/unknown.jpg)

Chọn ít nhất 100 ảnh truy vấn độc lập, bao phủ áo, quần, đồng phục, giày, phụ kiện và ảnh ngoài catalog. Trong nhóm quần và đồng phục, lấy cả ảnh cắt sát một món và ảnh thấy đủ áo quần. Nhóm phụ kiện cần bao phủ nhiều loại trong catalog. Nhóm ngoài catalog cần có cả cảnh và vật dụng dễ bị nhầm. Chọn ảnh, vùng cắt và nhãn sản phẩm tương đồng trước khi xem kết quả mô hình. Trình dữ liệu kiểm tra ảnh, trang nguồn và chuỗi nguồn với catalog cùng toàn bộ các bộ DEV/TEST trước.

Từ thư mục gốc, xác minh hồ sơ hiện có và tạo ảnh xem trước. Thêm `--download-missing` khi cần phục hồi ảnh theo nguồn và checksum đã ghi. Trình kiểm tra cần các ảnh tham chiếu của catalog và các bộ trước để đối chiếu trùng lặp.

```powershell
python datasets/image-search/dev-v3/build_dataset.py --test-v4 --contact-sheets
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
