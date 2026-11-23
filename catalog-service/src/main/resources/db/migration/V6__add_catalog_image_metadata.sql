set role catalog_owner;

alter table catalog.product_images
  add column if not exists public_id text,
  add column if not exists asset_version text,
  add column if not exists width integer check (width > 0),
  add column if not exists height integer check (height > 0),
  add column if not exists asset_format text,
  add column if not exists asset_bytes bigint check (asset_bytes > 0),
  add column if not exists checksum text check (checksum ~ '^[0-9a-f]{64}$'),
  add column if not exists source_kind text,
  add column if not exists license_code text,
  add column if not exists source_uri text,
  add column if not exists source_group_code text;

comment on column catalog.product_images.checksum is
  'SHA-256 of original asset bytes. Search stores a separate digest of the normalized bytes.';

reset role;
