do $$ begin
  if not exists(select 1 from pg_roles where rolname='sales_owner') then create role sales_owner nologin; end if;
  if not exists(select 1 from pg_roles where rolname='sales_app') then create role sales_app nologin; end if;
end $$;
create schema if not exists sales authorization sales_owner;
set role sales_owner;
set search_path=sales,pg_catalog;

create sequence if not exists orders_invoice_number_seq start with 1;
create table if not exists carts (
  id uuid primary key default gen_random_uuid(), user_ref uuid, session_id varchar(255),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  check(num_nonnulls(user_ref,session_id)=1), check(session_id is null or btrim(session_id)<>'')
);
create unique index if not exists uq_sales_carts_user on carts(user_ref) where user_ref is not null;
create unique index if not exists uq_sales_carts_session on carts(session_id) where session_id is not null;
create table if not exists cart_items (
  id uuid primary key default gen_random_uuid(), cart_id uuid not null references carts(id) on delete cascade,
  variant_ref uuid not null, quantity integer not null check(quantity>0), created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(), unique(cart_id,variant_ref)
);
create table if not exists orders (
  id uuid primary key, order_code varchar(50) not null unique, user_ref uuid not null,
  reservation_ref uuid, address_snapshot_json jsonb not null default '{}'::jsonb,
  subtotal_amount numeric(14,2) not null check(subtotal_amount>=0), shipping_fee numeric(14,2) not null default 0,
  discount_amount numeric(14,2) not null default 0, total_amount numeric(14,2) not null,
  currency_code char(3) not null default 'VND' check(currency_code='VND'),
  payment_method varchar(20) not null check(payment_method in ('COD','VIETQR')),
  order_status varchar(30) not null default 'PENDING_CONFIRMATION',
  payment_status varchar(30) not null default 'UNPAID', note text, internal_note text,
  cancellation_requested_by varchar(20), cancellation_reason text, cancellation_requested_at timestamptz,
  data_source varchar(20) not null default 'REAL', placed_at timestamptz not null default now(),
  delivered_at timestamptz, return_eligible_until timestamptz, invoice_number varchar(30) unique,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  check(total_amount=subtotal_amount+shipping_fee-discount_amount),
  check(order_status in ('PENDING_CONFIRMATION','CONFIRMED','PACKING','SHIPPING','DELIVERED','CANCELLED')),
  check(payment_status in ('UNPAID','PENDING','PAID','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED'))
);
create index if not exists idx_sales_orders_user on orders(user_ref,placed_at desc);
create table if not exists order_items (
  id uuid primary key, order_id uuid not null references orders(id), product_ref uuid not null, variant_ref uuid not null,
  product_name_snapshot varchar(255) not null, sku_snapshot varchar(100) not null,
  size_snapshot varchar(100), color_snapshot varchar(100), unit_price_snapshot numeric(14,2) not null check(unit_price_snapshot>=0),
  quantity integer not null check(quantity>0), line_total numeric(14,2) not null,
  discount_allocated numeric(14,2) not null default 0, net_line_amount numeric(14,2) not null,
  return_pending_quantity integer not null default 0, returned_quantity integer not null default 0,
  unique(id,order_id), check(line_total=unit_price_snapshot*quantity),
  check(net_line_amount=line_total-discount_allocated)
);
create table if not exists order_status_history (
  id uuid primary key default gen_random_uuid(), order_id uuid not null references orders(id), from_status varchar(30),
  to_status varchar(30) not null, reason text, actor_ref uuid, occurred_at timestamptz not null default now()
);
create table if not exists payments (
  id uuid primary key, order_id uuid not null references orders(id), provider varchar(20) not null,
  transaction_ref varchar(150) not null, amount numeric(14,2) not null check(amount>0), status varchar(30) not null,
  raw_payload_json jsonb, gateway_transaction_no varchar(100), bank_code varchar(30), transaction_date varchar(30),
  expires_at timestamptz, paid_at timestamptz, created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(), unique(provider,transaction_ref), unique(id,order_id),
  check(provider in ('COD','BANK_TRANSFER')), check(status in ('UNPAID','PENDING','PAID','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED'))
);
create index if not exists idx_sales_payments_order on payments(order_id,created_at desc);
create table if not exists payment_events (
  id uuid primary key default gen_random_uuid(), payment_id uuid not null references payments(id), provider varchar(20) not null,
  provider_event_key char(64) not null, event_type varchar(50) not null, verified boolean not null,
  payload_redacted jsonb, received_at timestamptz not null default now(), unique(provider,provider_event_key)
);
create table if not exists returns (
  id uuid primary key, order_id uuid not null references orders(id), user_ref uuid not null, return_code varchar(50) not null unique,
  status varchar(30) not null default 'REQUESTED', reason varchar(100) not null, description text,
  requested_at timestamptz not null default now(), approved_at timestamptz, received_at timestamptz,
  resolved_at timestamptz, handled_by_ref uuid, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(id,order_id)
);
create table if not exists return_items (
  id uuid primary key, return_id uuid not null, order_id uuid not null, order_item_id uuid not null,
  quantity integer not null check(quantity>0), reason varchar(100), condition_status varchar(30), resolution varchar(30),
  refund_amount numeric(14,2), restocked boolean not null default false, created_at timestamptz not null default now(),
  foreign key(return_id,order_id) references returns(id,order_id), foreign key(order_item_id,order_id) references order_items(id,order_id),
  unique(return_id,order_item_id)
);
create table if not exists refunds (
  id uuid primary key, return_id uuid, order_id uuid not null references orders(id), payment_id uuid,
  refund_code varchar(50) not null unique, amount numeric(14,2) not null check(amount>0),
  shipping_refund_amount numeric(14,2) not null default 0, provider varchar(20) not null,
  status varchar(20) not null default 'PENDING', reason text, gateway_request_id varchar(64) unique,
  gateway_transaction_no varchar(100), gateway_response_json jsonb, manual_reference varchar(100), manual_note text,
  refund_bank_code varchar(20), refund_bank_name varchar(100), refund_account_number varchar(30), refund_account_name varchar(100),
  refunded_at timestamptz, created_by_ref uuid, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  foreign key(payment_id,order_id) references payments(id,order_id),
  check(provider in ('COD','BANK_TRANSFER','MANUAL')), check(status in ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED'))
);
create table if not exists bank_transactions (
  id uuid primary key default gen_random_uuid(), source varchar(20) not null default 'SEPAY',
  external_transaction_id varchar(100) not null, bank_reference varchar(100), account_number varchar(50) not null,
  amount numeric(14,2) not null, description text, transaction_time timestamptz not null, running_balance numeric(14,2),
  payment_id uuid references payments(id), refund_id uuid references refunds(id), match_status varchar(30) not null default 'UNMATCHED',
  match_reason varchar(100), raw_payload_redacted jsonb, reconciled_at timestamptz, created_at timestamptz not null default now(),
  unique(source,external_transaction_id)
);
create table if not exists shipping_methods (
  id uuid primary key, code varchar(50) not null unique, name varchar(150) not null, base_fee numeric(14,2) not null,
  status varchar(20) not null default 'ACTIVE', created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists shipments (
  id uuid primary key, order_id uuid not null references orders(id), shipping_method_id uuid references shipping_methods(id),
  carrier varchar(100), tracking_number varchar(100), status varchar(30) not null default 'PENDING',
  shipped_at timestamptz, delivered_at timestamptz, created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists notifications (
  id uuid primary key default gen_random_uuid(), user_ref uuid not null, order_id uuid references orders(id),
  type varchar(50) not null, channel varchar(20) not null, title varchar(200) not null, content text not null,
  status varchar(20) not null default 'PENDING', sent_at timestamptz, created_at timestamptz not null default now()
);
create table if not exists integration_outbox (
  id uuid primary key default gen_random_uuid(), event_type varchar(100) not null, aggregate_type varchar(50) not null,
  aggregate_id uuid not null, aggregate_version bigint not null default 1, payload jsonb not null,
  status varchar(20) not null default 'PENDING', attempts integer not null default 0,
  occurred_at timestamptz not null default now(), published_at timestamptz, next_attempt_at timestamptz
);
create index if not exists idx_sales_outbox_pending on integration_outbox(status,next_attempt_at,occurred_at);

reset role;
alter schema sales owner to sales_owner;
revoke all on schema public from sales_app;
grant usage on schema sales to sales_app;
grant select,insert,update,delete on all tables in schema sales to sales_app;
grant usage,select on all sequences in schema sales to sales_app;
alter default privileges for role sales_owner in schema sales grant select,insert,update,delete on tables to sales_app;
alter default privileges for role sales_owner in schema sales grant usage,select on sequences to sales_app;
comment on schema sales is 'Sales-owned carts, orders, payments, SePay reconciliation, shipping, returns, refunds and financial notifications.';
