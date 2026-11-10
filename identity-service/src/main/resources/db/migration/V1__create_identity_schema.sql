do $$ begin
  if not exists(select 1 from pg_roles where rolname='identity_owner') then create role identity_owner nologin; end if;
  if not exists(select 1 from pg_roles where rolname='identity_app') then create role identity_app nologin; end if;
end $$;
create schema if not exists identity authorization identity_owner;
set role identity_owner;
set search_path=identity,pg_catalog;

create table if not exists users (
  id uuid primary key default gen_random_uuid(), email varchar(255) not null unique, password_hash varchar(255),
  full_name varchar(150) not null, phone varchar(20), role_code varchar(30) not null default 'CUSTOMER',
  status varchar(20) not null default 'ACTIVE', last_login_at timestamptz, anonymized_at timestamptz,
  version bigint not null default 1, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  check(email=lower(btrim(email))), check(role_code in ('CUSTOMER','SALES_STAFF','WAREHOUSE_STAFF','ADMIN')),
  check(status in ('ACTIVE','LOCKED','DEACTIVATED'))
);
create table if not exists user_identities (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references users(id) on delete cascade,
  provider varchar(30) not null check(provider in ('GOOGLE')), provider_subject varchar(255) not null,
  provider_email varchar(255), last_login_at timestamptz, created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(), unique(provider,provider_subject), unique(user_id,provider)
);
create table if not exists addresses (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references users(id) on delete cascade,
  receiver_name varchar(150) not null, phone varchar(20) not null, province_name varchar(100) not null,
  district_name varchar(100), ward_name varchar(100) not null, address_line varchar(255) not null,
  is_default boolean not null default false, created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create unique index if not exists uq_identity_default_address on addresses(user_id) where is_default;
create table if not exists refresh_tokens (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references users(id) on delete cascade,
  token_hash char(64) not null unique, expires_at timestamptz not null, revoked_at timestamptz,
  revocation_reason varchar(30), created_at timestamptz not null default now()
);
create index if not exists idx_identity_refresh_user on refresh_tokens(user_id,expires_at);
create table if not exists password_reset_tokens (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references users(id) on delete cascade,
  token_hash char(64) not null unique, expires_at timestamptz not null, used_at timestamptz,
  created_at timestamptz not null default now()
);
create table if not exists integration_outbox (
  id uuid primary key default gen_random_uuid(), event_type varchar(100) not null, aggregate_id uuid not null,
  aggregate_version bigint not null, payload jsonb not null, status varchar(20) not null default 'PENDING',
  attempts integer not null default 0, occurred_at timestamptz not null default now(), published_at timestamptz,
  next_attempt_at timestamptz
);
create index if not exists idx_identity_outbox_pending on integration_outbox(status,next_attempt_at,occurred_at);

create or replace function identity.emit_user_event() returns trigger language plpgsql as $$
declare event_name text;
begin
  event_name := case when tg_op='INSERT' then 'identity.user.created.v1' else 'identity.user.updated.v1' end;
  insert into identity.integration_outbox(event_type,aggregate_id,aggregate_version,payload)
  values(event_name,new.id,new.version,jsonb_build_object('eventId',gen_random_uuid(),'eventType',event_name,
    'eventVersion',1,'userId',new.id,'role',new.role_code,'status',new.status,'version',new.version,
    'occurredAt',now(),'traceId',coalesce(nullif(current_setting('app.correlation_id',true),''),'database-trigger')));
  return new;
end $$;
drop trigger if exists trg_identity_user_event on users;
create trigger trg_identity_user_event after insert or update of full_name,phone,role_code,status on users
for each row execute function identity.emit_user_event();

reset role;
alter schema identity owner to identity_owner;
revoke all on schema public from identity_app;
grant usage on schema identity to identity_app;
grant select,insert,update,delete on all tables in schema identity to identity_app;
grant usage,select on all sequences in schema identity to identity_app;
alter default privileges for role identity_owner in schema identity grant select,insert,update,delete on tables to identity_app;
alter default privileges for role identity_owner in schema identity grant usage,select on sequences to identity_app;
comment on schema identity is 'Identity-owned users, identities, addresses and authentication tokens.';
