set search_path = public;

-- Dữ liệu tham chiếu tối thiểu cho catalog thể thao ở giai đoạn 1A.
-- Tất cả khóa nghiệp vụ đều ổn định để migration có thể được kiểm tra và seed lại an toàn.

insert into brands (code, name, slug, status)
values ('NO_BRAND', 'Không thương hiệu', 'khong-thuong-hieu', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    slug = excluded.slug,
    status = excluded.status;

insert into size_systems (code, name, status)
values
    ('APPAREL_INT', 'Quần áo quốc tế', 'ACTIVE'),
    ('SHOES_EU', 'Giày EU', 'ACTIVE'),
    ('ONE_SIZE', 'Một kích cỡ', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    status = excluded.status;

insert into sizes (size_system_id, code, display_name, sort_order)
select ss.id, seed.code, seed.label, seed.sort_order
from size_systems ss
join (values
    ('APPAREL_INT', 'XS', 'XS', 10),
    ('APPAREL_INT', 'S', 'S', 20),
    ('APPAREL_INT', 'M', 'M', 30),
    ('APPAREL_INT', 'L', 'L', 40),
    ('APPAREL_INT', 'XL', 'XL', 50),
    ('APPAREL_INT', 'XXL', 'XXL', 60),
    ('SHOES_EU', '36', '36', 360),
    ('SHOES_EU', '37', '37', 370),
    ('SHOES_EU', '38', '38', 380),
    ('SHOES_EU', '39', '39', 390),
    ('SHOES_EU', '40', '40', 400),
    ('SHOES_EU', '41', '41', 410),
    ('SHOES_EU', '42', '42', 420),
    ('SHOES_EU', '43', '43', 430),
    ('SHOES_EU', '44', '44', 440),
    ('SHOES_EU', '45', '45', 450),
    ('ONE_SIZE', 'ONE_SIZE', 'Một kích cỡ', 10)
) as seed(system_code, code, label, sort_order)
    on seed.system_code = ss.code
on conflict (size_system_id, code) do update
set display_name = excluded.display_name,
    sort_order = excluded.sort_order;

insert into product_types (code, name, default_size_system_id, status)
select seed.code, seed.name, ss.id, 'ACTIVE'
from (values
    ('SPORTS_TOP', 'Áo thể thao', 'APPAREL_INT'),
    ('SPORTS_BOTTOM', 'Quần thể thao', 'APPAREL_INT'),
    ('SPORTS_SHOES', 'Giày thể thao', 'SHOES_EU'),
    ('SPORTS_UNIFORM', 'Đồng phục thể thao nguyên bộ', 'APPAREL_INT'),
    ('SPORTS_ACCESSORY', 'Phụ kiện thể thao', 'ONE_SIZE')
) as seed(code, name, size_system_code)
join size_systems ss on ss.code = seed.size_system_code
on conflict (code) do update
set name = excluded.name,
    default_size_system_id = excluded.default_size_system_id,
    status = excluded.status;

insert into sports (code, name, status)
values
    ('FOOTBALL', 'Bóng đá', 'ACTIVE'),
    ('RUNNING', 'Chạy bộ', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    status = excluded.status;

insert into use_cases (code, name, status)
values
    ('TRAINING', 'Luyện tập', 'ACTIVE'),
    ('COMPETITION', 'Thi đấu', 'ACTIVE'),
    ('EVERYDAY', 'Mặc hằng ngày', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    status = excluded.status;

insert into materials (code, name, status)
values
    ('POLYESTER', 'Polyester', 'ACTIVE'),
    ('COTTON', 'Cotton', 'ACTIVE'),
    ('ELASTANE', 'Elastane', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    status = excluded.status;

insert into technologies (code, name, status)
values
    ('MOISTURE_WICKING', 'Thấm hút mồ hôi', 'ACTIVE'),
    ('BREATHABLE', 'Thoáng khí', 'ACTIVE'),
    ('WATER_RESISTANT', 'Kháng nước', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    status = excluded.status;

insert into colors (code, name, family_code, hex_value, status)
values
    ('BLACK', 'Đen', 'BLACK', '#000000', 'ACTIVE'),
    ('WHITE', 'Trắng', 'WHITE', '#FFFFFF', 'ACTIVE'),
    ('GRAY', 'Xám', 'GRAY', '#808080', 'ACTIVE'),
    ('RED', 'Đỏ', 'RED', '#FF0000', 'ACTIVE'),
    ('BLUE', 'Xanh dương', 'BLUE', '#0000FF', 'ACTIVE'),
    ('NAVY', 'Xanh navy', 'BLUE', '#000080', 'ACTIVE'),
    ('GREEN', 'Xanh lá', 'GREEN', '#008000', 'ACTIVE'),
    ('YELLOW', 'Vàng', 'YELLOW', '#FFFF00', 'ACTIVE'),
    ('ORANGE', 'Cam', 'ORANGE', '#FFA500', 'ACTIVE'),
    ('PINK', 'Hồng', 'PINK', '#FFC0CB', 'ACTIVE'),
    ('PURPLE', 'Tím', 'PURPLE', '#800080', 'ACTIVE'),
    ('BROWN', 'Nâu', 'BROWN', '#A52A2A', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    family_code = excluded.family_code,
    hex_value = excluded.hex_value,
    status = excluded.status;

insert into categories (code, name, slug, parent_id, sort_order, status)
values
    ('APPAREL', 'Trang phục', 'trang-phuc', null, 10, 'ACTIVE'),
    ('FOOTWEAR', 'Giày', 'giay', null, 20, 'ACTIVE'),
    ('ACCESSORIES', 'Phụ kiện', 'phu-kien', null, 30, 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    slug = excluded.slug,
    parent_id = excluded.parent_id,
    sort_order = excluded.sort_order,
    status = excluded.status;

insert into categories (code, name, slug, parent_id, sort_order, status)
select seed.code, seed.name, seed.slug, parent.id, seed.sort_order, 'ACTIVE'
from (values
    ('SPORTS_TOPS', 'Áo thể thao', 'ao-the-thao', 'APPAREL', 10),
    ('SPORTS_BOTTOMS', 'Quần thể thao', 'quan-the-thao', 'APPAREL', 20),
    ('SPORTS_UNIFORMS', 'Đồng phục thể thao', 'dong-phuc-the-thao', 'APPAREL', 30),
    ('SPORTS_SHOES_CATEGORY', 'Giày thể thao', 'giay-the-thao', 'FOOTWEAR', 10),
    ('SPORTS_ACCESSORIES', 'Phụ kiện thể thao', 'phu-kien-the-thao', 'ACCESSORIES', 10)
) as seed(code, name, slug, parent_code, sort_order)
join categories parent on parent.code = seed.parent_code
on conflict (code) do update
set name = excluded.name,
    slug = excluded.slug,
    parent_id = excluded.parent_id,
    sort_order = excluded.sort_order,
    status = excluded.status;
