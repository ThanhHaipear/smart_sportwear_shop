from __future__ import annotations
import argparse,asyncio,json,secrets
from datetime import datetime,timezone
from pathlib import Path
from urllib.parse import quote,unquote,urlsplit,urlunsplit
import asyncpg
from dotenv import dotenv_values
ROOT=Path(__file__).resolve().parents[2]
TABLES=("carts","cart_items","orders","order_items","order_status_history","payments","payment_events","returns","return_items","refunds","bank_transactions","shipping_methods","shipments","notifications")
def runtime_dsn(dsn,role,password):
 p=urlsplit(dsn.replace("postgresql+asyncpg://","postgresql://"));suffix=(unquote(p.username or "").split(".",1)+[""])[1];user=f"{role}.{suffix}" if suffix else role;port=f":{p.port}" if p.port else "";return urlunsplit((p.scheme,f"{quote(user)}:{quote(password)}@{p.hostname}{port}",p.path,p.query,""))
def update_env(path,updates):
 lines=path.read_text("utf-8").splitlines() if path.exists() else [];out=[];seen=set()
 for line in lines:
  key=line.split("=",1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
  if key in updates:out.append(f"{key}={updates[key]}");seen.add(key)
  else:out.append(line)
 if out and out[-1]!="":out.append("")
 out.extend(f"{k}={v}" for k,v in updates.items() if k not in seen);path.write_text("\n".join(out)+"\n",encoding="utf-8")
async def apply(admin_dsn):
 normalized=admin_dsn.replace("postgresql+asyncpg://","postgresql://");password=secrets.token_urlsafe(36);token=secrets.token_urlsafe(36);c=await asyncpg.connect(normalized,timeout=20)
 try:
  admin=await c.fetchval("select quote_ident(current_user)");pw=await c.fetchval("select quote_literal($1)",password)
  async with c.transaction():
   await c.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='sales_owner') then create role sales_owner nologin; end if; if not exists(select 1 from pg_roles where rolname='sales_app') then create role sales_app login; end if; end $$")
   await c.execute(f"grant sales_owner to {admin}");await c.execute(f"alter role sales_app login password {pw}");db=await c.fetchval("select quote_ident(current_database())");await c.execute(f"grant connect on database {db} to sales_app")
   for name in ("V1__create_sales_schema.sql","V2__backfill_legacy_sales.sql"):await c.execute((ROOT/"sales-service/src/main/resources/db/migration"/name).read_text("utf-8"))
   await c.execute("alter role sales_app set search_path=sales,pg_catalog")
   target={t:await c.fetchval(f"select count(*) from sales.{t}") for t in TABLES};legacy={t:await c.fetchval(f"select count(*) from public.{t}") for t in TABLES}
   if target!=legacy:raise RuntimeError(f"sales backfill mismatch: target={target}, legacy={legacy}")
  dsn=runtime_dsn(normalized,"sales_app",password)
 finally:await c.close()
 runtime=None
 for attempt in range(6):
  try:runtime=await asyncpg.connect(dsn,timeout=20);break
  except asyncpg.InvalidPasswordError:
   if attempt==5:raise
   await asyncio.sleep(2**attempt)
 try:
  attrs=await runtime.fetchrow("select rolsuper,rolcreatedb,rolcreaterole from pg_roles where rolname=current_user");permissions={"role":await runtime.fetchval("select current_user"),"superuser":attrs["rolsuper"],"createDatabase":attrs["rolcreatedb"],"createRole":attrs["rolcreaterole"],"ownedSchemaUsage":await runtime.fetchval("select has_schema_privilege(current_user,'sales','USAGE')"),"publicCreate":await runtime.fetchval("select has_schema_privilege(current_user,'public','CREATE')")}
  tr=runtime.transaction();await tr.start()
  try:
   await runtime.execute("insert into sales.carts(session_id) values($1)","probe-"+secrets.token_hex(6))
   try:await runtime.execute("create table public.sales_permission_probe(id integer)");raise RuntimeError("sales_app unexpectedly wrote public")
   except asyncpg.InsufficientPrivilegeError:pass
  finally:await tr.rollback()
 finally:await runtime.close()
 p=urlsplit(dsn);port=f":{p.port}" if p.port else "";jdbc=f"jdbc:postgresql://{p.hostname}{port}{p.path}"+(f"?{p.query}" if p.query else "")
 update_env(ROOT/"sales-service/.env",{"SALES_DATABASE_URL":jdbc,"SALES_DB_USERNAME":unquote(p.username or ""),"SALES_DB_PASSWORD":unquote(p.password or ""),"SALES_INTERNAL_TOKEN":token,"SALES_EVENTS_ENABLED":"false","SEPAY_WEBHOOK_ENABLED":"false"})
 return {"appliedAt":datetime.now(timezone.utc).isoformat(),"database":"configured-supabase-postgres","schemasCreated":["sales"],"rolesConfigured":["sales_owner","sales_app"],"permissions":permissions,"backfillCounts":target,"legacyCounts":legacy,"countsMatch":target==legacy,"salesEventsEnabled":False,"sepayIngressEnabled":False,"publicRouteCutover":False,"secretsPrinted":False,"rollback":"Keep schema; route to the prior core release, disable Sales/SePay writers, and optionally revoke sales_app LOGIN."}
def main():
 p=argparse.ArgumentParser();p.add_argument("--admin-env",default="chatbot-service/.env");p.add_argument("--evidence",default="evidence/microservice-phase-8-supabase-2026-11-10.json");a=p.parse_args();v=dotenv_values(ROOT/a.admin_env);dsn=v.get("DB_WRITE_URL") or v.get("DB_READ_URL") or ""
 if not dsn:raise SystemExit("Admin database DSN is not configured")
 result=asyncio.run(apply(dsn));e=ROOT/a.evidence;e.parent.mkdir(parents=True,exist_ok=True);e.write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8");print(json.dumps(result,indent=2))
if __name__=="__main__":main()
