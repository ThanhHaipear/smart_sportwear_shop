-- Transitional compatibility for the legacy runtime while the isolated V2
-- baseline is still being completed. All statements preserve existing data.

alter table addresses
    add column if not exists province_code varchar(30),
    add column if not exists district_code varchar(30),
    add column if not exists ward_code varchar(30),
    add column if not exists province_name varchar(100),
    add column if not exists district_name varchar(100),
    add column if not exists ward_name varchar(100);

update addresses
   set province_name = coalesce(province_name, province),
       district_name = coalesce(district_name, district),
       ward_name = coalesce(ward_name, ward);

alter table addresses alter column province_name set not null;
alter table addresses alter column ward_name set not null;

-- The renamed 1A fields are now authoritative. Keep the legacy name columns
-- readable during the transition, but do not require new JPA writes to fill them.
alter table addresses alter column province drop not null;
alter table addresses alter column district drop not null;
alter table addresses alter column ward drop not null;

alter table users
    add column if not exists role_code varchar(30),
    add column if not exists anonymized_at timestamptz;

update users set role_code = role where role_code is null;
alter table users alter column role_code set not null;
alter table users alter column role drop not null;

alter table refresh_tokens
    add column if not exists revocation_reason varchar(30);

alter table refresh_tokens
    alter column token_hash type char(64) using token_hash::char(64);
alter table password_reset_tokens
    alter column token_hash type char(64) using token_hash::char(64);

create table if not exists user_identities (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    provider varchar(30) not null,
    provider_subject varchar(255) not null,
    provider_email varchar(255),
    last_login_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_user_identities_provider_subject unique (provider, provider_subject),
    constraint uq_user_identities_user_provider unique (user_id, provider)
);

-- 1A taxonomy tables required by the normalized catalog entities.
alter table categories add column if not exists code varchar(50);
update categories
   set code = upper(regexp_replace(slug, '[^a-zA-Z0-9]+', '_', 'g'))
 where code is null;
create unique index if not exists uq_categories_code on categories (code);

alter table brands add column if not exists code varchar(50);
update brands
   set code = upper(regexp_replace(slug, '[^a-zA-Z0-9]+', '_', 'g'))
 where code is null;
create unique index if not exists uq_brands_code on brands (code);

create table if not exists size_systems (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists sizes (
    id uuid primary key,
    size_system_id uuid not null references size_systems (id) on delete restrict,
    code varchar(30) not null,
    display_name varchar(100) not null,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    constraint uq_sizes_system_code unique (size_system_id, code)
);

create table if not exists product_types (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    default_size_system_id uuid not null references size_systems (id) on delete restrict,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists sports (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists use_cases (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists materials (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists technologies (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists colors (
    id uuid primary key,
    code varchar(50) not null unique,
    name varchar(150) not null,
    family_code varchar(30) not null,
    hex_value char(7),
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists catalog_datasets (
    id uuid primary key,
    name varchar(100) not null,
    version varchar(50) not null,
    manifest_uri text not null,
    manifest_checksum char(64) not null,
    source_description text,
    license_code varchar(50) not null,
    status varchar(20) not null default 'DRAFT',
    imported_at timestamptz,
    created_at timestamptz not null,
    constraint uq_catalog_datasets_name_version unique (name, version)
);

alter table products
    add column if not exists product_type_id uuid references product_types (id) on delete restrict,
    add column if not exists size_system_id uuid references size_systems (id) on delete restrict,
    add column if not exists gender_code varchar(12),
    add column if not exists catalog_version bigint not null default 1,
    add column if not exists data_source varchar(20) not null default 'REAL';

update products
   set gender_code = case when gender in ('MEN', 'WOMEN', 'UNISEX') then gender else 'UNISEX' end
 where gender_code is null;

create table if not exists product_sports (
    product_id uuid not null references products (id) on delete restrict,
    sport_id uuid not null references sports (id) on delete restrict,
    primary key (product_id, sport_id)
);

create table if not exists product_use_cases (
    product_id uuid not null references products (id) on delete restrict,
    use_case_id uuid not null references use_cases (id) on delete restrict,
    primary key (product_id, use_case_id)
);

create table if not exists product_materials (
    product_id uuid not null references products (id) on delete restrict,
    material_id uuid not null references materials (id) on delete restrict,
    percentage numeric(5, 2),
    primary key (product_id, material_id)
);

create table if not exists product_technologies (
    product_id uuid not null references products (id) on delete restrict,
    technology_id uuid not null references technologies (id) on delete restrict,
    primary key (product_id, technology_id)
);

create table if not exists catalog_dataset_items (
    dataset_id uuid not null references catalog_datasets (id) on delete restrict,
    product_id uuid not null references products (id) on delete restrict,
    source_record_id varchar(150) not null,
    source_uri text,
    normalized_checksum char(64) not null,
    imported_at timestamptz not null default now(),
    primary key (dataset_id, product_id),
    constraint uq_catalog_dataset_items_source unique (dataset_id, source_record_id)
);

alter table product_variants
    add column if not exists size_id uuid references sizes (id) on delete restrict,
    add column if not exists color_id uuid references colors (id) on delete restrict,
    add column if not exists reorder_point integer not null default 0;

alter table product_variants alter column size drop not null;
alter table product_variants alter column color drop not null;
create unique index if not exists uq_product_variants_product_id_id
    on product_variants (product_id, id);

alter table product_images
    add column if not exists variant_id uuid,
    add column if not exists color_id uuid references colors (id) on delete restrict,
    add column if not exists storage_provider varchar(20) not null default 'CLOUDINARY',
    add column if not exists asset_version varchar(50) not null default 'legacy',
    add column if not exists secure_url text,
    add column if not exists format varchar(20) not null default 'jpg',
    add column if not exists bytes bigint,
    add column if not exists checksum char(64),
    add column if not exists width integer,
    add column if not exists height integer,
    add column if not exists source_kind varchar(20) not null default 'OWNED',
    add column if not exists source_uri text,
    add column if not exists license_code varchar(50) not null default 'OWNED',
    add column if not exists source_group_code varchar(100),
    add column if not exists updated_at timestamptz not null default now();

update product_images
   set secure_url = image_url,
       public_id = coalesce(public_id, 'legacy/' || id::text)
 where secure_url is null or public_id is null;

alter table product_images alter column image_url drop not null;

-- Stable reference rows used by compatibility APIs that still accept size/color text.
insert into size_systems (id, code, name, status, created_at, updated_at)
values
    ('a1000000-0000-0000-0000-000000000001', 'APPAREL_INT', 'International apparel', 'ACTIVE', now(), now()),
    ('a1000000-0000-0000-0000-000000000002', 'SHOES_EU', 'EU shoes', 'ACTIVE', now(), now()),
    ('a1000000-0000-0000-0000-000000000003', 'ONE_SIZE', 'One size', 'ACTIVE', now(), now())
on conflict (code) do nothing;

insert into sizes (id, size_system_id, code, display_name, sort_order, created_at)
select gen_random_uuid(), ss.id, seed.code, seed.code, seed.sort_order, now()
from size_systems ss
join (values
    ('APPAREL_INT', 'XS', 10), ('APPAREL_INT', 'S', 20), ('APPAREL_INT', 'M', 30),
    ('APPAREL_INT', 'L', 40), ('APPAREL_INT', 'XL', 50), ('APPAREL_INT', 'XXL', 60),
    ('SHOES_EU', '36', 360), ('SHOES_EU', '37', 370), ('SHOES_EU', '38', 380),
    ('SHOES_EU', '39', 390), ('SHOES_EU', '40', 400), ('SHOES_EU', '41', 410),
    ('SHOES_EU', '42', 420), ('SHOES_EU', '43', 430), ('SHOES_EU', '44', 440),
    ('SHOES_EU', '45', 450), ('ONE_SIZE', 'ONE_SIZE', 10)
) as seed(system_code, code, sort_order) on seed.system_code = ss.code
on conflict (size_system_id, code) do nothing;

insert into product_types (id, code, name, default_size_system_id, status, created_at, updated_at)
select gen_random_uuid(), seed.code, seed.name, ss.id, 'ACTIVE', now(), now()
from (values
    ('SPORTS_TOP', 'Sports top', 'APPAREL_INT'),
    ('SPORTS_BOTTOM', 'Sports bottom', 'APPAREL_INT'),
    ('SPORTS_SHOES', 'Sports shoes', 'SHOES_EU'),
    ('SPORTS_UNIFORM', 'Sports uniform', 'APPAREL_INT'),
    ('SPORTS_ACCESSORY', 'Sports accessory', 'ONE_SIZE')
) as seed(code, name, system_code)
join size_systems ss on ss.code = seed.system_code
on conflict (code) do nothing;

insert into colors (id, code, name, family_code, hex_value, status, created_at, updated_at)
values
    (gen_random_uuid(), 'BLACK', 'Black', 'BLACK', '#000000', 'ACTIVE', now(), now()),
    (gen_random_uuid(), 'WHITE', 'White', 'WHITE', '#FFFFFF', 'ACTIVE', now(), now()),
    (gen_random_uuid(), 'RED', 'Red', 'RED', '#FF0000', 'ACTIVE', now(), now()),
    (gen_random_uuid(), 'BLUE', 'Blue', 'BLUE', '#0000FF', 'ACTIVE', now(), now())
on conflict (code) do nothing;

alter table orders
    add column if not exists currency_code char(3) not null default 'VND',
    add column if not exists placed_at timestamptz,
    add column if not exists return_eligible_until timestamptz;

update orders set placed_at = created_at where placed_at is null;

update orders
   set order_status = 'CANCELLED'
 where order_status in ('CANCELLATION_REQUESTED', 'CANCELLATION_APPROVED');

alter table orders drop constraint if exists chk_orders_order_status;
alter table orders add constraint chk_orders_order_status check (order_status in
    ('PENDING_CONFIRMATION', 'CONFIRMED', 'PACKING', 'SHIPPING', 'DELIVERED', 'CANCELLED'));

alter table orders drop constraint if exists chk_orders_payment_status;
alter table orders add constraint chk_orders_payment_status check (payment_status in
    ('UNPAID', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED'));

alter table order_items
    add column if not exists discount_allocated numeric(14, 2) not null default 0,
    add column if not exists net_line_amount numeric(14, 2),
    add column if not exists return_pending_quantity integer not null default 0,
    add column if not exists returned_quantity integer not null default 0;

update order_items
   set net_line_amount = line_total - discount_allocated
 where net_line_amount is null;

alter table order_items alter column net_line_amount set not null;

alter table payments drop constraint if exists chk_payments_status;
alter table payments add constraint chk_payments_status check (status in
    ('UNPAID', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED'));

create table if not exists order_status_history (
    id uuid primary key,
    order_id uuid not null references orders (id) on delete restrict,
    from_status varchar(30),
    to_status varchar(30) not null,
    actor_user_id uuid references users (id) on delete set null,
    reason text,
    occurred_at timestamptz not null default now()
);

create index if not exists idx_order_status_history_order_time
    on order_status_history (order_id, occurred_at desc);

create table if not exists payment_events (
    id uuid primary key,
    payment_id uuid not null references payments (id) on delete restrict,
    provider varchar(20) not null,
    provider_event_key char(64) not null,
    event_type varchar(40) not null,
    verified boolean not null,
    payload_redacted jsonb,
    received_at timestamptz not null default now(),
    constraint uq_payment_events_provider_key unique (provider, provider_event_key)
);

create index if not exists idx_payment_events_payment_time
    on payment_events (payment_id, received_at desc);

alter table return_items
    add column if not exists order_id uuid references orders (id) on delete restrict;

update return_items ri
   set order_id = r.order_id
  from returns r
 where r.id = ri.return_id and ri.order_id is null;

alter table return_items alter column order_id set not null;

alter table refunds
    add column if not exists shipping_refund_amount numeric(14, 2) not null default 0;

alter table inventory_transactions
    add column if not exists return_id uuid references returns (id) on delete restrict,
    add column if not exists reason text,
    add column if not exists actor_user_id uuid references users (id) on delete set null,
    add column if not exists idempotency_key varchar(180);

update inventory_transactions
   set reason = coalesce(reason, note),
       actor_user_id = coalesce(actor_user_id, created_by),
       idempotency_key = coalesce(idempotency_key, 'LEGACY:' || id::text);

alter table inventory_transactions alter column idempotency_key set not null;
create unique index if not exists uq_inventory_transactions_idempotency
    on inventory_transactions (idempotency_key);

alter table inventory_transactions drop constraint if exists chk_inventory_transactions_type;
alter table inventory_transactions add constraint chk_inventory_transactions_type check (type in
    ('IMPORT', 'EXPORT', 'ADJUSTMENT_UP', 'ADJUSTMENT_DOWN', 'ORDER_RESERVE', 'ORDER_RELEASE',
     'ORDER_CONFIRM_DEDUCT', 'ORDER_CANCEL_RESTOCK', 'RETURN_RESTOCK'));

alter table integration_outbox
    add column if not exists idempotency_key varchar(200),
    add column if not exists aggregate_version bigint not null default 1,
    add column if not exists locked_at timestamptz,
    add column if not exists locked_by varchar(120),
    add column if not exists updated_at timestamptz not null default now();

update integration_outbox
   set idempotency_key = coalesce(idempotency_key, 'LEGACY:' || id::text);

alter table integration_outbox alter column idempotency_key set not null;
create unique index if not exists uq_integration_outbox_idempotency
    on integration_outbox (idempotency_key);

create table if not exists user_interactions (
    event_id uuid primary key,
    occurred_at timestamptz not null default now(),
    user_id uuid references users (id) on delete set null,
    anonymous_id varchar(100),
    event_type varchar(50) not null,
    product_id uuid references products (id) on delete restrict,
    variant_id uuid references product_variants (id) on delete restrict,
    request_id uuid,
    source varchar(50) not null,
    data_source varchar(20) not null,
    metadata jsonb,
    anonymized_at timestamptz
);

create index if not exists idx_user_interactions_user_time
    on user_interactions (user_id, occurred_at desc);
create index if not exists idx_user_interactions_anonymous_time
    on user_interactions (anonymous_id, occurred_at desc);
