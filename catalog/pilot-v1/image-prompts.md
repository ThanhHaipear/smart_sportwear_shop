# Image generation record

60 ảnh trong catalog pilot được tạo bằng **OpenAI built-in imagegen** ngày 11/10/2026. Mỗi sản phẩm có một ảnh chính mới và một ảnh phụ được tạo bằng cách tham chiếu ảnh chính để giữ nguyên thiết kế, màu và chất liệu.

## Prompt nền cho ảnh chính

> Photorealistic ecommerce product catalog image for a sportswear and visual-search dataset. Show the complete specified product alone, centered on a clean light-neutral studio background, square composition, soft diffused lighting, sharp textile/material detail, realistic construction, consistent color, generous margin, no crop, no person, no mannequin, no hanger, no brand, no logo, no text and no watermark. Primary three-quarter view.

Phần mô tả riêng của từng sản phẩm được lấy từ `manifest.json`: loại sản phẩm, màu, môn thể thao, giới tính, chất liệu và đặc điểm nhận diện. Với đồng phục, prompt luôn yêu cầu áo và quần xuất hiện cùng nhau vì một SKU là một bộ nguyên vẹn.

## Prompt nền cho ảnh phụ

> Preserve exactly the same fictional product design, color palette, construction, trim and materials. Create a second ecommerce catalog view from the rear or opposite three-quarter angle, showing the complete product on the same light-neutral background with the same scale and lighting. No person, brand, logo, text or watermark.

## Quy tắc tái tạo

- Không tạo thêm góc ảnh bằng prompt độc lập nếu cần khẳng định hai ảnh là cùng một sản phẩm; phải dùng ảnh chính làm reference/edit.
- Mỗi asset được gắn `source_group_code` theo mã sản phẩm và thứ tự ảnh.
- Mọi lần tái tạo làm thay đổi pixel sẽ có SHA-256 mới và phải thuộc dataset version mới; không ghi đè asset của bản đã `FROZEN`.
- Prompt là provenance mô tả quy trình, không thay thế checksum, model/tool, ngày tạo, source URI và license trong manifest/database.
