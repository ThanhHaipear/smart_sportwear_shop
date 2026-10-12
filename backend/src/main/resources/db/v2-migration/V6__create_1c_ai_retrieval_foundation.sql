-- Database V2 - batch 1C.1-1C.2
-- AI Retrieval model registry, catalog read model, product embeddings and indexing jobs.
-- Product/image IDs from Commerce are logical references; there are no cross-schema FKs.

create schema if not exists extensions;
create extension if not exists vector with schema extensions;
create schema if not exists ai_retrieval;

set search_path = ai_retrieval, extensions, public;

create function ai_retrieval.set_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

-- ============================================================================
-- 1C.1 Model registry and catalog read model
-- ============================================================================

create table ai_retrieval.embedding_models (
    id uuid primary key default gen_random_uuid(),
    modality varchar(30) not null,
    provider varchar(80) not null,
    model_name varchar(200) not null,
    model_version varchar(120) not null,
    dimensions integer not null,
    distance_metric varchar(20) not null default 'COSINE',
    normalization varchar(20) not null default 'L2',
    preprocessing_version varchar(50) not null,
    status varchar(20) not null default 'DRAFT',
    activated_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_embedding_models_modality check (
        modality in ('PRODUCT_TEXT', 'PRODUCT_IMAGE', 'KNOWLEDGE')
    ),
    constraint chk_embedding_models_text_fields check (
        provider = btrim(provider) and provider <> ''
        and model_name = btrim(model_name) and model_name <> ''
        and model_version = btrim(model_version) and model_version <> ''
        and preprocessing_version = btrim(preprocessing_version) and preprocessing_version <> ''
    ),
    constraint chk_embedding_models_dimensions check (dimensions > 0 and dimensions <= 4096),
    constraint chk_embedding_models_metric check (distance_metric = 'COSINE'),
    constraint chk_embedding_models_normalization check (normalization = 'L2'),
    constraint chk_embedding_models_status check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    constraint chk_embedding_models_activation check (
        (status = 'DRAFT' and activated_at is null)
        or (status in ('ACTIVE', 'RETIRED') and activated_at is not null)
    ),
    constraint uq_embedding_models_version unique (
        modality, provider, model_name, model_version, preprocessing_version
    )
);

create unique index uq_embedding_models_active_modality
    on ai_retrieval.embedding_models (modality)
    where status = 'ACTIVE';
create index idx_embedding_models_status
    on ai_retrieval.embedding_models (status, modality);

create trigger trg_embedding_models_updated_at
before update on ai_retrieval.embedding_models
for each row execute function ai_retrieval.set_updated_at();

create table ai_retrieval.product_search_documents (
    product_id uuid primary key,
    name varchar(255) not null,
    document_text text not null,
    document_hash char(64) not null,
    search_vector tsvector generated always as (
        to_tsvector('simple'::regconfig, name || ' ' || document_text)
    ) stored,
    category_code varchar(80),
    brand_code varchar(80),
    product_type_code varchar(80),
    gender_code varchar(20),
    sport_codes text[] not null default '{}',
    use_case_codes text[] not null default '{}',
    material_codes text[] not null default '{}',
    source_version bigint not null,
    indexed_at timestamptz not null default now(),
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_product_search_documents_name check (name = btrim(name) and name <> ''),
    constraint chk_product_search_documents_text check (btrim(document_text) <> ''),
    constraint chk_product_search_documents_hash check (document_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_product_search_documents_source_version check (source_version >= 0),
    constraint chk_product_search_documents_status check (status in ('ACTIVE', 'INACTIVE', 'DELETED')),
    constraint chk_product_search_documents_arrays check (
        array_position(sport_codes, null) is null
        and array_position(use_case_codes, null) is null
        and array_position(material_codes, null) is null
    )
);

create index idx_product_search_documents_fts
    on ai_retrieval.product_search_documents using gin (search_vector);
create index idx_product_search_documents_filters
    on ai_retrieval.product_search_documents
        (status, category_code, brand_code, product_type_code, gender_code);
create index idx_product_search_documents_sports
    on ai_retrieval.product_search_documents using gin (sport_codes);
create index idx_product_search_documents_source_version
    on ai_retrieval.product_search_documents (source_version);

create trigger trg_product_search_documents_updated_at
before update on ai_retrieval.product_search_documents
for each row execute function ai_retrieval.set_updated_at();

create table ai_retrieval.search_variant_facets (
    variant_id uuid primary key,
    product_id uuid not null,
    sku varchar(100) not null,
    size_code varchar(80) not null,
    color_code varchar(80) not null,
    color_family_code varchar(80),
    price numeric(14,2) not null,
    available_quantity integer not null,
    status varchar(20) not null default 'ACTIVE',
    source_version bigint not null,
    indexed_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_search_variant_facets_product foreign key (product_id)
        references ai_retrieval.product_search_documents (product_id) on delete cascade,
    constraint uq_search_variant_facets_product_variant unique (product_id, variant_id),
    constraint chk_search_variant_facets_sku check (sku = btrim(sku) and sku <> ''),
    constraint chk_search_variant_facets_size check (size_code = btrim(size_code) and size_code <> ''),
    constraint chk_search_variant_facets_color check (color_code = btrim(color_code) and color_code <> ''),
    constraint chk_search_variant_facets_price check (price >= 0),
    constraint chk_search_variant_facets_available check (available_quantity >= 0),
    constraint chk_search_variant_facets_status check (status in ('ACTIVE', 'INACTIVE', 'DELETED')),
    constraint chk_search_variant_facets_source_version check (source_version >= 0)
);

create unique index uq_search_variant_facets_sku
    on ai_retrieval.search_variant_facets (sku);
create index idx_search_variant_facets_product
    on ai_retrieval.search_variant_facets (product_id, status);
create index idx_search_variant_facets_filters
    on ai_retrieval.search_variant_facets
        (status, size_code, color_code, color_family_code, price, available_quantity);
create index idx_search_variant_facets_source_version
    on ai_retrieval.search_variant_facets (source_version);

create trigger trg_search_variant_facets_updated_at
before update on ai_retrieval.search_variant_facets
for each row execute function ai_retrieval.set_updated_at();

create table ai_retrieval.processed_events (
    consumer_name varchar(80) not null,
    event_id uuid not null,
    aggregate_version bigint,
    processed_at timestamptz not null default now(),
    primary key (consumer_name, event_id),
    constraint chk_processed_events_consumer check (
        consumer_name = btrim(consumer_name) and consumer_name <> ''
    ),
    constraint chk_processed_events_version check (
        aggregate_version is null or aggregate_version >= 0
    )
);

create index idx_processed_events_time
    on ai_retrieval.processed_events (processed_at);

-- ============================================================================
-- 1C.2 Product embeddings
-- ============================================================================

create table ai_retrieval.product_text_embeddings (
    product_id uuid not null,
    model_id uuid not null,
    document_hash char(64),
    embedding extensions.vector(1024),
    status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    last_error text,
    embedded_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (product_id, model_id),
    constraint fk_product_text_embeddings_product foreign key (product_id)
        references ai_retrieval.product_search_documents (product_id) on delete cascade,
    constraint fk_product_text_embeddings_model foreign key (model_id)
        references ai_retrieval.embedding_models (id) on delete restrict,
    constraint chk_product_text_embeddings_hash check (
        document_hash is null or document_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint chk_product_text_embeddings_status check (
        status in ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'STALE')
    ),
    constraint chk_product_text_embeddings_attempts check (attempts between 0 and 20),
    constraint chk_product_text_embeddings_ready check (
        status <> 'READY'
        or (document_hash is not null and embedding is not null and embedded_at is not null
            and last_error is null)
    ),
    constraint chk_product_text_embeddings_failed check (
        status <> 'FAILED' or nullif(btrim(last_error), '') is not null
    ),
    constraint chk_product_text_embeddings_l2 check (
        embedding is null or abs((embedding <#> embedding) + 1.0) <= 0.02
    )
);

create index idx_product_text_embeddings_model_status
    on ai_retrieval.product_text_embeddings (model_id, status, product_id);

create trigger trg_product_text_embeddings_updated_at
before update on ai_retrieval.product_text_embeddings
for each row execute function ai_retrieval.set_updated_at();

create table ai_retrieval.product_image_embeddings (
    image_id uuid not null,
    product_id uuid not null,
    model_id uuid not null,
    source_public_id varchar(255) not null,
    source_asset_version varchar(50) not null,
    image_hash char(64),
    embedding extensions.vector(768),
    status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    last_error text,
    embedded_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (image_id, model_id),
    constraint fk_product_image_embeddings_model foreign key (model_id)
        references ai_retrieval.embedding_models (id) on delete restrict,
    constraint chk_product_image_embeddings_source check (
        source_public_id = btrim(source_public_id) and source_public_id <> ''
        and source_asset_version = btrim(source_asset_version) and source_asset_version <> ''
    ),
    constraint chk_product_image_embeddings_hash check (
        image_hash is null or image_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint chk_product_image_embeddings_status check (
        status in ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'STALE')
    ),
    constraint chk_product_image_embeddings_attempts check (attempts between 0 and 20),
    constraint chk_product_image_embeddings_ready check (
        status <> 'READY'
        or (image_hash is not null and embedding is not null and embedded_at is not null
            and last_error is null)
    ),
    constraint chk_product_image_embeddings_failed check (
        status <> 'FAILED' or nullif(btrim(last_error), '') is not null
    ),
    constraint chk_product_image_embeddings_l2 check (
        embedding is null or abs((embedding <#> embedding) + 1.0) <= 0.02
    )
);

create index idx_product_image_embeddings_model_status
    on ai_retrieval.product_image_embeddings (model_id, status, product_id);
create index idx_product_image_embeddings_product
    on ai_retrieval.product_image_embeddings (product_id, status);

create trigger trg_product_image_embeddings_updated_at
before update on ai_retrieval.product_image_embeddings
for each row execute function ai_retrieval.set_updated_at();

create function ai_retrieval.validate_product_text_embedding()
returns trigger
language plpgsql
set search_path = ai_retrieval, extensions, pg_catalog
as $$
declare
    registered_modality varchar(30);
    registered_dimensions integer;
    current_document_hash char(64);
begin
    select modality, dimensions
      into registered_modality, registered_dimensions
      from ai_retrieval.embedding_models
     where id = new.model_id;

    if registered_modality is distinct from 'PRODUCT_TEXT' or registered_dimensions <> 1024 then
        raise exception using
            errcode = '23514',
            message = 'product_text_embeddings requires a PRODUCT_TEXT model with 1024 dimensions';
    end if;

    if new.status = 'READY' then
        select document_hash into current_document_hash
          from ai_retrieval.product_search_documents
         where product_id = new.product_id;
        if current_document_hash is distinct from new.document_hash then
            raise exception using
                errcode = '23514',
                message = 'READY text embedding document_hash must match the current search document';
        end if;
    end if;
    return new;
end;
$$;

create trigger trg_validate_product_text_embedding
before insert or update on ai_retrieval.product_text_embeddings
for each row execute function ai_retrieval.validate_product_text_embedding();

create function ai_retrieval.validate_product_image_embedding()
returns trigger
language plpgsql
set search_path = ai_retrieval, extensions, pg_catalog
as $$
declare
    registered_modality varchar(30);
    registered_dimensions integer;
begin
    select modality, dimensions
      into registered_modality, registered_dimensions
      from ai_retrieval.embedding_models
     where id = new.model_id;

    if registered_modality is distinct from 'PRODUCT_IMAGE' or registered_dimensions <> 768 then
        raise exception using
            errcode = '23514',
            message = 'product_image_embeddings requires a PRODUCT_IMAGE model with 768 dimensions';
    end if;
    return new;
end;
$$;

create trigger trg_validate_product_image_embedding
before insert or update on ai_retrieval.product_image_embeddings
for each row execute function ai_retrieval.validate_product_image_embedding();

create function ai_retrieval.protect_embedding_model_identity()
returns trigger
language plpgsql
set search_path = ai_retrieval, pg_catalog
as $$
begin
    if (old.modality, old.provider, old.model_name, old.model_version, old.dimensions,
        old.distance_metric, old.normalization, old.preprocessing_version)
       is distinct from
       (new.modality, new.provider, new.model_name, new.model_version, new.dimensions,
        new.distance_metric, new.normalization, new.preprocessing_version)
       and (
           exists (select 1 from ai_retrieval.product_text_embeddings where model_id = old.id)
           or exists (select 1 from ai_retrieval.product_image_embeddings where model_id = old.id)
       ) then
        raise exception using
            errcode = '55000',
            message = 'An embedding model already referenced by vectors is immutable; create a new model version';
    end if;
    return new;
end;
$$;

create trigger trg_protect_embedding_model_identity
before update on ai_retrieval.embedding_models
for each row execute function ai_retrieval.protect_embedding_model_identity();

-- ============================================================================
-- Indexing orchestration shared by 1C.1-1C.2 and later 1C batches
-- ============================================================================

create table ai_retrieval.indexing_jobs (
    id uuid primary key default gen_random_uuid(),
    job_type varchar(60) not null,
    model_id uuid,
    status varchar(30) not null default 'PENDING',
    total_count integer not null default 0,
    success_count integer not null default 0,
    failed_count integer not null default 0,
    requested_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    error_summary jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_indexing_jobs_model foreign key (model_id)
        references ai_retrieval.embedding_models (id) on delete restrict,
    constraint chk_indexing_jobs_type check (job_type = btrim(job_type) and job_type <> ''),
    constraint chk_indexing_jobs_status check (
        status in ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
    ),
    constraint chk_indexing_jobs_counts check (
        total_count >= 0 and success_count >= 0 and failed_count >= 0
        and success_count + failed_count <= total_count
    ),
    constraint chk_indexing_jobs_times check (
        (started_at is null or started_at >= requested_at)
        and (finished_at is null or finished_at >= coalesce(started_at, requested_at))
    ),
    constraint chk_indexing_jobs_lifecycle check (
        (status = 'PENDING' and started_at is null and finished_at is null)
        or (status = 'RUNNING' and started_at is not null and finished_at is null)
        or (status in ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED')
            and started_at is not null and finished_at is not null)
        or (status = 'CANCELLED' and finished_at is not null)
    ),
    constraint chk_indexing_jobs_completion check (
        status <> 'COMPLETED' or (success_count = total_count and failed_count = 0)
    ),
    constraint chk_indexing_jobs_partial check (
        status <> 'PARTIALLY_COMPLETED'
        or (success_count > 0 and failed_count > 0 and success_count + failed_count = total_count)
    ),
    constraint chk_indexing_jobs_error_summary check (
        error_summary is null or jsonb_typeof(error_summary) = 'object'
    )
);

create index idx_indexing_jobs_queue
    on ai_retrieval.indexing_jobs (status, requested_at)
    where status in ('PENDING', 'RUNNING');
create index idx_indexing_jobs_model_time
    on ai_retrieval.indexing_jobs (model_id, requested_at desc)
    where model_id is not null;

create trigger trg_indexing_jobs_updated_at
before update on ai_retrieval.indexing_jobs
for each row execute function ai_retrieval.set_updated_at();

create table ai_retrieval.indexing_job_items (
    id uuid primary key default gen_random_uuid(),
    job_id uuid not null,
    entity_type varchar(40) not null,
    entity_id uuid not null,
    status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_indexing_job_items_job foreign key (job_id)
        references ai_retrieval.indexing_jobs (id) on delete cascade,
    constraint uq_indexing_job_items_entity unique (job_id, entity_type, entity_id),
    constraint chk_indexing_job_items_entity_type check (
        entity_type in ('PRODUCT', 'PRODUCT_IMAGE', 'KNOWLEDGE_DOCUMENT', 'KNOWLEDGE_CHUNK')
    ),
    constraint chk_indexing_job_items_status check (
        status in ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'SKIPPED')
    ),
    constraint chk_indexing_job_items_attempts check (attempts between 0 and 20),
    constraint chk_indexing_job_items_error check (
        status <> 'FAILED' or nullif(btrim(last_error), '') is not null
    )
);

create index idx_indexing_job_items_claim
    on ai_retrieval.indexing_job_items (job_id, status, updated_at, id)
    where status in ('PENDING', 'PROCESSING');

create trigger trg_indexing_job_items_updated_at
before update on ai_retrieval.indexing_job_items
for each row execute function ai_retrieval.set_updated_at();

-- Exact cosine search is deliberate for the 30-50 product pilot. There is no
-- HNSW or IVFFlat index in this migration; ORDER BY <=> performs an exact scan.
create function ai_retrieval.search_product_text_exact(
    p_model_id uuid,
    p_query_embedding extensions.vector(1024),
    p_match_count integer default 20
)
returns table (product_id uuid, distance double precision, similarity double precision)
language sql
stable
parallel safe
set search_path = ai_retrieval, extensions, pg_catalog
as $$
    select embedding.product_id,
           embedding.embedding <=> p_query_embedding as distance,
           1.0 - (embedding.embedding <=> p_query_embedding) as similarity
      from ai_retrieval.product_text_embeddings embedding
      join ai_retrieval.product_search_documents document
        on document.product_id = embedding.product_id
     where embedding.model_id = p_model_id
       and embedding.status = 'READY'
       and document.status = 'ACTIVE'
     order by embedding.embedding <=> p_query_embedding, embedding.product_id
     limit greatest(1, least(coalesce(p_match_count, 20), 100));
$$;

create function ai_retrieval.search_product_images_exact(
    p_model_id uuid,
    p_query_embedding extensions.vector(768),
    p_match_count integer default 20
)
returns table (
    image_id uuid,
    product_id uuid,
    distance double precision,
    similarity double precision
)
language sql
stable
parallel safe
set search_path = ai_retrieval, extensions, pg_catalog
as $$
    select embedding.image_id,
           embedding.product_id,
           embedding.embedding <=> p_query_embedding as distance,
           1.0 - (embedding.embedding <=> p_query_embedding) as similarity
      from ai_retrieval.product_image_embeddings embedding
     where embedding.model_id = p_model_id
       and embedding.status = 'READY'
     order by embedding.embedding <=> p_query_embedding, embedding.image_id
     limit greatest(1, least(coalesce(p_match_count, 20), 100));
$$;

comment on schema ai_retrieval is
    'AI Retrieval-owned read models, embeddings and indexing orchestration.';
comment on table ai_retrieval.processed_events is
    'Insert in the same transaction as the read-model update for idempotent event consumption.';
comment on function ai_retrieval.search_product_text_exact(uuid, extensions.vector, integer) is
    'Exact cosine product-text search for the pilot; intentionally has no ANN index.';
comment on function ai_retrieval.search_product_images_exact(uuid, extensions.vector, integer) is
    'Exact cosine product-image search for the pilot; intentionally has no ANN index.';
