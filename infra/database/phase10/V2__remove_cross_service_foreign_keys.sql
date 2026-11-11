-- Service-owned schemas use external references across boundaries. Any FK whose
-- referenced table is in another service schema is therefore a migration defect.
do $$
declare
  item record;
begin
  for item in
    select ns.nspname as source_schema, cls.relname as source_table, con.conname
      from pg_constraint con
      join pg_class cls on cls.oid=con.conrelid
      join pg_namespace ns on ns.oid=cls.relnamespace
      join pg_class ref on ref.oid=con.confrelid
      join pg_namespace ref_ns on ref_ns.oid=ref.relnamespace
     where con.contype='f'
       and ns.nspname=any(array['identity','catalog','sales','ai_search','chatbot','recommendation'])
       and ref_ns.nspname=any(array['identity','catalog','sales','ai_search','chatbot','recommendation'])
       and ns.nspname<>ref_ns.nspname
  loop
    execute format('alter table %I.%I drop constraint %I',item.source_schema,item.source_table,item.conname);
  end loop;
end $$;
