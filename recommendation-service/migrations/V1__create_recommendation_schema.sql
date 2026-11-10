do $$ begin
    if not exists (select 1 from pg_roles where rolname = 'recommendation_owner') then
        create role recommendation_owner nologin;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'recommendation_app') then
        create role recommendation_app nologin;
    end if;
end $$;

create schema if not exists recommendation authorization recommendation_owner;
set role recommendation_owner;
set search_path = recommendation, pg_catalog;

create table if not exists catalog_products (
    product_id uuid primary key,
    source_version bigint not null check (source_version >= 0),
    status varchar(20) not null,
    available_quantity integer not null default 0 check (available_quantity >= 0),
    purchase_count bigint not null default 0 check (purchase_count >= 0),
    snapshot jsonb not null check (jsonb_typeof(snapshot) = 'object'),
    updated_at timestamptz not null default now()
);
create index if not exists idx_recommendation_catalog_popular
    on catalog_products(status, purchase_count desc) where available_quantity > 0;

create table if not exists user_interactions (
    event_id uuid primary key,
    occurred_at timestamptz not null,
    user_ref varchar(100),
    anonymous_id varchar(100),
    event_type varchar(50) not null,
    product_id uuid,
    variant_id uuid,
    request_id uuid,
    source varchar(50) not null,
    data_source varchar(20) not null check (data_source in ('REAL','DEMO','SYNTHETIC')),
    metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
    anonymized_at timestamptz,
    constraint chk_recommendation_interaction_identity check (num_nonnulls(user_ref, anonymous_id) >= 1)
);
create index if not exists idx_recommendation_interactions_time on user_interactions(occurred_at desc);
create index if not exists idx_recommendation_interactions_product on user_interactions(product_id, occurred_at desc);

create table if not exists order_baskets (
    order_id uuid primary key,
    user_ref varchar(100),
    product_ids uuid[] not null check (cardinality(product_ids) > 0),
    occurred_at timestamptz not null
);

create table if not exists recommendation_models (
    id uuid primary key default gen_random_uuid(),
    algorithm varchar(50) not null,
    version varchar(50) not null,
    config jsonb not null default '{}'::jsonb,
    training_cutoff_at timestamptz,
    training_event_checksum char(64),
    trained_at timestamptz,
    status varchar(20) not null check (status in ('DRAFT','ACTIVE','ARCHIVED')),
    unique(algorithm, version)
);

create table if not exists association_rules (
    id bigint generated always as identity primary key,
    antecedent_product_id uuid not null,
    consequent_product_id uuid not null,
    support double precision not null check (support between 0 and 1),
    confidence double precision not null check (confidence between 0 and 1),
    lift double precision not null check (lift >= 0),
    pair_count bigint not null check (pair_count > 0),
    model_version varchar(50) not null,
    status varchar(20) not null check (status in ('ACTIVE','ARCHIVED')),
    created_at timestamptz not null default now(),
    unique(antecedent_product_id, consequent_product_id, model_version)
);
create index if not exists idx_recommendation_active_rules
    on association_rules(antecedent_product_id, confidence desc, lift desc) where status='ACTIVE';

create table if not exists recommendation_requests (
    id uuid primary key default gen_random_uuid(),
    model_version varchar(50) not null,
    user_ref varchar(100),
    context_type varchar(30) not null,
    source_product_ids uuid[] not null default '{}',
    served_at timestamptz not null default now(),
    latency_ms integer check (latency_ms >= 0)
);
create table if not exists recommendation_items (
    request_id uuid not null references recommendation_requests(id) on delete cascade,
    rank integer not null check (rank > 0),
    product_id uuid not null,
    score double precision not null,
    reason_code varchar(50) not null,
    primary key(request_id, rank),
    unique(request_id, product_id)
);

create table if not exists recommendation_logs (
    event_id uuid primary key default gen_random_uuid(),
    request_id uuid,
    user_ref varchar(100),
    anonymous_id varchar(100),
    event_type varchar(30) not null check (event_type in ('IMPRESSION','CLICK','ADD_TO_CART','PURCHASE')),
    source_product_ids uuid[] not null default '{}',
    recommended_product_id uuid not null,
    position_index integer check (position_index is null or position_index >= 0),
    algorithm varchar(50) not null,
    occurred_at timestamptz not null default now()
);
create index if not exists idx_recommendation_logs_request on recommendation_logs(request_id, occurred_at desc);

create table if not exists processed_events (
    consumer_name varchar(100) not null,
    event_id uuid not null,
    source_version bigint not null default 0 check (source_version >= 0),
    processed_at timestamptz not null default now(),
    primary key(consumer_name, event_id)
);

create table if not exists evaluation_runs (
    id uuid primary key default gen_random_uuid(),
    model_version varchar(50) not null,
    dataset_version varchar(100) not null,
    k integer not null check (k > 0),
    precision_at_k double precision not null check (precision_at_k between 0 and 1),
    recall_at_k double precision not null check (recall_at_k between 0 and 1),
    evaluated_at timestamptz not null default now()
);

create or replace function reject_recommendation_fact_mutation() returns trigger language plpgsql as $$
begin raise exception using errcode='55000', message=format('%I is append-only',tg_table_name); end;
$$;
drop trigger if exists trg_recommendation_interactions_append_only on user_interactions;
create trigger trg_recommendation_interactions_append_only before update or delete on user_interactions
    for each row execute function reject_recommendation_fact_mutation();
drop trigger if exists trg_recommendation_logs_append_only on recommendation_logs;
create trigger trg_recommendation_logs_append_only before update or delete on recommendation_logs
    for each row execute function reject_recommendation_fact_mutation();

reset role;
alter schema recommendation owner to recommendation_owner;
revoke all on schema public from recommendation_app;
grant usage on schema recommendation to recommendation_app;
grant select,insert,update,delete on all tables in schema recommendation to recommendation_app;
grant usage,select on all sequences in schema recommendation to recommendation_app;
alter default privileges for role recommendation_owner in schema recommendation
    grant select,insert,update,delete on tables to recommendation_app;
alter default privileges for role recommendation_owner in schema recommendation
    grant usage,select on sequences to recommendation_app;

comment on schema recommendation is 'Recommendation-owned data and event-derived read models; no commercial foreign keys.';
