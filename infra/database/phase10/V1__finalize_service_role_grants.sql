-- Phase 10: final least-privilege matrix. Apply with the database migration role.
-- This migration intentionally does not delete legacy data.
revoke create on schema public from public;

do $$
declare
  app_role text;
  data_schema text;
  owner_role text;
begin
  foreach app_role in array array['identity_app','catalog_app','sales_app','search_app','chatbot_app','recommendation_app'] loop
    if not exists (select 1 from pg_roles where rolname = app_role) then
      raise exception 'required Phase 10 role % does not exist', app_role;
    end if;
    execute format('revoke all on schema public from %I', app_role);
    foreach data_schema in array array['identity','catalog','sales','ai_search','chatbot','recommendation'] loop
      if to_regnamespace(data_schema) is null then
        raise exception 'required Phase 10 schema % does not exist', data_schema;
      end if;
      execute format('revoke all on all tables in schema %I from %I', data_schema, app_role);
      execute format('revoke all on all sequences in schema %I from %I', data_schema, app_role);
      execute format('revoke all on all functions in schema %I from %I', data_schema, app_role);
      execute format('revoke all on schema %I from %I', data_schema, app_role);
    end loop;
  end loop;
  foreach owner_role in array array['identity_owner','catalog_owner','sales_owner','search_owner','chatbot_owner','recommendation_owner'] loop
    foreach data_schema in array array['identity','catalog','sales','ai_search','chatbot','recommendation'] loop
      foreach app_role in array array['identity_app','catalog_app','sales_app','search_app','chatbot_app','recommendation_app'] loop
        execute format('alter default privileges for role %I in schema %I revoke all on tables from %I',owner_role,data_schema,app_role);
        execute format('alter default privileges for role %I in schema %I revoke all on sequences from %I',owner_role,data_schema,app_role);
        execute format('alter default privileges for role %I in schema %I revoke all on functions from %I',owner_role,data_schema,app_role);
      end loop;
    end loop;
  end loop;
end $$;

grant usage on schema identity to identity_app;
grant select,insert,update,delete on all tables in schema identity to identity_app;
grant usage,select on all sequences in schema identity to identity_app;

grant usage on schema catalog to catalog_app;
grant select,insert,update,delete on all tables in schema catalog to catalog_app;
grant usage,select on all sequences in schema catalog to catalog_app;

grant usage on schema sales to sales_app;
grant select,insert,update,delete on all tables in schema sales to sales_app;
grant usage,select on all sequences in schema sales to sales_app;

grant usage on schema ai_search,extensions to search_app;
grant select,insert,update,delete on all tables in schema ai_search to search_app;
grant execute on all functions in schema ai_search to search_app;

grant usage on schema chatbot to chatbot_app;
grant select,insert,update,delete on all tables in schema chatbot to chatbot_app;
grant usage,select on all sequences in schema chatbot to chatbot_app;

grant usage on schema recommendation to recommendation_app;
grant select,insert,update,delete on all tables in schema recommendation to recommendation_app;
grant usage,select on all sequences in schema recommendation to recommendation_app;
grant execute on all functions in schema recommendation to recommendation_app;

alter role identity_app set search_path=identity,pg_catalog;
alter role catalog_app set search_path=catalog,pg_catalog;
alter role sales_app set search_path=sales,pg_catalog;
alter role search_app set search_path=ai_search,extensions,pg_catalog;
alter role chatbot_app set search_path=chatbot,pg_catalog;
alter role recommendation_app set search_path=recommendation,pg_catalog;

alter default privileges for role identity_owner in schema identity grant select,insert,update,delete on tables to identity_app;
alter default privileges for role identity_owner in schema identity grant usage,select on sequences to identity_app;
alter default privileges for role catalog_owner in schema catalog grant select,insert,update,delete on tables to catalog_app;
alter default privileges for role catalog_owner in schema catalog grant usage,select on sequences to catalog_app;
alter default privileges for role sales_owner in schema sales grant select,insert,update,delete on tables to sales_app;
alter default privileges for role sales_owner in schema sales grant usage,select on sequences to sales_app;
alter default privileges for role search_owner in schema ai_search grant select,insert,update,delete on tables to search_app;
alter default privileges for role search_owner in schema ai_search grant execute on functions to search_app;
alter default privileges for role chatbot_owner in schema chatbot grant select,insert,update,delete on tables to chatbot_app;
alter default privileges for role chatbot_owner in schema chatbot grant usage,select on sequences to chatbot_app;
alter default privileges for role recommendation_owner in schema recommendation grant select,insert,update,delete on tables to recommendation_app;
alter default privileges for role recommendation_owner in schema recommendation grant usage,select on sequences to recommendation_app;
alter default privileges for role recommendation_owner in schema recommendation grant execute on functions to recommendation_app;
