do $$ begin
  if not exists(select 1 from pg_roles where rolname='catalog_owner') then create role catalog_owner nologin; end if;
  if not exists(select 1 from pg_roles where rolname='catalog_app') then create role catalog_app nologin; end if;
end $$;
create schema if not exists catalog authorization catalog_owner;
set role catalog_owner;
set search_path=catalog,pg_catalog;

create table if not exists categories (
  id uuid primary key default gen_random_uuid(), parent_id uuid references categories(id), code varchar(50) not null unique,
  name varchar(150) not null, slug varchar(180) not null unique, description text, status varchar(20) not null default 'ACTIVE',
  sort_order integer not null default 0, created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists brands (
  id uuid primary key default gen_random_uuid(), code varchar(50) not null unique, name varchar(150) not null,
  slug varchar(180) not null unique, description text, status varchar(20) not null default 'ACTIVE',
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists products (
  id uuid primary key default gen_random_uuid(), category_id uuid references categories(id), brand_id uuid references brands(id),
  name varchar(255) not null, slug varchar(300) not null unique, description text, attributes jsonb not null default '{}'::jsonb,
  status varchar(20) not null default 'DRAFT' check(status in ('DRAFT','ACTIVE','INACTIVE')),
  version bigint not null default 1 check(version>0), created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists product_variants (
  id uuid primary key default gen_random_uuid(), product_id uuid not null references products(id), sku varchar(100) not null unique,
  size_label varchar(100) not null, color_label varchar(150) not null, price numeric(14,2) not null check(price>0),
  compare_at_price numeric(14,2), stock_quantity integer not null default 0 check(stock_quantity>=0),
  reserved_quantity integer not null default 0 check(reserved_quantity>=0 and reserved_quantity<=stock_quantity),
  reorder_point integer not null default 0, status varchar(20) not null default 'ACTIVE', version bigint not null default 1,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create index if not exists idx_catalog_variants_product on product_variants(product_id,status);
create table if not exists product_images (
  id uuid primary key default gen_random_uuid(), product_id uuid not null references products(id), variant_id uuid references product_variants(id),
  secure_url text not null, alt_text varchar(255), sort_order integer not null default 0, is_primary boolean not null default false,
  created_at timestamptz not null default now()
);
create table if not exists promotions (
  id uuid primary key default gen_random_uuid(), name varchar(200) not null, slug varchar(200) not null unique, description text,
  type varchar(20) not null, scope varchar(20) not null, discount_percent numeric(8,2), discount_amount numeric(14,2),
  max_discount_amount numeric(14,2), min_order_amount numeric(14,2), starts_at timestamptz, ends_at timestamptz,
  status varchar(20) not null default 'DRAFT', usage_count integer not null default 0, usage_limit integer, created_by uuid,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists promotion_products (
  promotion_id uuid not null references promotions(id) on delete cascade, product_id uuid not null references products(id),
  primary key(promotion_id,product_id)
);
create table if not exists wishlists (
  id uuid primary key default gen_random_uuid(), user_ref uuid not null unique, created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists wishlist_items (
  wishlist_id uuid not null references wishlists(id) on delete cascade, product_id uuid not null references products(id),
  created_at timestamptz not null default now(), primary key(wishlist_id,product_id)
);
create table if not exists product_reviews (
  id uuid primary key default gen_random_uuid(), product_id uuid not null references products(id), user_ref uuid not null,
  order_item_ref uuid, rating integer not null check(rating between 1 and 5), title varchar(200), content text,
  status varchar(20) not null default 'PENDING', created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists inventory_transactions (
  id uuid primary key default gen_random_uuid(), variant_id uuid not null references product_variants(id), external_ref uuid,
  transaction_type varchar(40) not null, quantity integer not null, idempotency_key varchar(200) not null unique,
  created_at timestamptz not null default now()
);
create table if not exists inventory_reservations (
  id uuid primary key, order_ref uuid not null, idempotency_key varchar(200) not null unique,
  status varchar(20) not null check(status in ('ACTIVE','CONFIRMED','RELEASED','EXPIRED')),
  expires_at timestamptz not null, created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists inventory_reservation_items (
  reservation_id uuid not null references inventory_reservations(id) on delete cascade,
  variant_id uuid not null references product_variants(id), quantity integer not null check(quantity>0),
  primary key(reservation_id,variant_id)
);
create table if not exists integration_outbox (
  id uuid primary key default gen_random_uuid(), event_type varchar(100) not null, aggregate_type varchar(50) not null,
  aggregate_id uuid not null, aggregate_version bigint not null, payload jsonb not null,
  status varchar(20) not null default 'PENDING', attempts integer not null default 0,
  occurred_at timestamptz not null default now(), published_at timestamptz, next_attempt_at timestamptz
);
create index if not exists idx_catalog_outbox_pending on integration_outbox(status,next_attempt_at,occurred_at);

create or replace function catalog.emit_inventory_changed() returns trigger language plpgsql as $$
begin
  if new.stock_quantity is distinct from old.stock_quantity or new.reserved_quantity is distinct from old.reserved_quantity then
    insert into catalog.integration_outbox(event_type,aggregate_type,aggregate_id,aggregate_version,payload)
    values('catalog.inventory.changed.v1','ProductVariant',new.id,new.version,
      jsonb_build_object('eventId',gen_random_uuid(),'variantId',new.id,'availableQuantity',new.stock_quantity-new.reserved_quantity,
        'version',new.version,'occurredAt',now()));
  end if;
  return new;
end $$;
drop trigger if exists trg_catalog_inventory_changed on product_variants;
create trigger trg_catalog_inventory_changed after update of stock_quantity,reserved_quantity on product_variants
for each row execute function catalog.emit_inventory_changed();

create or replace function catalog.emit_product_changed() returns trigger language plpgsql as $$
begin
  insert into catalog.integration_outbox(event_type,aggregate_type,aggregate_id,aggregate_version,payload)
  values('catalog.product.changed.v1','Product',new.id,new.version,
    jsonb_build_object('eventId',gen_random_uuid(),'productId',new.id,'changeType',tg_op,'version',new.version,'occurredAt',now()));
  return new;
end $$;
drop trigger if exists trg_catalog_product_changed on products;
create trigger trg_catalog_product_changed after insert or update on products
for each row execute function catalog.emit_product_changed();

reset role;
alter schema catalog owner to catalog_owner;
revoke all on schema public from catalog_app;
grant usage on schema catalog to catalog_app;
grant select,insert,update,delete on all tables in schema catalog to catalog_app;
grant usage,select on all sequences in schema catalog to catalog_app;
alter default privileges for role catalog_owner in schema catalog grant select,insert,update,delete on tables to catalog_app;
alter default privileges for role catalog_owner in schema catalog grant usage,select on sequences to catalog_app;
comment on schema catalog is 'Catalog-owned product, taxonomy, promotion, inventory, wishlist and review data; external IDs have no cross-service FK.';
