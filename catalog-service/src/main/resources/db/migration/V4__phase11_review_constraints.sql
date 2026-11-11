set search_path=catalog,pg_catalog;
create unique index if not exists uq_catalog_review_user_product on product_reviews(user_ref,product_id);
create index if not exists idx_catalog_reviews_public on product_reviews(product_id,created_at desc) where status='APPROVED';
