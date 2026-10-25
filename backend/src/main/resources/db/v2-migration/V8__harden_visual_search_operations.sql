-- Database V2 - visual-search operational hardening.
-- Keeps the immutable model/vector foundation from V6 while adding the
-- minimum retry, audit-free telemetry and job correlation fields required by
-- the visual-search worker and admin dashboard.

set search_path = ai_retrieval, extensions, public;

alter table ai_retrieval.product_image_embeddings
    add column if not exists color_signature double precision[],
    add column if not exists failure_code varchar(100),
    add column if not exists last_attempt_at timestamptz;

alter table ai_retrieval.indexing_job_items
    add column if not exists event_id uuid,
    add column if not exists source_provider varchar(40),
    add column if not exists failure_code varchar(100);

create unique index if not exists uq_indexing_job_items_event
    on ai_retrieval.indexing_job_items(event_id)
    where event_id is not null;

alter table ai_retrieval.indexing_jobs
    add column if not exists requested_by uuid,
    add column if not exists source_counts jsonb not null default '{}'::jsonb;

create table if not exists ai_retrieval.usage_events (
    id uuid primary key default gen_random_uuid(),
    model_id uuid not null,
    provider varchar(80) not null,
    model_name varchar(200) not null,
    operation varchar(40) not null,
    image_pixels bigint not null default 0,
    text_tokens bigint not null default 0,
    estimated_cost_usd numeric(14,6) not null default 0,
    latency_ms integer not null,
    success boolean not null,
    error_code varchar(100),
    occurred_at timestamptz not null default now(),
    constraint fk_usage_events_model foreign key (model_id)
        references ai_retrieval.embedding_models(id) on delete restrict,
    constraint chk_usage_events_operation check (
        operation in ('QUERY_EMBEDDING', 'DOCUMENT_EMBEDDING')
    ),
    constraint chk_usage_events_non_negative check (
        image_pixels >= 0 and text_tokens >= 0
        and estimated_cost_usd >= 0 and latency_ms >= 0
    ),
    constraint chk_usage_events_error check (
        success or nullif(btrim(error_code), '') is not null
    )
);

create index if not exists idx_usage_events_time_operation
    on ai_retrieval.usage_events(occurred_at desc, operation);

comment on table ai_retrieval.usage_events is
    'Aggregate-safe provider usage telemetry. Query images, tokens, secrets and user identifiers are never stored.';
