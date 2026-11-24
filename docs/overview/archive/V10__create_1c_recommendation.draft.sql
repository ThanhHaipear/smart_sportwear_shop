-- Database V2 - batch 1C.5
-- Recommendation model registry, served recommendation log and shared AI usage metering.
-- Product IDs from Commerce are logical references; there are no cross-schema FKs.
-- DRAFT: version number depends on the team decision about V8/V9 merge order.

set search_path = ai_retrieval, extensions, public;

-- ============================================================================
-- Recommendation model registry
-- ============================================================================

create table ai_retrieval.recommendation_models (
    id uuid primary key default gen_random_uuid(),
    algorithm varchar(30) not null,
    version varchar(50) not null,
    config jsonb not null default '{}'::jsonb,
    training_dataset_version varchar(80),
    training_event_checksum char(64),
    training_cutoff_at timestamptz,
    trained_at timestamptz,
    status varchar(20) not null default 'DRAFT',
    activated_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_recommendation_models_version unique (algorithm, version),
    constraint chk_recommendation_models_algorithm check (
        algorithm in ('POPULARITY', 'CONTENT_BASED', 'ASSOCIATION', 'HYBRID')
    ),
    constraint chk_recommendation_models_version check (version = btrim(version) and version <> ''),
    constraint chk_recommendation_models_config check (jsonb_typeof(config) = 'object'),
    constraint chk_recommendation_models_dataset_version check (
        training_dataset_version is null
        or (training_dataset_version = btrim(training_dataset_version) and training_dataset_version <> '')
    ),
    constraint chk_recommendation_models_checksum check (
        training_event_checksum is null or training_event_checksum ~ '^[0-9a-f]{64}$'
    ),
    -- cutoff and checksum describe the same training snapshot, so they come as a pair
    constraint chk_recommendation_models_training_pair check (
        (training_cutoff_at is null) = (training_event_checksum is null)
    ),
    -- every algorithm except pure content-based learns from behaviour events
    constraint chk_recommendation_models_behavior_snapshot check (
        algorithm = 'CONTENT_BASED' or status = 'DRAFT'
        or (training_event_checksum is not null and trained_at is not null)
    ),
    constraint chk_recommendation_models_trained_after_cutoff check (
        trained_at is null or training_cutoff_at is null or trained_at >= training_cutoff_at
    ),
    constraint chk_recommendation_models_status check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    constraint chk_recommendation_models_activation check (
        (status = 'DRAFT' and activated_at is null)
        or (status in ('ACTIVE', 'RETIRED') and activated_at is not null)
    )
);

create unique index uq_recommendation_models_active_algorithm
    on ai_retrieval.recommendation_models (algorithm)
    where status = 'ACTIVE';

create trigger trg_recommendation_models_updated_at
before update on ai_retrieval.recommendation_models
for each row execute function ai_retrieval.set_updated_at();

create function ai_retrieval.protect_recommendation_model_identity()
returns trigger
language plpgsql
set search_path = ai_retrieval, pg_catalog
as $$
begin
    if (old.algorithm, old.version, old.config, old.training_dataset_version,
        old.training_event_checksum, old.training_cutoff_at, old.trained_at)
       is distinct from
       (new.algorithm, new.version, new.config, new.training_dataset_version,
        new.training_event_checksum, new.training_cutoff_at, new.trained_at)
       and old.status <> 'DRAFT' then
        raise exception using
            errcode = '55000',
            message = 'An activated recommendation model is immutable; create a new model version';
    end if;
    return new;
end;
$$;

create trigger trg_protect_recommendation_model_identity
before update on ai_retrieval.recommendation_models
for each row execute function ai_retrieval.protect_recommendation_model_identity();

-- ============================================================================
-- Served recommendations (operational log, 30-day retention)
-- ============================================================================

create table ai_retrieval.recommendation_requests (
    id uuid primary key default gen_random_uuid(),
    model_id uuid not null,
    user_ref varchar(100),
    context_type varchar(30) not null,
    source_product_id uuid,
    context jsonb,
    served_at timestamptz not null default now(),
    latency_ms integer not null,
    constraint fk_recommendation_requests_model foreign key (model_id)
        references ai_retrieval.recommendation_models (id) on delete restrict,
    constraint chk_recommendation_requests_user_ref check (
        user_ref is null or (user_ref = btrim(user_ref) and user_ref <> '')
    ),
    constraint chk_recommendation_requests_context_type check (
        context_type in ('PRODUCT_DETAIL', 'HOME', 'CART')
    ),
    constraint chk_recommendation_requests_source_product check (
        context_type <> 'PRODUCT_DETAIL' or source_product_id is not null
    ),
    constraint chk_recommendation_requests_context check (
        context is null or jsonb_typeof(context) = 'object'
    ),
    constraint chk_recommendation_requests_latency check (latency_ms >= 0)
);

create index idx_recommendation_requests_served
    on ai_retrieval.recommendation_requests (served_at);
create index idx_recommendation_requests_model_served
    on ai_retrieval.recommendation_requests (model_id, served_at desc);

create table ai_retrieval.recommendation_items (
    request_id uuid not null,
    rank integer not null,
    product_id uuid not null,
    score numeric(12,6) not null,
    reason_code varchar(40) not null,
    primary key (request_id, rank),
    constraint fk_recommendation_items_request foreign key (request_id)
        references ai_retrieval.recommendation_requests (id) on delete cascade,
    constraint uq_recommendation_items_request_product unique (request_id, product_id),
    constraint chk_recommendation_items_rank check (rank between 1 and 100),
    constraint chk_recommendation_items_reason check (
        reason_code in ('POPULAR', 'SIMILAR_CONTENT', 'USER_PROFILE', 'BOUGHT_TOGETHER', 'HYBRID')
    )
);

create index idx_recommendation_items_product
    on ai_retrieval.recommendation_items (product_id);

-- ============================================================================
-- AI usage metering shared by search, chatbot and recommendation (90-day raw retention)
-- ============================================================================

create table ai_retrieval.usage_events (
    id uuid primary key default gen_random_uuid(),
    provider varchar(80) not null,
    model_id uuid,
    model_name varchar(200) not null,
    operation varchar(50) not null,
    request_count integer not null default 1,
    tokens bigint not null default 0,
    image_pixels bigint not null default 0,
    estimated_cost_usd numeric(14,8) not null default 0,
    latency_ms integer not null,
    success boolean not null,
    error_code varchar(100),
    occurred_at timestamptz not null default now(),
    constraint chk_usage_events_provider check (provider = btrim(provider) and provider <> ''),
    constraint chk_usage_events_model_name check (model_name = btrim(model_name) and model_name <> ''),
    constraint chk_usage_events_operation check (operation ~ '^[A-Z][A-Z0-9_]*$'),
    constraint chk_usage_events_request_count check (request_count > 0),
    constraint chk_usage_events_amounts check (
        tokens >= 0 and image_pixels >= 0 and estimated_cost_usd >= 0 and latency_ms >= 0
    ),
    constraint chk_usage_events_error check (
        (success and error_code is null)
        or (not success and nullif(btrim(error_code), '') is not null)
    )
);

create index idx_usage_events_time
    on ai_retrieval.usage_events (occurred_at);
create index idx_usage_events_operation_time
    on ai_retrieval.usage_events (provider, operation, occurred_at desc);

comment on table ai_retrieval.recommendation_models is
    'One row per algorithm/config; non-DRAFT rows are immutable so every served list can be reproduced.';
comment on table ai_retrieval.recommendation_requests is
    'Operational log of served recommendations; user_ref is pseudonymous; delete rows older than 30 days.';
comment on table ai_retrieval.recommendation_items is
    'Ranked items of a served request; removed with the parent request.';
comment on table ai_retrieval.usage_events is
    'Raw AI cost/latency events shared by all AI features; no prompts or PII; delete raw rows after 90 days.';
comment on column ai_retrieval.usage_events.model_id is
    'Logical reference to embedding_models or recommendation_models when the model is registered; null for external LLM APIs.';
