-- Database V2 - batch 1A
-- Identity, taxonomy, catalog, SKU, Cloudinary metadata and catalog datasets.
-- This migration is intentionally isolated from db/migration (legacy history).

set search_path = public;

create or replace function set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

-- ============================================================================
-- 1A.1 Identity
-- ============================================================================

create table users (
    id uuid primary key default gen_random_uuid(),
    email varchar(255) not null,
    password_hash varchar(255),
    full_name varchar(150) not null,
    phone varchar(20),
    role_code varchar(30) not null default 'CUSTOMER',
    status varchar(20) not null default 'ACTIVE',
    last_login_at timestamptz,
    anonymized_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_users_email_normalized check (
        email = lower(btrim(email)) and email <> '' and position('@' in email) > 1
    ),
    constraint chk_users_full_name check (full_name = btrim(full_name) and full_name <> ''),
    constraint chk_users_phone check (
        phone is null or phone ~ '^[0-9+() -]{8,20}$'
    ),
    constraint chk_users_role check (
        role_code in ('CUSTOMER', 'SALES_STAFF', 'WAREHOUSE_STAFF', 'ADMIN')
    ),
    constraint chk_users_status check (status in ('ACTIVE', 'LOCKED', 'DEACTIVATED')),
    constraint chk_users_anonymized_status check (
        anonymized_at is null or status = 'DEACTIVATED'
    )
);

create unique index uq_users_email_lower on users (lower(email));
create index idx_users_role_status on users (role_code, status);

create table user_identities (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    provider varchar(30) not null,
    provider_subject varchar(255) not null,
    provider_email varchar(255),
    last_login_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_user_identities_user foreign key (user_id)
        references users (id) on delete cascade,
    constraint chk_user_identities_provider check (provider in ('GOOGLE')),
    constraint chk_user_identities_subject check (
        provider_subject = btrim(provider_subject) and provider_subject <> ''
    ),
    constraint chk_user_identities_email check (
        provider_email is null or provider_email = lower(btrim(provider_email))
    ),
    constraint uq_user_identities_provider_subject unique (provider, provider_subject),
    constraint uq_user_identities_user_provider unique (user_id, provider)
);

create index idx_user_identities_user on user_identities (user_id);

create table addresses (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    receiver_name varchar(150) not null,
    phone varchar(20) not null,
    province_code varchar(30),
    province_name varchar(100) not null,
    district_code varchar(30),
    district_name varchar(100),
    ward_code varchar(30),
    ward_name varchar(100) not null,
    address_line varchar(255) not null,
    is_default boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_addresses_user foreign key (user_id)
        references users (id) on delete cascade,
    constraint chk_addresses_receiver_name check (
        receiver_name = btrim(receiver_name) and receiver_name <> ''
    ),
    constraint chk_addresses_phone check (phone ~ '^[0-9+() -]{8,20}$'),
    constraint chk_addresses_province_name check (
        province_name = btrim(province_name) and province_name <> ''
    ),
    constraint chk_addresses_district_pair check (
        (district_code is null and district_name is null)
        or district_name is not null
    ),
    constraint chk_addresses_ward_name check (
        ward_name = btrim(ward_name) and ward_name <> ''
    ),
    constraint chk_addresses_line check (
        address_line = btrim(address_line) and address_line <> ''
    )
);

create unique index uq_addresses_default_per_user
    on addresses (user_id) where is_default;
create index idx_addresses_user_created
    on addresses (user_id, created_at desc);

create table refresh_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    token_hash char(64) not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    revocation_reason varchar(30),
    created_at timestamptz not null default now(),
    constraint fk_refresh_tokens_user foreign key (user_id)
        references users (id) on delete cascade,
    constraint uq_refresh_tokens_hash unique (token_hash),
    constraint chk_refresh_tokens_hash check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_refresh_tokens_expiry check (expires_at > created_at),
    constraint chk_refresh_tokens_revoke_time check (
        revoked_at is null or revoked_at >= created_at
    ),
    constraint chk_refresh_tokens_revoke_reason check (
        (revoked_at is null and revocation_reason is null)
        or (revoked_at is not null and revocation_reason in (
            'ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET',
            'ACCOUNT_LOCKED', 'ACCOUNT_DEACTIVATED', 'ADMIN_REVOKED'
        ))
    )
);

create index idx_refresh_tokens_user_expiry on refresh_tokens (user_id, expires_at);
create index idx_refresh_tokens_cleanup on refresh_tokens (expires_at, revoked_at);

create table password_reset_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    token_hash char(64) not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now(),
    constraint fk_password_reset_tokens_user foreign key (user_id)
        references users (id) on delete cascade,
    constraint uq_password_reset_tokens_hash unique (token_hash),
    constraint chk_password_reset_tokens_hash check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_password_reset_tokens_expiry check (expires_at > created_at),
    constraint chk_password_reset_tokens_used_time check (
        used_at is null or used_at >= created_at
    )
);

create index idx_password_reset_tokens_user_created
    on password_reset_tokens (user_id, created_at desc);
create index idx_password_reset_tokens_cleanup
    on password_reset_tokens (expires_at, used_at);

create or replace function assert_user_credential_mode(p_user_id uuid)
returns void
language plpgsql
as $$
declare
    v_password_hash varchar(255);
    v_external_count integer;
begin
    select password_hash
      into v_password_hash
      from users
     where id = p_user_id;

    if not found then
        return;
    end if;

    select count(*)
      into v_external_count
      from user_identities
     where user_id = p_user_id;

    if v_password_hash is not null and v_external_count <> 0 then
        raise exception using
            errcode = '23514',
            message = 'LOCAL account must not have an external identity';
    end if;

    if v_password_hash is null and v_external_count <> 1 then
        raise exception using
            errcode = '23514',
            message = 'External account must have exactly one identity';
    end if;
end;
$$;

create or replace function check_user_credentials_from_user()
returns trigger
language plpgsql
as $$
begin
    perform assert_user_credential_mode(coalesce(new.id, old.id));
    return null;
end;
$$;

create or replace function check_user_credentials_from_identity()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'UPDATE' and old.user_id is distinct from new.user_id then
        perform assert_user_credential_mode(old.user_id);
    end if;
    perform assert_user_credential_mode(coalesce(new.user_id, old.user_id));
    return null;
end;
$$;

create constraint trigger ct_users_credential_mode
after insert or update on users
deferrable initially deferred
for each row execute function check_user_credentials_from_user();

create constraint trigger ct_user_identities_credential_mode
after insert or update or delete on user_identities
deferrable initially deferred
for each row execute function check_user_credentials_from_identity();

-- ============================================================================
-- 1A.2 Taxonomy
-- ============================================================================

create table categories (
    id uuid primary key default gen_random_uuid(),
    parent_id uuid,
    code varchar(50) not null,
    name varchar(150) not null,
    slug varchar(180) not null,
    description text,
    status varchar(20) not null default 'ACTIVE',
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_categories_parent foreign key (parent_id)
        references categories (id) on delete restrict,
    constraint uq_categories_code unique (code),
    constraint uq_categories_slug unique (slug),
    constraint chk_categories_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_categories_name check (name = btrim(name) and name <> ''),
    constraint chk_categories_slug check (slug = lower(btrim(slug)) and slug <> ''),
    constraint chk_categories_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint chk_categories_sort_order check (sort_order >= 0),
    constraint chk_categories_not_self check (parent_id is null or parent_id <> id)
);

create index idx_categories_parent_sort on categories (parent_id, sort_order);
create index idx_categories_status_sort on categories (status, sort_order);

create or replace function check_category_cycle()
returns trigger
language plpgsql
as $$
declare
    v_has_cycle boolean;
begin
    if new.parent_id is null then
        return null;
    end if;

    with recursive ancestors(id, parent_id) as (
        select c.id, c.parent_id
          from categories c
         where c.id = new.parent_id
        union
        select c.id, c.parent_id
          from categories c
          join ancestors a on c.id = a.parent_id
    )
    select exists(select 1 from ancestors where id = new.id)
      into v_has_cycle;

    if v_has_cycle then
        raise exception using errcode = '23514', message = 'Category hierarchy contains a cycle';
    end if;
    return null;
end;
$$;

create constraint trigger ct_categories_no_cycle
after insert or update of parent_id on categories
deferrable initially deferred
for each row execute function check_category_cycle();

create table brands (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    slug varchar(180) not null,
    description text,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_brands_code unique (code),
    constraint uq_brands_slug unique (slug),
    constraint chk_brands_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_brands_name check (name = btrim(name) and name <> ''),
    constraint chk_brands_slug check (slug = lower(btrim(slug)) and slug <> ''),
    constraint chk_brands_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table size_systems (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_size_systems_code unique (code),
    constraint chk_size_systems_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_size_systems_name check (name = btrim(name) and name <> ''),
    constraint chk_size_systems_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table sizes (
    id uuid primary key default gen_random_uuid(),
    size_system_id uuid not null,
    code varchar(30) not null,
    display_name varchar(100) not null,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    constraint fk_sizes_system foreign key (size_system_id)
        references size_systems (id) on delete restrict,
    constraint uq_sizes_system_code unique (size_system_id, code),
    constraint chk_sizes_code check (code = upper(btrim(code)) and code <> ''),
    constraint chk_sizes_display_name check (
        display_name = btrim(display_name) and display_name <> ''
    ),
    constraint chk_sizes_sort_order check (sort_order >= 0)
);

create index idx_sizes_system_sort on sizes (size_system_id, sort_order);

create table product_types (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    default_size_system_id uuid not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_product_types_size_system foreign key (default_size_system_id)
        references size_systems (id) on delete restrict,
    constraint uq_product_types_code unique (code),
    constraint chk_product_types_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_product_types_name check (name = btrim(name) and name <> ''),
    constraint chk_product_types_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table sports (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_sports_code unique (code),
    constraint chk_sports_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_sports_name check (name = btrim(name) and name <> ''),
    constraint chk_sports_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table use_cases (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_use_cases_code unique (code),
    constraint chk_use_cases_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_use_cases_name check (name = btrim(name) and name <> ''),
    constraint chk_use_cases_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table materials (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_materials_code unique (code),
    constraint chk_materials_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_materials_name check (name = btrim(name) and name <> ''),
    constraint chk_materials_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table technologies (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_technologies_code unique (code),
    constraint chk_technologies_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_technologies_name check (name = btrim(name) and name <> ''),
    constraint chk_technologies_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table colors (
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null,
    name varchar(150) not null,
    family_code varchar(30) not null,
    hex_value char(7),
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_colors_code unique (code),
    constraint chk_colors_code check (code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_colors_family check (family_code ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_colors_name check (name = btrim(name) and name <> ''),
    constraint chk_colors_hex check (hex_value is null or hex_value ~ '^#[0-9A-Fa-f]{6}$'),
    constraint chk_colors_status check (status in ('ACTIVE', 'INACTIVE'))
);

-- ============================================================================
-- 1A.3-1A.4 Catalog, SKU, images and catalog datasets
-- ============================================================================

create table catalog_datasets (
    id uuid primary key default gen_random_uuid(),
    name varchar(100) not null,
    version varchar(50) not null,
    manifest_uri text not null,
    manifest_checksum char(64) not null,
    source_description text,
    license_code varchar(50) not null,
    status varchar(20) not null default 'DRAFT',
    imported_at timestamptz,
    created_at timestamptz not null default now(),
    constraint uq_catalog_datasets_name_version unique (name, version),
    constraint chk_catalog_datasets_name check (name = btrim(name) and name <> ''),
    constraint chk_catalog_datasets_version check (version = btrim(version) and version <> ''),
    constraint chk_catalog_datasets_manifest_checksum check (
        manifest_checksum ~ '^[0-9a-f]{64}$'
    ),
    constraint chk_catalog_datasets_status check (status in ('DRAFT', 'VALIDATED', 'FROZEN'))
);

create table products (
    id uuid primary key default gen_random_uuid(),
    category_id uuid not null,
    brand_id uuid not null,
    product_type_id uuid not null,
    size_system_id uuid not null,
    name varchar(255) not null,
    slug varchar(300) not null,
    short_description varchar(500),
    description text,
    gender_code varchar(12) not null,
    attributes jsonb not null default '{}'::jsonb,
    status varchar(20) not null default 'DRAFT',
    catalog_version bigint not null default 1,
    data_source varchar(20) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_products_category foreign key (category_id)
        references categories (id) on delete restrict,
    constraint fk_products_brand foreign key (brand_id)
        references brands (id) on delete restrict,
    constraint fk_products_type foreign key (product_type_id)
        references product_types (id) on delete restrict,
    constraint fk_products_size_system foreign key (size_system_id)
        references size_systems (id) on delete restrict,
    constraint uq_products_slug unique (slug),
    constraint chk_products_name check (name = btrim(name) and name <> ''),
    constraint chk_products_slug check (slug = lower(btrim(slug)) and slug <> ''),
    constraint chk_products_gender check (gender_code in ('MEN', 'WOMEN', 'UNISEX')),
    constraint chk_products_attributes check (jsonb_typeof(attributes) = 'object'),
    constraint chk_products_status check (status in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    constraint chk_products_catalog_version check (catalog_version > 0),
    constraint chk_products_data_source check (data_source in ('REAL', 'DEMO', 'SYNTHETIC'))
);

create index idx_products_category_status on products (category_id, status);
create index idx_products_brand_status on products (brand_id, status);
create index idx_products_type_gender_status on products (product_type_id, gender_code, status);

create table catalog_dataset_items (
    dataset_id uuid not null,
    product_id uuid not null,
    source_record_id varchar(150) not null,
    source_uri text,
    normalized_checksum char(64) not null,
    imported_at timestamptz not null default now(),
    constraint pk_catalog_dataset_items primary key (dataset_id, product_id),
    constraint fk_catalog_dataset_items_dataset foreign key (dataset_id)
        references catalog_datasets (id) on delete restrict,
    constraint fk_catalog_dataset_items_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint uq_catalog_dataset_items_source unique (dataset_id, source_record_id),
    constraint chk_catalog_dataset_items_source_record check (
        source_record_id = btrim(source_record_id) and source_record_id <> ''
    ),
    constraint chk_catalog_dataset_items_checksum check (
        normalized_checksum ~ '^[0-9a-f]{64}$'
    )
);

create index idx_catalog_dataset_items_product on catalog_dataset_items (product_id);

create table product_sports (
    product_id uuid not null,
    sport_id uuid not null,
    constraint pk_product_sports primary key (product_id, sport_id),
    constraint fk_product_sports_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint fk_product_sports_sport foreign key (sport_id)
        references sports (id) on delete restrict
);

create index idx_product_sports_sport on product_sports (sport_id, product_id);

create table product_use_cases (
    product_id uuid not null,
    use_case_id uuid not null,
    constraint pk_product_use_cases primary key (product_id, use_case_id),
    constraint fk_product_use_cases_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint fk_product_use_cases_use_case foreign key (use_case_id)
        references use_cases (id) on delete restrict
);

create index idx_product_use_cases_use_case on product_use_cases (use_case_id, product_id);

create table product_materials (
    product_id uuid not null,
    material_id uuid not null,
    percentage numeric(5,2),
    constraint pk_product_materials primary key (product_id, material_id),
    constraint fk_product_materials_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint fk_product_materials_material foreign key (material_id)
        references materials (id) on delete restrict,
    constraint chk_product_materials_percentage check (
        percentage is null or (percentage > 0 and percentage <= 100)
    )
);

create index idx_product_materials_material on product_materials (material_id, product_id);

create table product_technologies (
    product_id uuid not null,
    technology_id uuid not null,
    constraint pk_product_technologies primary key (product_id, technology_id),
    constraint fk_product_technologies_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint fk_product_technologies_technology foreign key (technology_id)
        references technologies (id) on delete restrict
);

create index idx_product_technologies_technology
    on product_technologies (technology_id, product_id);

create table product_variants (
    id uuid primary key default gen_random_uuid(),
    product_id uuid not null,
    sku varchar(100) not null,
    size_id uuid not null,
    color_id uuid not null,
    price numeric(14,2) not null,
    compare_at_price numeric(14,2),
    stock_quantity integer not null default 0,
    reserved_quantity integer not null default 0,
    reorder_point integer not null default 0,
    status varchar(20) not null default 'ACTIVE',
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_product_variants_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint fk_product_variants_size foreign key (size_id)
        references sizes (id) on delete restrict,
    constraint fk_product_variants_color foreign key (color_id)
        references colors (id) on delete restrict,
    constraint uq_product_variants_sku unique (sku),
    constraint uq_product_variants_combination unique (product_id, size_id, color_id),
    constraint uq_product_variants_product_id_id unique (product_id, id),
    constraint chk_product_variants_sku check (sku = upper(btrim(sku)) and sku <> ''),
    constraint chk_product_variants_price check (price > 0),
    constraint chk_product_variants_compare_price check (
        compare_at_price is null or compare_at_price >= price
    ),
    constraint chk_product_variants_stock check (stock_quantity >= 0),
    constraint chk_product_variants_reserved check (
        reserved_quantity >= 0 and reserved_quantity <= stock_quantity
    ),
    constraint chk_product_variants_reorder_point check (reorder_point >= 0),
    constraint chk_product_variants_status check (
        status in ('ACTIVE', 'OUT_OF_STOCK', 'INACTIVE')
    ),
    constraint chk_product_variants_version check (version > 0)
);

create index idx_product_variants_product_status on product_variants (product_id, status);
create index idx_product_variants_size_color on product_variants (size_id, color_id);

create table product_images (
    id uuid primary key default gen_random_uuid(),
    product_id uuid not null,
    variant_id uuid,
    color_id uuid,
    storage_provider varchar(20) not null default 'CLOUDINARY',
    public_id varchar(255) not null,
    asset_version varchar(50) not null,
    secure_url text not null,
    format varchar(20) not null,
    bytes bigint,
    alt_text varchar(255),
    sort_order integer not null default 0,
    is_primary boolean not null default false,
    checksum char(64),
    width integer,
    height integer,
    source_kind varchar(20) not null,
    source_uri text,
    license_code varchar(50) not null,
    source_group_code varchar(100),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_product_images_product foreign key (product_id)
        references products (id) on delete restrict,
    constraint fk_product_images_variant foreign key (product_id, variant_id)
        references product_variants (product_id, id) on delete restrict,
    constraint fk_product_images_color foreign key (color_id)
        references colors (id) on delete restrict,
    constraint uq_product_images_asset unique (storage_provider, public_id),
    constraint chk_product_images_provider check (storage_provider in ('CLOUDINARY')),
    constraint chk_product_images_public_id check (public_id = btrim(public_id) and public_id <> ''),
    constraint chk_product_images_asset_version check (
        asset_version = btrim(asset_version) and asset_version <> ''
    ),
    constraint chk_product_images_url check (secure_url like 'https://%'),
    constraint chk_product_images_format check (format = lower(btrim(format)) and format <> ''),
    constraint chk_product_images_bytes check (bytes is null or bytes > 0),
    constraint chk_product_images_sort_order check (sort_order >= 0),
    constraint chk_product_images_checksum check (
        checksum is null or checksum ~ '^[0-9a-f]{64}$'
    ),
    constraint chk_product_images_dimensions check (
        (width is null and height is null) or (width > 0 and height > 0)
    ),
    constraint chk_product_images_source_kind check (
        source_kind in ('OWNED', 'LICENSED', 'SYNTHETIC')
    )
);

create unique index uq_product_images_primary
    on product_images (product_id) where is_primary;
create index idx_product_images_product_sort
    on product_images (product_id, sort_order, id);
create index idx_product_images_variant on product_images (variant_id) where variant_id is not null;
create index idx_product_images_color on product_images (color_id) where color_id is not null;

create or replace function check_variant_size_system()
returns trigger
language plpgsql
as $$
declare
    v_product_system uuid;
    v_size_system uuid;
begin
    select size_system_id into v_product_system from products where id = new.product_id;
    select size_system_id into v_size_system from sizes where id = new.size_id;
    if v_product_system is distinct from v_size_system then
        raise exception using errcode = '23514', message = 'Variant size does not belong to product size system';
    end if;
    return null;
end;
$$;

create constraint trigger ct_variant_size_system
after insert or update of product_id, size_id on product_variants
deferrable initially deferred
for each row execute function check_variant_size_system();

create or replace function check_product_size_system_change()
returns trigger
language plpgsql
as $$
begin
    if exists (
        select 1
          from product_variants pv
          join sizes s on s.id = pv.size_id
         where pv.product_id = new.id
           and s.size_system_id <> new.size_system_id
    ) then
        raise exception using errcode = '23514', message = 'Product size system conflicts with existing variants';
    end if;
    return null;
end;
$$;

create constraint trigger ct_product_size_system_change
after update of size_system_id on products
deferrable initially deferred
for each row execute function check_product_size_system_change();

create or replace function check_product_image_variant_color()
returns trigger
language plpgsql
as $$
declare
    v_variant_color uuid;
begin
    if new.variant_id is null then
        return null;
    end if;
    select color_id into v_variant_color
      from product_variants
     where id = new.variant_id and product_id = new.product_id;
    if not found or new.color_id is distinct from v_variant_color then
        raise exception using errcode = '23514', message = 'Image color must match its variant color';
    end if;
    return null;
end;
$$;

create constraint trigger ct_product_image_variant_color
after insert or update of product_id, variant_id, color_id on product_images
deferrable initially deferred
for each row execute function check_product_image_variant_color();

create or replace function assert_product_publishable(p_product_id uuid)
returns void
language plpgsql
as $$
declare
    v_status varchar(20);
begin
    select status into v_status from products where id = p_product_id;
    if not found or v_status <> 'ACTIVE' then
        return;
    end if;

    if not exists (
        select 1 from product_variants
         where product_id = p_product_id and status in ('ACTIVE', 'OUT_OF_STOCK')
    ) then
        raise exception using errcode = '23514', message = 'Active product requires a sellable variant';
    end if;

    if not exists (
        select 1 from product_images
         where product_id = p_product_id
           and is_primary
           and checksum is not null
           and bytes is not null
           and width is not null
           and height is not null
           and format <> ''
           and asset_version <> ''
           and license_code <> ''
    ) then
        raise exception using errcode = '23514', message = 'Active product requires one valid primary image';
    end if;
end;
$$;

create or replace function check_product_publishable_from_product()
returns trigger
language plpgsql
as $$
begin
    perform assert_product_publishable(coalesce(new.id, old.id));
    return null;
end;
$$;

create or replace function check_product_publishable_from_variant_or_image()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'UPDATE' and old.product_id is distinct from new.product_id then
        perform assert_product_publishable(old.product_id);
    end if;
    perform assert_product_publishable(coalesce(new.product_id, old.product_id));
    return null;
end;
$$;

create constraint trigger ct_products_publishable
after insert or update on products
deferrable initially deferred
for each row execute function check_product_publishable_from_product();

create constraint trigger ct_product_variants_publishable
after insert or update or delete on product_variants
deferrable initially deferred
for each row execute function check_product_publishable_from_variant_or_image();

create constraint trigger ct_product_images_publishable
after insert or update or delete on product_images
deferrable initially deferred
for each row execute function check_product_publishable_from_variant_or_image();

create or replace function protect_catalog_dataset()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        if old.status = 'FROZEN' then
            raise exception using errcode = '23514', message = 'Frozen catalog dataset is immutable';
        end if;
        return old;
    end if;

    if old.status = 'FROZEN' and new is distinct from old then
        raise exception using errcode = '23514', message = 'Frozen catalog dataset is immutable';
    end if;

    if new.status <> old.status and not (
        (old.status = 'DRAFT' and new.status = 'VALIDATED')
        or (old.status = 'VALIDATED' and new.status = 'FROZEN')
    ) then
        raise exception using errcode = '23514', message = 'Invalid catalog dataset status transition';
    end if;
    return new;
end;
$$;

create trigger trg_catalog_datasets_protect
before update or delete on catalog_datasets
for each row execute function protect_catalog_dataset();

create or replace function protect_catalog_dataset_item()
returns trigger
language plpgsql
as $$
declare
    v_dataset_id uuid;
    v_status varchar(20);
begin
    v_dataset_id := case when tg_op = 'DELETE' then old.dataset_id else new.dataset_id end;
    select status into v_status from catalog_datasets where id = v_dataset_id;
    if v_status = 'FROZEN' then
        raise exception using errcode = '23514', message = 'Items of a frozen catalog dataset are immutable';
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger trg_catalog_dataset_items_protect
before insert or update or delete on catalog_dataset_items
for each row execute function protect_catalog_dataset_item();

create or replace function check_catalog_dataset_freeze()
returns trigger
language plpgsql
as $$
begin
    if new.status = 'FROZEN' and not exists (
        select 1 from catalog_dataset_items where dataset_id = new.id
    ) then
        raise exception using errcode = '23514', message = 'Frozen catalog dataset must contain at least one item';
    end if;
    return null;
end;
$$;

create constraint trigger ct_catalog_dataset_freeze
after insert or update of status on catalog_datasets
deferrable initially deferred
for each row execute function check_catalog_dataset_freeze();

create or replace function protect_product_data_source()
returns trigger
language plpgsql
as $$
begin
    if new.data_source is distinct from old.data_source then
        raise exception using errcode = '23514', message = 'Product data_source is immutable';
    end if;
    return new;
end;
$$;

create trigger trg_products_data_source_immutable
before update of data_source on products
for each row execute function protect_product_data_source();

-- Keep updated_at correct even for SQL/import paths that bypass JPA auditing.
create trigger trg_users_updated_at before update on users
for each row execute function set_updated_at();
create trigger trg_user_identities_updated_at before update on user_identities
for each row execute function set_updated_at();
create trigger trg_addresses_updated_at before update on addresses
for each row execute function set_updated_at();
create trigger trg_categories_updated_at before update on categories
for each row execute function set_updated_at();
create trigger trg_brands_updated_at before update on brands
for each row execute function set_updated_at();
create trigger trg_size_systems_updated_at before update on size_systems
for each row execute function set_updated_at();
create trigger trg_product_types_updated_at before update on product_types
for each row execute function set_updated_at();
create trigger trg_sports_updated_at before update on sports
for each row execute function set_updated_at();
create trigger trg_use_cases_updated_at before update on use_cases
for each row execute function set_updated_at();
create trigger trg_materials_updated_at before update on materials
for each row execute function set_updated_at();
create trigger trg_technologies_updated_at before update on technologies
for each row execute function set_updated_at();
create trigger trg_colors_updated_at before update on colors
for each row execute function set_updated_at();
create trigger trg_products_updated_at before update on products
for each row execute function set_updated_at();
create trigger trg_product_variants_updated_at before update on product_variants
for each row execute function set_updated_at();
create trigger trg_product_images_updated_at before update on product_images
for each row execute function set_updated_at();
