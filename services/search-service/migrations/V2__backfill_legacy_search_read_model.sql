-- Phase 10 copies rebuildable read models. Text vectors are intentionally not copied:
-- legacy vectors are 1024 dimensions while ai_search uses OpenAI vector(1536).
set search_path=ai_search,extensions,pg_catalog;

insert into ai_search.search_documents(product_id,source_version,status,name,document_text,document_hash,
  category,brand,product_type,gender,sport_codes,attributes,updated_at)
select product_id,source_version,case when status='DELETED' then 'DELETED' else status end,name,document_text,document_hash,
  category_code,brand_code,product_type_code,gender_code,sport_codes,
  jsonb_build_object('useCaseCodes',use_case_codes,'materialCodes',material_codes),updated_at
from ai_retrieval.product_search_documents
on conflict(product_id) do update set source_version=excluded.source_version,status=excluded.status,name=excluded.name,
 document_text=excluded.document_text,document_hash=excluded.document_hash,category=excluded.category,brand=excluded.brand,
 product_type=excluded.product_type,gender=excluded.gender,sport_codes=excluded.sport_codes,attributes=excluded.attributes,updated_at=excluded.updated_at;

insert into ai_search.variant_facets(variant_id,product_id,sku,size_code,color_code,color_family,price,available_quantity,status,source_version)
select variant_id,product_id,sku,size_code,color_code,color_family_code,price,available_quantity,status,source_version
from ai_retrieval.search_variant_facets
on conflict(variant_id) do update set product_id=excluded.product_id,sku=excluded.sku,size_code=excluded.size_code,
 color_code=excluded.color_code,color_family=excluded.color_family,price=excluded.price,
 available_quantity=excluded.available_quantity,status=excluded.status,source_version=excluded.source_version;

insert into ai_search.image_embeddings(image_id,product_id,image_url,image_hash,model,embedding,status,updated_at)
select distinct on (e.image_id) e.image_id,e.product_id,i.secure_url,e.image_hash,
  m.provider||':'||m.model_name||':'||m.model_version,e.embedding,e.status,e.updated_at
from ai_retrieval.product_image_embeddings e
join ai_retrieval.embedding_models m on m.id=e.model_id
join public.product_images i on i.id=e.image_id
where e.embedding is not null and e.image_hash is not null and e.status='READY'
order by e.image_id,(m.status='ACTIVE') desc,e.updated_at desc
on conflict(image_id) do update set product_id=excluded.product_id,image_url=excluded.image_url,image_hash=excluded.image_hash,
 model=excluded.model,embedding=excluded.embedding,status=excluded.status,updated_at=excluded.updated_at;

grant select,insert,update,delete on all tables in schema ai_search to search_app;
