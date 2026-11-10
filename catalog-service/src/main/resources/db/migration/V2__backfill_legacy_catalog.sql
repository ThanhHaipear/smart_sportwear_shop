-- Idempotent copy from the legacy public schema. Legacy tables remain intact for rollback.
do $$ begin
if to_regclass('public.categories') is not null then
  insert into catalog.categories(id,parent_id,code,name,slug,description,status,sort_order,created_at,updated_at)
  select id,parent_id,code,name,slug,description,status,sort_order,created_at,updated_at from public.categories on conflict(id) do nothing;
end if;
if to_regclass('public.brands') is not null then
  insert into catalog.brands(id,code,name,slug,description,status,created_at,updated_at)
  select id,code,name,slug,description,status,created_at,updated_at from public.brands on conflict(id) do nothing;
end if;
if to_regclass('public.products') is not null then
  insert into catalog.products(id,category_id,brand_id,name,slug,description,attributes,status,version,created_at,updated_at)
  select id,category_id,brand_id,name,slug,description,attributes,status,catalog_version,created_at,updated_at from public.products on conflict(id) do nothing;
end if;
if to_regclass('public.product_variants') is not null then
  insert into catalog.product_variants(id,product_id,sku,size_label,color_label,price,compare_at_price,stock_quantity,reserved_quantity,reorder_point,status,version,created_at,updated_at)
  select v.id,v.product_id,v.sku,s.display_name,c.name,v.price,v.compare_at_price,v.stock_quantity,v.reserved_quantity,v.reorder_point,v.status,v.version,v.created_at,v.updated_at
    from public.product_variants v join public.sizes s on s.id=v.size_id join public.colors c on c.id=v.color_id on conflict(id) do nothing;
end if;
if to_regclass('public.product_images') is not null then
  insert into catalog.product_images(id,product_id,variant_id,secure_url,alt_text,sort_order,is_primary,created_at)
  select id,product_id,variant_id,secure_url,alt_text,sort_order,is_primary,created_at from public.product_images on conflict(id) do nothing;
end if;
if to_regclass('public.wishlists') is not null then
  insert into catalog.wishlists(id,user_ref,created_at,updated_at)
  select id,user_id,created_at,updated_at from public.wishlists on conflict(id) do nothing;
end if;
if to_regclass('public.wishlist_items') is not null then
  insert into catalog.wishlist_items(wishlist_id,product_id,created_at)
  select wishlist_id,product_id,created_at from public.wishlist_items on conflict(wishlist_id,product_id) do nothing;
end if;
if to_regclass('public.product_reviews') is not null then
  insert into catalog.product_reviews(id,product_id,user_ref,order_item_ref,rating,title,content,status,created_at,updated_at)
  select id,product_id,user_id,order_item_id,rating,title,content,status,created_at,updated_at from public.product_reviews on conflict(id) do nothing;
end if;
if to_regclass('public.promotions') is not null then
  insert into catalog.promotions(id,name,slug,description,type,scope,discount_percent,discount_amount,max_discount_amount,min_order_amount,starts_at,ends_at,status,usage_count,usage_limit,created_by,created_at,updated_at)
  select id,name,slug,description,type,scope,discount_percent,discount_amount,max_discount_amount,min_order_amount,starts_at,ends_at,status,usage_count,usage_limit,created_by,created_at,updated_at
    from public.promotions on conflict(id) do nothing;
end if;
if to_regclass('public.promotion_products') is not null then
  insert into catalog.promotion_products(promotion_id,product_id)
  select promotion_id,product_id from public.promotion_products on conflict(promotion_id,product_id) do nothing;
end if;
end $$;
