create extension if not exists vector with schema extensions;

do $$ begin
    if not exists (select 1 from pg_roles where rolname = 'search_owner') then
        create role search_owner nologin;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'search_app') then
        create role search_app nologin;
    end if;
end $$;

create schema if not exists ai_search authorization search_owner;
grant usage on schema extensions to search_owner;
set role search_owner;
set search_path = ai_search, extensions, pg_catalog;

create table if not exists ai_search.search_documents (
    product_id uuid primary key,
    source_version bigint not null check (source_version >= 0),
    status varchar(20) not null check (status in ('ACTIVE','INACTIVE','DELETED','DRAFT')),
    name varchar(255) not null,
    document_text text not null,
    document_hash char(64) not null,
    category_id uuid, category varchar(150), category_slug varchar(180),
    brand_id uuid, brand varchar(150), brand_slug varchar(180),
    product_type varchar(50), gender varchar(20),
    sport_codes text[] not null default '{}', attributes jsonb not null default '{}',
    search_vector tsvector generated always as (
        to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(document_text,''))
    ) stored,
    updated_at timestamptz not null default now()
);
create index if not exists idx_ai_search_documents_fts on ai_search.search_documents using gin(search_vector);
create index if not exists idx_ai_search_documents_filters on ai_search.search_documents(status,category_slug,brand_slug,product_type,gender);

create table if not exists ai_search.variant_facets (
    variant_id uuid primary key, product_id uuid not null references ai_search.search_documents(product_id) on delete cascade,
    sku varchar(100) not null, size_code varchar(100), color_code varchar(150), color_family varchar(30),
    price numeric(14,2) not null, available_quantity integer not null, status varchar(20) not null,
    source_version bigint not null
);
create index if not exists idx_ai_search_variant_product on ai_search.variant_facets(product_id,status,available_quantity);

create table if not exists ai_search.text_embeddings (
    product_id uuid primary key references ai_search.search_documents(product_id) on delete cascade,
    document_hash char(64) not null, model varchar(150) not null,
    embedding extensions.vector(1536) not null, status varchar(20) not null,
    updated_at timestamptz not null default now()
);
create index if not exists idx_ai_search_text_embedding on ai_search.text_embeddings using hnsw(embedding vector_cosine_ops);

create table if not exists ai_search.image_embeddings (
    image_id uuid primary key, product_id uuid not null references ai_search.search_documents(product_id) on delete cascade,
    image_url text not null, image_hash char(64) not null, model varchar(150) not null,
    embedding extensions.vector(768) not null, status varchar(20) not null,
    updated_at timestamptz not null default now()
);
create index if not exists idx_ai_search_image_embedding on ai_search.image_embeddings using hnsw(embedding vector_cosine_ops);

create table if not exists ai_search.processed_events (
    consumer_name varchar(100) not null, event_id uuid not null, aggregate_version bigint not null,
    processed_at timestamptz not null default now(), primary key(consumer_name,event_id)
);

create table if not exists ai_search.indexing_jobs (
    id uuid primary key default gen_random_uuid(), job_type varchar(50) not null,
    status varchar(30) not null, total_count integer not null default 0,
    success_count integer not null default 0, failed_count integer not null default 0,
    requested_at timestamptz not null default now(), finished_at timestamptz, error_summary jsonb
);

create or replace function ai_search.search_text(
    p_query text, p_vector extensions.vector, p_limit integer, p_filters jsonb default '{}',
    p_min_similarity real default 0.40,
    p_rrf_k integer default 60, p_keyword_weight real default 1.2, p_semantic_weight real default 1.0
) returns table(product_id uuid, keyword_rank integer, semantic_rank integer,
                semantic_score real, fusion_score double precision, matched_reasons text[])
language sql stable set search_path=ai_search,extensions,pg_catalog as $$
with eligible as (
    select d.* from ai_search.search_documents d
    where d.status='ACTIVE'
      and (coalesce(p_filters->>'categoryId','')='' or d.category_id=(p_filters->>'categoryId')::uuid)
      and (coalesce(p_filters->>'categorySlug','')='' or d.category_slug=p_filters->>'categorySlug')
      and (coalesce(p_filters->>'brandId','')='' or d.brand_id=(p_filters->>'brandId')::uuid)
      and (coalesce(p_filters->>'brandSlug','')='' or d.brand_slug=p_filters->>'brandSlug')
      and (coalesce(p_filters->>'gender','')='' or d.gender in (p_filters->>'gender','UNISEX'))
      and (coalesce(p_filters->>'sportType','')='' or p_filters->>'sportType'=any(d.sport_codes))
      and (coalesce(p_filters->>'productType','')='' or d.product_type=p_filters->>'productType')
      and exists (
          select 1 from ai_search.variant_facets f where f.product_id=d.product_id
            and f.status='ACTIVE'
            and (coalesce((p_filters->>'inStockOnly')::boolean,true)=false or f.available_quantity>0)
            and (coalesce(p_filters->>'color','')='' or f.color_family=p_filters->>'color' or f.color_code=p_filters->>'color')
            and (coalesce(p_filters->>'size','')='' or f.size_code=p_filters->>'size')
            and (p_filters->>'minPrice' is null or f.price >= (p_filters->>'minPrice')::numeric)
            and (p_filters->>'maxPrice' is null or f.price <= (p_filters->>'maxPrice')::numeric)
      )
), keyword as (
    select d.product_id, row_number() over(order by ts_rank_cd(d.search_vector, plainto_tsquery('simple',p_query)) desc)::int rank
    from eligible d
    where d.search_vector @@ plainto_tsquery('simple',p_query)
    order by rank limit p_limit
), semantic as (
    select d.product_id, row_number() over(order by e.embedding <=> p_vector)::int rank,
           (1-(e.embedding <=> p_vector))::real score
    from ai_search.text_embeddings e join eligible d using(product_id)
    where p_vector is not null and e.status='READY'
      and 1-(e.embedding <=> p_vector) >= p_min_similarity
    order by e.embedding <=> p_vector limit p_limit
), combined as (
    select coalesce(k.product_id,s.product_id) product_id, k.rank keyword_rank,
           s.rank semantic_rank, s.score semantic_score
    from keyword k full join semantic s using(product_id)
)
select c.product_id,c.keyword_rank,c.semantic_rank,c.semantic_score,
       coalesce(p_keyword_weight/(p_rrf_k+c.keyword_rank),0) +
       coalesce(p_semantic_weight/(p_rrf_k+c.semantic_rank),0) fusion_score,
       array_remove(array[case when c.keyword_rank is not null then 'KEYWORD' end,
                          case when c.semantic_rank is not null then 'SEMANTIC' end],null) matched_reasons
from combined c order by fusion_score desc,c.product_id limit p_limit;
$$;

reset role;
alter schema ai_search owner to search_owner;
grant usage on schema ai_search to search_app;
grant usage on schema extensions to search_app;
grant select,insert,update,delete on all tables in schema ai_search to search_app;
grant execute on all functions in schema ai_search to search_app;
alter default privileges for role search_owner in schema ai_search grant select,insert,update,delete on tables to search_app;
alter default privileges for role search_owner in schema ai_search grant execute on functions to search_app;
revoke all on schema public from search_app;

comment on schema ai_search is 'Search-owned derived read models; rebuilt only from Catalog API/events.';
