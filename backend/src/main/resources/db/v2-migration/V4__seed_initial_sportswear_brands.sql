set search_path = public;

-- Hai thương hiệu ban đầu của cửa hàng. NO_BRAND vẫn được giữ cho hàng không nhãn hiệu.
-- Seed theo code để chạy ổn định ở mọi môi trường; sản phẩm đã tham chiếu brand không bị xóa.
insert into brands (code, name, slug, description, status)
values
    ('NIKE', 'Nike', 'nike', 'Thương hiệu thể thao Nike.', 'ACTIVE'),
    ('ADIDAS', 'Adidas', 'adidas', 'Thương hiệu thể thao Adidas.', 'ACTIVE')
on conflict (code) do update
set name = excluded.name,
    slug = excluded.slug,
    description = excluded.description,
    status = excluded.status;
