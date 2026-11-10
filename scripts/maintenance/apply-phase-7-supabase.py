from __future__ import annotations

import argparse, asyncio, json, secrets
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit, urlunsplit
import asyncpg
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[2]

def runtime_dsn(admin_dsn: str, role: str, password: str) -> str:
    parsed = urlsplit(admin_dsn.replace("postgresql+asyncpg://", "postgresql://"))
    suffix = (unquote(parsed.username or "").split(".", 1) + [""])[1]
    username = f"{role}.{suffix}" if suffix else role
    port = f":{parsed.port}" if parsed.port else ""
    return urlunsplit((parsed.scheme, f"{quote(username)}:{quote(password)}@{parsed.hostname}{port}", parsed.path, parsed.query, ""))

def update_env(path: Path, updates: dict[str, str]) -> None:
    lines = path.read_text("utf-8").splitlines() if path.exists() else []
    output, seen = [], set()
    for line in lines:
        key = line.split("=", 1)[0] if "=" in line and not line.lstrip().startswith("#") else ""
        if key in updates: output.append(f"{key}={updates[key]}"); seen.add(key)
        else: output.append(line)
    if output and output[-1] != "": output.append("")
    output.extend(f"{key}={value}" for key, value in updates.items() if key not in seen)
    path.write_text("\n".join(output) + "\n", encoding="utf-8")

def jdbc_parts(dsn: str) -> tuple[str, str, str]:
    parsed=urlsplit(dsn); query=f"?{parsed.query}" if parsed.query else ""; port=f":{parsed.port}" if parsed.port else ""
    return f"jdbc:postgresql://{parsed.hostname}{port}{parsed.path}{query}", unquote(parsed.username or ""), unquote(parsed.password or "")

async def apply(admin_dsn: str) -> dict:
    normalized=admin_dsn.replace("postgresql+asyncpg://","postgresql://"); password=secrets.token_urlsafe(36)
    connection=await asyncpg.connect(normalized,timeout=20)
    try:
        admin_role=await connection.fetchval("select current_user"); quoted_admin=await connection.fetchval("select quote_ident($1)",admin_role); quoted_password=await connection.fetchval("select quote_literal($1)",password)
        async with connection.transaction():
            await connection.execute("""do $$ begin
              if not exists(select 1 from pg_roles where rolname='identity_owner') then create role identity_owner nologin; end if;
              if not exists(select 1 from pg_roles where rolname='identity_app') then create role identity_app login; end if;
            end $$""")
            await connection.execute(f"grant identity_owner to {quoted_admin}")
            await connection.execute(f"alter role identity_app login password {quoted_password}")
            database=await connection.fetchval("select quote_ident(current_database())");await connection.execute(f"grant connect on database {database} to identity_app")
            for name in ("V1__create_identity_schema.sql","V2__backfill_legacy_identity.sql"):
                await connection.execute((ROOT/"identity-service/src/main/resources/db/migration"/name).read_text("utf-8"))
            await connection.execute("alter role identity_app set search_path=identity,pg_catalog")
            counts=dict(await connection.fetchrow("""select
              (select count(*) from identity.users) users,
              (select count(*) from identity.user_identities) user_identities,
              (select count(*) from identity.addresses) addresses,
              (select count(*) from identity.refresh_tokens) refresh_tokens,
              (select count(*) from identity.password_reset_tokens) password_reset_tokens"""))
            legacy=dict(await connection.fetchrow("""select
              (select count(*) from public.users) users,
              (select count(*) from public.user_identities) user_identities,
              (select count(*) from public.addresses) addresses,
              (select count(*) from public.refresh_tokens) refresh_tokens,
              (select count(*) from public.password_reset_tokens) password_reset_tokens"""))
            if counts != legacy: raise RuntimeError(f"identity backfill count mismatch: target={counts}, legacy={legacy}")
        dsn=runtime_dsn(normalized,"identity_app",password)
    finally: await connection.close()
    runtime=None
    for attempt in range(6):
        try: runtime=await asyncpg.connect(dsn,timeout=20);break
        except asyncpg.InvalidPasswordError:
            if attempt==5: raise
            await asyncio.sleep(2**attempt)
    try:
        attrs=await runtime.fetchrow("select rolsuper,rolcreatedb,rolcreaterole from pg_roles where rolname=current_user")
        permissions={"role":await runtime.fetchval("select current_user"),"superuser":attrs["rolsuper"],"createDatabase":attrs["rolcreatedb"],"createRole":attrs["rolcreaterole"],"ownedSchemaUsage":await runtime.fetchval("select has_schema_privilege(current_user,'identity','USAGE')"),"publicCreate":await runtime.fetchval("select has_schema_privilege(current_user,'public','CREATE')")}
        transaction=runtime.transaction();await transaction.start()
        try:
            probe=secrets.token_hex(8);await runtime.execute("insert into identity.users(email,full_name) values($1,'permission probe')",f"probe-{probe}@example.invalid")
            try: await runtime.execute("create table public.identity_permission_probe(id integer)");raise RuntimeError("identity_app unexpectedly wrote public")
            except asyncpg.InsufficientPrivilegeError: pass
        finally: await transaction.rollback()
    finally: await runtime.close()
    jdbc_url,username,runtime_password=jdbc_parts(dsn)
    update_env(ROOT/"identity-service/.env",{"SERVER_PORT":"8093","IDENTITY_DATABASE_URL":jdbc_url,"IDENTITY_DB_USERNAME":username,"IDENTITY_DB_PASSWORD":runtime_password,"IDENTITY_FLYWAY_ENABLED":"false","IDENTITY_EVENTS_ENABLED":"false"})
    return {"appliedAt":datetime.now(timezone.utc).isoformat(),"database":"configured-supabase-postgres","schemasCreated":["identity"],"rolesConfigured":["identity_owner","identity_app"],"permissions":permissions,"backfillCounts":counts,"legacyCounts":legacy,"countsMatch":counts==legacy,"identityEventsEnabled":False,"publicRouteCutover":False,"secretsPrinted":False,"rollback":"Keep schema; disable route/events and optionally revoke identity_app LOGIN."}

def main():
    parser=argparse.ArgumentParser();parser.add_argument("--admin-env",default="chatbot-service/.env");parser.add_argument("--evidence",default="evidence/microservice-phase-7-supabase-2026-11-10.json");args=parser.parse_args()
    values=dotenv_values(ROOT/args.admin_env);admin_dsn=values.get("DB_WRITE_URL") or values.get("DB_READ_URL") or ""
    if not admin_dsn: raise SystemExit("Admin database DSN is not configured")
    result=asyncio.run(apply(admin_dsn));evidence=ROOT/args.evidence;evidence.parent.mkdir(parents=True,exist_ok=True);evidence.write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8");print(json.dumps(result,indent=2))
if __name__=="__main__": main()
