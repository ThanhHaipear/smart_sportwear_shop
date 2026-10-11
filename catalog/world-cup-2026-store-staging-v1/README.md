# World Cup 2026 official-store reference catalog

Dataset gồm ảnh áo đấu đội tuyển mùa 2026 lấy từ trang sản phẩm chính thức của Nike/Adidas, dùng cho đồ án cá nhân phi thương mại. Đây là ảnh sản phẩm trên store, không phải ảnh cầu thủ thi đấu và không phải ảnh AI tạo.

## Kết quả đã nhập

- 6 sản phẩm: Argentina, Bỉ, Đức nữ (Adidas); Brazil, Pháp, Anh (Nike).
- 30 variant: mỗi sản phẩm có size S–XXL, giá và tồn kho mô phỏng cho giao diện đồ án.
- 21 ảnh đã chuẩn hóa JPEG sRGB `1200×1200`, giữ đúng tỉ lệ bằng `object-contain`, không kéo giãn và không upscale ảnh nguồn.
- 21 ảnh đã upload Cloudinary và liên kết Supabase; cả 6 sản phẩm ở trạng thái `ACTIVE`.
- Dataset `world-cup-2026-official-store-reference:1.0.0` đã `FROZEN`, ID `89485e38-b361-4a5a-99ad-c0eed354c046`.

## Quy tắc nguồn và phạm vi sử dụng

1. `dataSource=REAL` nghĩa là mẫu/mã sản phẩm có thật; giá VND và tồn kho vẫn là dữ liệu mô phỏng của đồ án (`PROJECT_DEMO_VND`, `PROJECT_DEMO_STOCK`).
2. Ảnh dùng `sourceKind=REFERENCE` và `licenseCode=NONCOMMERCIAL_EDUCATIONAL_REFERENCE`. Giá trị này không tuyên bố shop sở hữu ảnh, được hãng cấp phép thương mại hoặc là đại lý chính hãng.
3. Mỗi ảnh giữ URL CDN chính thức trong `source_uri`, cùng SHA-256, kích thước, định dạng, Cloudinary public ID và asset version để truy vết.
4. Chỉ dùng bộ này cho demo/học tập cá nhân. Nếu triển khai công khai hoặc kinh doanh, phải thay bằng ảnh tự chụp/được cấp phép và chuyển provenance sang `OWNED` hoặc `LICENSED` với bằng chứng phù hợp.
5. Ảnh benchmark truy vấn phải độc lập với ảnh catalog; không dùng lại cùng checksum/source group để tránh điểm đánh giá tìm kiếm ảnh bị ảo.

## Chất lượng ảnh cho giao diện và AI

- Bản Cloudinary gốc của dataset là JPEG vuông `1200×1200`, chất lượng 92; tổng 21 ảnh khoảng 3,8 MB, ảnh lớn nhất dưới 0,5 MB.
- Giao diện dùng khung vuông, `object-contain`, responsive và không yêu cầu kích thước lớn hơn 1200 px.
- SigLIP2 dùng URL Cloudinary có version trong `import-result.json`, xác minh checksum trước khi tạo embedding; không dùng thumbnail đã biến đổi.
- Không sửa pixel của dataset đã `FROZEN`. Khi thay ảnh phải tạo version dataset mới để vector và kết quả đánh giá cũ còn tái lập được.

## Quy trình thêm ảnh sau này

1. Thêm URL trang sản phẩm và URL ảnh vào `sources.json`.
2. Chạy `download-assets.ps1` để tải và kiểm tra ảnh nguồn.
3. Chạy `prepare-import-assets.ps1` để chuẩn hóa ảnh và sinh `manifest.json` có checksum.
4. Review thủ công ảnh chính, nhãn đội tuyển, thương hiệu, màu và mã hãng.
5. Chạy importer; sản phẩm luôn được tạo `DRAFT`, chỉ chuyển `ACTIVE` sau khi có variant bán được và ảnh chính hợp lệ.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\catalog\world-cup-2026-store-staging-v1\download-assets.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\catalog\world-cup-2026-store-staging-v1\prepare-import-assets.ps1

Set-Location .\backend
.\mvnw.cmd spring-boot:run `
  "-Dspring-boot.run.main-class=com.dunghaiquyen.ecommerce.modules.catalog.importer.CatalogPilotImportCommand" `
  "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false" `
  "-Dspring-boot.run.arguments=../catalog/world-cup-2026-store-staging-v1/manifest.json"
```

Ảnh local trong `assets/` và `import-assets/` không commit. `sources.json`, `assets-manifest.json`, `manifest.json` và `import-result.json` là bằng chứng tái lập được lưu trong Git.
