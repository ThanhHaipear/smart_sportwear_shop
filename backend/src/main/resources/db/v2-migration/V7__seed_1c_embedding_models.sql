-- Database V2 - batch 1C.2 runtime baseline
-- Immutable Hugging Face revisions; changing model or preprocessing requires a new row/migration.

set search_path = ai_retrieval, extensions, public;

insert into ai_retrieval.embedding_models(
    id, modality, provider, model_name, model_version, dimensions,
    distance_metric, normalization, preprocessing_version, status, activated_at
)
values
    (
        '71000000-0000-0000-0000-000000000001',
        'PRODUCT_TEXT', 'HUGGING_FACE_LOCAL', 'BAAI/bge-m3',
        '5617a9f61b028005a4858fdac845db406aefb181', 1024,
        'COSINE', 'L2', 'product-document-v1-max512', 'ACTIVE', now()
    ),
    (
        '71000000-0000-0000-0000-000000000002',
        'PRODUCT_IMAGE', 'HUGGING_FACE_LOCAL', 'google/siglip2-base-patch16-224',
        '75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2', 768,
        'COSINE', 'L2', 'siglip2-autoprocessor-original-v1', 'ACTIVE', now()
    );

comment on table ai_retrieval.embedding_models is
    'Immutable embedding registry. Production workers must load model_name at model_version and apply preprocessing_version exactly.';

-- Keep exact search fail-closed while an asynchronous worker catches up. A
-- READY vector is usable only when its source identity still matches current data.
create or replace function ai_retrieval.search_product_text_exact(
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
       and document.document_hash = embedding.document_hash
     where embedding.model_id = p_model_id
       and embedding.status = 'READY'
       and document.status = 'ACTIVE'
     order by embedding.embedding <=> p_query_embedding, embedding.product_id
     limit greatest(1, least(coalesce(p_match_count, 20), 100));
$$;

create or replace function ai_retrieval.search_product_images_exact(
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
set search_path = ai_retrieval, extensions, public, pg_catalog
as $$
    select embedding.image_id,
           embedding.product_id,
           embedding.embedding <=> p_query_embedding as distance,
           1.0 - (embedding.embedding <=> p_query_embedding) as similarity
      from ai_retrieval.product_image_embeddings embedding
      join public.product_images image
        on image.id = embedding.image_id
       and image.product_id = embedding.product_id
       and image.public_id = embedding.source_public_id
       and image.asset_version = embedding.source_asset_version
      join ai_retrieval.product_search_documents document
        on document.product_id = embedding.product_id
       and document.status = 'ACTIVE'
     where embedding.model_id = p_model_id
       and embedding.status = 'READY'
     order by embedding.embedding <=> p_query_embedding, embedding.image_id
     limit greatest(1, least(coalesce(p_match_count, 20), 100));
$$;
