-- DESTRUCTIVE FINALIZATION. Never add this file to an automatic Flyway location.
-- The operator must set all three transaction-local gates after the approved
-- backup/restore drill and stability window. Missing gates make the script fail.
do $$
begin
  if current_setting('phase10.cutover_approved',true) is distinct from 'true' then
    raise exception 'phase10.cutover_approved gate is not true';
  end if;
  if current_setting('phase10.backup_restore_verified',true) is distinct from 'true' then
    raise exception 'phase10.backup_restore_verified gate is not true';
  end if;
  if current_setting('phase10.data_parity_verified',true) is distinct from 'true' then
    raise exception 'phase10.data_parity_verified gate is not true';
  end if;
  if coalesce(current_setting('phase10.stability_window_ended_at',true),'')='' or
     current_setting('phase10.stability_window_ended_at',true)::timestamptz > clock_timestamp() then
    raise exception 'Phase 10 stability window has not ended';
  end if;
end $$;

-- Abort instead of cascading over an unknown dependency. The ownership audit
-- must report no legacy relation before Phase 10 can be marked DONE.
drop schema if exists ai_retrieval restrict;

do $$
declare
  relation_name text;
  existing_relations text[] := '{}';
  legacy_relations constant text[] := array[
    'addresses','audit_logs','bank_reconciliation_runs','bank_transactions','brands','cart_items','carts',
    'catalog_dataset_items','catalog_datasets','categories','colors','integration_outbox','inventory_transactions',
    'materials','notification_templates','notifications','order_items','order_status_history','orders',
    'password_reset_tokens','payment_events','payments','product_images','product_materials','product_reviews',
    'product_sports','product_technologies','product_types','product_use_cases','product_variants','products',
    'promotion_products','promotions','refresh_tokens','refunds','return_items','returns','site_settings',
    'size_systems','sizes','sports','technologies','use_cases','user_identities','user_interactions','users',
    'wishlist_items','wishlists'];
begin
  foreach relation_name in array legacy_relations loop
    if to_regclass(format('public.%I',relation_name)) is not null then
      existing_relations := array_append(existing_relations,format('public.%I',relation_name));
    end if;
  end loop;
  if cardinality(existing_relations)>0 then
    -- One statement allows dependencies inside the approved set while RESTRICT
    -- still rejects any dependent object outside that set.
    execute 'drop table ' || array_to_string(existing_relations,',') || ' restrict';
  end if;
end $$;
