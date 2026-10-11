# Catalog pilot v1

Catalog này là dữ liệu demo có phiên bản để kiểm thử giao diện, tìm kiếm văn bản và tìm kiếm hình ảnh. Bản `smart-sportswear-pilot:1.0.0` đã được nhập lên Supabase/Cloudinary ngày 11/10/2026 và đã đóng băng.

## Kết quả

- Dataset ID: `bfb1508a-804a-473a-b331-5b1e71a3a109`.
- 30 sản phẩm `ACTIVE`: 8 áo, 6 quần, 6 đôi giày, 6 bộ đồng phục bóng đá và 4 phụ kiện.
- 148 variant có size, màu, giá và tồn kho.
- 60 ảnh, đúng 2 ảnh mỗi sản phẩm; ảnh chính là góc ba phần tư, ảnh phụ là góc sau/đối diện.
- Phân bổ giới tính: 9 nam, 9 nữ, 12 unisex. Phân bổ môn thể thao: 14 running, 16 football.
- Tất cả sản phẩm mang `data_source=DEMO`; ảnh mang `source_kind=SYNTHETIC` và `license_code=AI_GENERATED_PROJECT_USE`.

## Rule dữ liệu và ảnh đã chốt

1. Mỗi mã sản phẩm, slug, SKU và tên file ảnh phải ổn định, không tái sử dụng cho nội dung khác.
2. Bản gốc là PNG sRGB vuông `1254 x 1254`, dung lượng từ 100 KB đến 5 MB, có SHA-256 trong manifest. Không chứa người, logo, nhãn hiệu, chữ hoặc watermark.
3. Cloudinary giữ bản gốc có version. Database lưu `public_id`, `asset_version`, versioned `secure_url`, checksum, kích thước, định dạng, dung lượng, nguồn và giấy phép; không lưu binary ảnh trong PostgreSQL.
4. SigLIP2 chỉ tải **versioned original** từ Cloudinary và xác minh checksum trước khi tạo embedding. Không dùng URL thumbnail, ảnh nén hoặc transformation của giao diện làm nguồn embedding.
5. Giao diện hiển thị ảnh trong khung vuông, `object-contain`, responsive và không phóng lớn quá `1254 x 1254`. Cloudinary/Next Image có thể sinh bản giao diện nhỏ hơn nhưng không thay đổi asset gốc.
6. Sản phẩm được tạo ở `DRAFT`; chỉ chuyển `ACTIVE` sau khi có ít nhất một variant `ACTIVE`, tồn kho dương và một ảnh chính đủ checksum/kích thước/định dạng/provenance. Importer hiện yêu cầu 2–4 ảnh và đúng một ảnh chính cho mỗi sản phẩm.
7. Dataset đi theo vòng đời `DRAFT → VALIDATED → FROZEN`. Không sửa bản `1.0.0` đã đóng băng; thay đổi nội dung hoặc asset phải tạo phiên bản dataset mới.
8. Đây là ảnh synthetic cho demo/nghiên cứu, không được mô tả là ảnh hàng thật. Catalog thực tế sau này phải dùng ảnh `OWNED` hoặc `LICENSED` với bằng chứng quyền sử dụng tương ứng.

Các rule trên vừa bảo vệ chất lượng hiển thị, vừa giữ ảnh đủ ổn định và tái lập cho image embedding/evaluation.

## Artifact

- `manifest.json`: nguồn chuẩn cho sản phẩm, variant, ảnh và policy.
- `assets.sha256`: checksum độc lập của 60 ảnh gốc.
- `import-result.json`: ID trong Supabase và metadata Cloudinary sau import.
- `image-prompts.md`: cách tạo ảnh và quy tắc nhất quán hình ảnh.
- `build-manifest.ps1`: tạo lại manifest/checksum từ catalog đã duyệt.
- `assets/`: ảnh nguồn local khoảng 120 MB, được bỏ khỏi Git; bản runtime nằm trên Cloudinary.

Không xóa asset Cloudinary chỉ vì thư mục local không được commit. Trước khi thay asset phải tạo backup hoặc dataset version mới để các vector và kết quả đánh giá cũ còn tái lập được.

## Tạo lại manifest

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\catalog\pilot-v1\build-manifest.ps1
```

## Nhập catalog

Nạp biến môi trường backend/Cloudinary rồi chạy command one-shot từ thư mục `backend`:

```powershell
.\mvnw.cmd spring-boot:run `
  "-Dspring-boot.run.main-class=com.dunghaiquyen.ecommerce.modules.catalog.importer.CatalogPilotImportCommand" `
  "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false" `
  "-Dspring-boot.run.arguments=../catalog/pilot-v1/manifest.json"
```

Importer kiểm tra allocation, variant, checksum, kích thước ảnh và provenance trước khi upload. Lệnh có thể chạy lại an toàn: dataset đã `FROZEN` và khớp checksum sẽ chỉ tái tạo báo cáo, không tạo sản phẩm hoặc ảnh trùng.
