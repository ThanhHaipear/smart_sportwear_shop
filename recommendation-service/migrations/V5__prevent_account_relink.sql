set role recommendation_owner;
set search_path = recommendation, pg_catalog;

-- Store a digest of the stable Identity UUID so delayed writes cannot restore its account link.
create table if not exists erased_accounts (
    account_digest bytea primary key
);
revoke all on table erased_accounts from public, recommendation_app;

create or replace function prevent_erased_account_link() returns trigger
language plpgsql security definer set search_path = pg_catalog as $$
declare
    account_ref text := lower(new.user_ref);
begin
    if account_ref is null then return new; end if;

    -- Serialize with erasure. Under READ COMMITTED, the lookup after the lock sees a committed erasure.
    perform pg_advisory_xact_lock(hashtextextended(account_ref, 7589));
    if exists (select 1 from recommendation.erased_accounts
               where account_digest = sha256(convert_to(account_ref, 'UTF8'))) then
        new.user_ref := null;
        if tg_table_name in ('user_interactions', 'recommendation_logs') then
            new.anonymous_id := 'erased:' || gen_random_uuid()::text;
        end if;
        if tg_table_name = 'user_interactions' then
            new.anonymized_at := now();
        end if;
    end if;
    return new;
end;
$$;
revoke all on function prevent_erased_account_link() from public, recommendation_app;

do $$
declare
    relation text;
begin
    foreach relation in array array['user_interactions', 'recommendation_logs', 'order_baskets', 'recommendation_requests'] loop
        execute format('drop trigger if exists trg_prevent_erased_account_link on recommendation.%I', relation);
        execute format('create trigger trg_prevent_erased_account_link before insert on recommendation.%I '
                       'for each row execute function recommendation.prevent_erased_account_link()', relation);
    end loop;
end;
$$;

create or replace function erase_recommendation_account(account_id uuid)
returns table(interactions bigint, logs bigint, baskets bigint, requests bigint)
language plpgsql set search_path = recommendation, pg_catalog as $$
begin
    perform pg_advisory_xact_lock(hashtextextended(account_id::text, 7589));
    insert into erased_accounts(account_digest) values (sha256(convert_to(account_id::text, 'UTF8')))
        on conflict do nothing;

    return query
    with replacement as (
        select 'erased:' || gen_random_uuid()::text as anonymous_id
    ), erased_interactions as (
        update user_interactions set user_ref = null, anonymous_id = replacement.anonymous_id,
               anonymized_at = now() from replacement where lower(user_ref) = account_id::text and anonymized_at is null
        returning 1
    ), erased_logs as (
        update recommendation_logs set user_ref = null, anonymous_id = replacement.anonymous_id
        from replacement where lower(user_ref) = account_id::text returning 1
    ), erased_baskets as (
        update order_baskets set user_ref = null where lower(user_ref) = account_id::text returning 1
    ), erased_requests as (
        update recommendation_requests set user_ref = null where lower(user_ref) = account_id::text returning 1
    )
    select (select count(*) from erased_interactions), (select count(*) from erased_logs),
           (select count(*) from erased_baskets), (select count(*) from erased_requests);
end;
$$;
revoke all on function erase_recommendation_account(uuid) from public, recommendation_app;

reset role;
reset search_path;
