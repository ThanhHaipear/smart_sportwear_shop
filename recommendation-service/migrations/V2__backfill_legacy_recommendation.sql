-- One-time, idempotent migration-role backfill. Runtime recommendation_app never
-- receives USAGE/SELECT on public. Legacy tables remain intact for rollback.
do $$
begin
    if to_regclass('public.user_interactions') is not null then
        execute $sql$
            insert into recommendation.user_interactions
                (event_id,occurred_at,user_ref,anonymous_id,event_type,product_id,variant_id,
                 request_id,source,data_source,metadata,anonymized_at)
            select event_id,occurred_at,user_id::text,anonymous_id,event_type,product_id,variant_id,
                   request_id,source,data_source,coalesce(metadata,'{}'::jsonb),anonymized_at
              from public.user_interactions
            on conflict(event_id) do nothing
        $sql$;
    end if;

    if to_regclass('public.association_rules') is not null then
        execute $sql$
            insert into recommendation.association_rules
                (antecedent_product_id,consequent_product_id,support,confidence,lift,
                 pair_count,model_version,status,created_at)
            select antecedent_product_id,consequent_product_id,support,confidence,lift,
                   pair_count,model_version,status,created_at
              from public.association_rules
            on conflict(antecedent_product_id,consequent_product_id,model_version) do nothing
        $sql$;
    end if;

    if to_regclass('public.recommendation_logs') is not null then
        execute $sql$
            insert into recommendation.recommendation_logs
                (event_id,user_ref,event_type,source_product_ids,recommended_product_id,
                 position_index,algorithm,occurred_at)
            select l.id,l.user_id::text,l.event_type,
                   array(select jsonb_array_elements_text(coalesce(l.source_product_ids,'[]'::jsonb))::uuid),
                   l.recommended_product_id,l.position_index,l.algorithm,l.created_at
              from public.recommendation_logs l
            on conflict(event_id) do nothing
        $sql$;
    end if;
end $$;
