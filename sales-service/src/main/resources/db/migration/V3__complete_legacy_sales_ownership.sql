-- Phase 10 assigns the residual core operational tables to Sales.
set role sales_owner;
set search_path=sales,pg_catalog;

create table if not exists bank_reconciliation_runs (
  id uuid primary key, source varchar(20) not null, status varchar(20) not null,
  from_time timestamptz not null, to_time timestamptz not null, cursor_id uuid,
  records_fetched integer not null, records_inserted integer not null, records_matched integer not null,
  error_code varchar(100), error_message varchar(500), started_at timestamptz not null, finished_at timestamptz,
  created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists notification_templates (
  id uuid primary key, type varchar(30) not null unique, channel varchar(20) not null,
  subject varchar(255) not null, body text not null, created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists site_settings (
  id uuid primary key, setting_key varchar(120) not null unique, setting_value text,
  value_type varchar(20) not null, description text, is_public boolean not null, updated_by_ref uuid,
  created_at timestamptz not null, updated_at timestamptz not null
);
create table if not exists audit_logs (
  id uuid primary key, actor_ref uuid, action varchar(100) not null, entity_type varchar(100) not null,
  entity_id varchar(100) not null, before_json jsonb, after_json jsonb, details_redacted jsonb,
  ip_address varchar(64), user_agent varchar(500), created_at timestamptz not null
);
create index if not exists idx_sales_audit_actor_time on audit_logs(actor_ref,created_at desc);
create index if not exists idx_sales_audit_entity_time on audit_logs(entity_type,entity_id,created_at desc);

reset role;
do $$ begin
if to_regclass('public.bank_reconciliation_runs') is not null then
  insert into sales.bank_reconciliation_runs select * from public.bank_reconciliation_runs on conflict do nothing;
end if;
if to_regclass('public.notification_templates') is not null then
  insert into sales.notification_templates select * from public.notification_templates on conflict do nothing;
end if;
if to_regclass('public.site_settings') is not null then
  insert into sales.site_settings(id,setting_key,setting_value,value_type,description,is_public,updated_by_ref,created_at,updated_at)
  select id,setting_key,setting_value,value_type,description,is_public,updated_by,created_at,updated_at
  from public.site_settings on conflict do nothing;
end if;
if to_regclass('public.audit_logs') is not null then
  insert into sales.audit_logs(id,actor_ref,action,entity_type,entity_id,before_json,after_json,details_redacted,ip_address,user_agent,created_at)
  select id,actor_user_id,action,entity_type,entity_id,before_json,after_json,details_redacted,ip_address,user_agent,created_at
  from public.audit_logs on conflict do nothing;
end if;
end $$;

grant select,insert,update,delete on all tables in schema sales to sales_app;
grant usage,select on all sequences in schema sales to sales_app;
