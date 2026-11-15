set role catalog_owner;
set search_path = catalog, pg_catalog;

create or replace function catalog.emit_inventory_changed() returns trigger language plpgsql as $$
declare
  v_event_id uuid := gen_random_uuid();
  v_product_version bigint;
  v_trace_id text;
begin
  if new.stock_quantity is distinct from old.stock_quantity
     or new.reserved_quantity is distinct from old.reserved_quantity then
    select version into v_product_version from catalog.products where id = new.product_id;
    v_trace_id := coalesce(
      nullif(current_setting('app.trace_id', true), ''),
      'catalog-outbox-' || v_event_id::text
    );
    insert into catalog.integration_outbox(
      id,event_type,aggregate_type,aggregate_id,aggregate_version,payload
    ) values(
      v_event_id,'product.reindex.requested','Product',new.product_id,v_product_version,
      jsonb_build_object(
        'eventId',v_event_id,
        'eventType','PRODUCT_REINDEX_REQUESTED',
        'eventVersion',1,
        'productId',new.product_id,
        'sourceVersion',v_product_version,
        'occurredAt',now(),
        'traceId',v_trace_id
      )
    );
  end if;
  return new;
end $$;

create or replace function catalog.emit_product_changed() returns trigger language plpgsql as $$
declare
  v_event_id uuid := gen_random_uuid();
  v_trace_id text := coalesce(
    nullif(current_setting('app.trace_id', true), ''),
    'catalog-outbox-' || v_event_id::text
  );
begin
  insert into catalog.integration_outbox(
    id,event_type,aggregate_type,aggregate_id,aggregate_version,payload
  ) values(
    v_event_id,'product.reindex.requested','Product',new.id,new.version,
    jsonb_build_object(
      'eventId',v_event_id,
      'eventType','PRODUCT_REINDEX_REQUESTED',
      'eventVersion',1,
      'productId',new.id,
      'sourceVersion',new.version,
      'occurredAt',now(),
      'traceId',v_trace_id
    )
  );
  return new;
end $$;

comment on function catalog.emit_inventory_changed() is
  'Emits catalog-event-v1 PRODUCT_REINDEX_REQUESTED for Search and Recommendation.';
comment on function catalog.emit_product_changed() is
  'Emits catalog-event-v1 PRODUCT_REINDEX_REQUESTED for Search and Recommendation.';

reset role;
