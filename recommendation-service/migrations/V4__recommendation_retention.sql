set role recommendation_owner;
set search_path = recommendation, pg_catalog;

-- The runtime may append facts, but only the schema owner may erase account links or expire rows.
create or replace function reject_recommendation_fact_mutation() returns trigger
language plpgsql set search_path = pg_catalog as $$
begin
    if current_user = 'recommendation_owner' then
        if tg_op = 'DELETE' then
            if old.occurred_at < now() - interval '180 days' then return old; end if;
        elsif old.user_ref is not null and new.user_ref is null
              and new.anonymous_id ~ '^erased:[0-9a-f-]{36}$' then
            if tg_table_name = 'user_interactions' then
                if old.anonymized_at is null and new.anonymized_at is not null
                   and to_jsonb(new) - array['user_ref','anonymous_id','anonymized_at']
                       = to_jsonb(old) - array['user_ref','anonymous_id','anonymized_at'] then
                    return new;
                end if;
            elsif to_jsonb(new) - array['user_ref','anonymous_id']
                  = to_jsonb(old) - array['user_ref','anonymous_id'] then
                return new;
            end if;
        end if;
    end if;
    raise exception using errcode = '55000', message = format('%I is append-only', tg_table_name);
end;
$$;

create or replace function expire_recommendation_data(batch_size integer)
returns table(interactions bigint, logs bigint, baskets bigint, requests bigint)
language sql set search_path = recommendation, pg_catalog as $$
    with expired_interactions as (
        delete from user_interactions where event_id in (
            select event_id from user_interactions where occurred_at < now() - interval '180 days'
            order by occurred_at, event_id limit batch_size for update skip locked
        ) returning 1
    ), expired_logs as (
        delete from recommendation_logs where event_id in (
            select event_id from recommendation_logs where occurred_at < now() - interval '180 days'
            order by occurred_at, event_id limit batch_size for update skip locked
        ) returning 1
    ), expired_baskets as (
        update order_baskets set user_ref = null where order_id in (
            select order_id from order_baskets where user_ref is not null and occurred_at < now() - interval '180 days'
            order by occurred_at, order_id limit batch_size for update skip locked
        ) returning 1
    ), expired_requests as (
        delete from recommendation_requests where id in (
            select id from recommendation_requests where served_at < now() - interval '30 days'
            order by served_at, id limit batch_size for update skip locked
        ) returning 1
    )
    select (select count(*) from expired_interactions), (select count(*) from expired_logs),
           (select count(*) from expired_baskets), (select count(*) from expired_requests);
$$;

create or replace function erase_recommendation_account(account_id uuid)
returns table(interactions bigint, logs bigint, baskets bigint, requests bigint)
language sql set search_path = recommendation, pg_catalog as $$
    with replacement as (
        select 'erased:' || gen_random_uuid()::text as anonymous_id
    ), erased_interactions as (
        update user_interactions set user_ref = null, anonymous_id = replacement.anonymous_id,
               anonymized_at = now() from replacement where user_ref = account_id::text and anonymized_at is null
        returning 1
    ), erased_logs as (
        update recommendation_logs set user_ref = null, anonymous_id = replacement.anonymous_id
        from replacement where user_ref = account_id::text returning 1
    ), erased_baskets as (
        update order_baskets set user_ref = null where user_ref = account_id::text returning 1
    ), erased_requests as (
        update recommendation_requests set user_ref = null where user_ref = account_id::text returning 1
    )
    select (select count(*) from erased_interactions), (select count(*) from erased_logs),
           (select count(*) from erased_baskets), (select count(*) from erased_requests);
$$;

revoke all on function expire_recommendation_data(integer), erase_recommendation_account(uuid)
    from public, recommendation_app;

reset role;
reset search_path;
