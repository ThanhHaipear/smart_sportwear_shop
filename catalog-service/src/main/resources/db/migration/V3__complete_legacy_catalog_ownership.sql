-- Phase 10 completes Catalog ownership for taxonomy/dataset metadata omitted by V1/V2.
set role catalog_owner;
set search_path=catalog,pg_catalog;

create table if not exists size_systems (
  id uuid primary key, code varchar(50) not null unique, name varchar(150) not null,
  status varchar(20) not null, created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists sizes (
  id uuid primary key, size_system_id uuid not null references size_systems(id), code varchar(30) not null,
  display_name varchar(100) not null, sort_order integer not null, created_at timestamptz not null,
  unique(size_system_id,code)
);
create table if not exists product_types (
  id uuid primary key, code varchar(50) not null unique, name varchar(150) not null,
  default_size_system_id uuid not null references size_systems(id), status varchar(20) not null,
  created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists sports (
  id uuid primary key, code varchar(50) not null unique, name varchar(150) not null,
  status varchar(20) not null, created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists use_cases (like sports including defaults including constraints including indexes);
create table if not exists materials (like sports including defaults including constraints including indexes);
create table if not exists technologies (like sports including defaults including constraints including indexes);
create table if not exists colors (
  id uuid primary key, code varchar(50) not null unique, name varchar(150) not null, family_code varchar(30) not null,
  hex_value char(7), status varchar(20) not null, created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists catalog_datasets (
  id uuid primary key, name varchar(100) not null, version varchar(50) not null, manifest_uri text not null,
  manifest_checksum char(64) not null, source_description text, license_code varchar(50) not null,
  status varchar(20) not null, imported_at timestamptz, created_at timestamptz not null, unique(name,version)
);
create table if not exists catalog_dataset_items (
  dataset_id uuid not null references catalog_datasets(id), product_id uuid not null references products(id),
  source_record_id varchar(150) not null, source_uri text, normalized_checksum char(64) not null,
  imported_at timestamptz not null, primary key(dataset_id,product_id), unique(dataset_id,source_record_id)
);
create table if not exists product_sports (
  product_id uuid not null references products(id), sport_id uuid not null references sports(id), primary key(product_id,sport_id)
);
create table if not exists product_use_cases (
  product_id uuid not null references products(id), use_case_id uuid not null references use_cases(id), primary key(product_id,use_case_id)
);
create table if not exists product_materials (
  product_id uuid not null references products(id), material_id uuid not null references materials(id),
  percentage numeric(5,2), primary key(product_id,material_id)
);
create table if not exists product_technologies (
  product_id uuid not null references products(id), technology_id uuid not null references technologies(id),
  primary key(product_id,technology_id)
);
alter table inventory_transactions add column if not exists order_ref uuid;
alter table inventory_transactions add column if not exists return_ref uuid;
alter table inventory_transactions add column if not exists before_stock_quantity integer;
alter table inventory_transactions add column if not exists after_stock_quantity integer;
alter table inventory_transactions add column if not exists before_reserved_quantity integer;
alter table inventory_transactions add column if not exists after_reserved_quantity integer;
alter table inventory_transactions add column if not exists actor_ref uuid;
alter table inventory_transactions add column if not exists reason text;

reset role;
do $$ begin
if to_regclass('public.size_systems') is not null then insert into catalog.size_systems select * from public.size_systems on conflict do nothing; end if;
if to_regclass('public.sizes') is not null then insert into catalog.sizes select * from public.sizes on conflict do nothing; end if;
if to_regclass('public.product_types') is not null then insert into catalog.product_types select * from public.product_types on conflict do nothing; end if;
if to_regclass('public.sports') is not null then insert into catalog.sports select * from public.sports on conflict do nothing; end if;
if to_regclass('public.use_cases') is not null then insert into catalog.use_cases select * from public.use_cases on conflict do nothing; end if;
if to_regclass('public.materials') is not null then insert into catalog.materials select * from public.materials on conflict do nothing; end if;
if to_regclass('public.technologies') is not null then insert into catalog.technologies select * from public.technologies on conflict do nothing; end if;
if to_regclass('public.colors') is not null then insert into catalog.colors select * from public.colors on conflict do nothing; end if;
if to_regclass('public.catalog_datasets') is not null then insert into catalog.catalog_datasets select * from public.catalog_datasets on conflict do nothing; end if;
if to_regclass('public.catalog_dataset_items') is not null then insert into catalog.catalog_dataset_items select * from public.catalog_dataset_items on conflict do nothing; end if;
if to_regclass('public.product_sports') is not null then insert into catalog.product_sports select * from public.product_sports on conflict do nothing; end if;
if to_regclass('public.product_use_cases') is not null then insert into catalog.product_use_cases select * from public.product_use_cases on conflict do nothing; end if;
if to_regclass('public.product_materials') is not null then insert into catalog.product_materials select * from public.product_materials on conflict do nothing; end if;
if to_regclass('public.product_technologies') is not null then insert into catalog.product_technologies select * from public.product_technologies on conflict do nothing; end if;
if to_regclass('public.inventory_transactions') is not null then
  insert into catalog.inventory_transactions(id,variant_id,external_ref,transaction_type,quantity,idempotency_key,created_at,
    order_ref,return_ref,before_stock_quantity,after_stock_quantity,before_reserved_quantity,after_reserved_quantity,actor_ref,reason)
  select id,variant_id,coalesce(order_id,return_id,actor_user_id),type,quantity,idempotency_key,created_at,
    order_id,return_id,before_stock_quantity,after_stock_quantity,before_reserved_quantity,after_reserved_quantity,actor_user_id,reason
  from public.inventory_transactions on conflict(id) do nothing;
end if;
end $$;

grant select,insert,update,delete on all tables in schema catalog to catalog_app;
grant usage,select on all sequences in schema catalog to catalog_app;
