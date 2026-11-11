from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "infra/database/phase10/ownership.json"
SERVICE_SCHEMAS = ("identity", "catalog", "sales", "ai_search", "chatbot", "recommendation")
WRITE_PRIVILEGES = ("INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")


async def audit(dsn: str, require_legacy_retired: bool) -> dict:
    manifest = json.loads(MANIFEST.read_text("utf-8"))
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"), timeout=20)
    violations: list[str] = []
    role_results: list[dict] = []
    try:
        for service in manifest["services"]:
            schema = service["schema"]
            role = service["runtimeRole"]
            owner_role = service["ownerRole"]
            attributes = await connection.fetchrow(
                "select rolsuper,rolcreatedb,rolcreaterole,rolconfig from pg_roles where rolname=$1", role
            )
            if attributes is None:
                violations.append(f"missing runtime role {role}")
                continue
            if attributes["rolsuper"] or attributes["rolcreatedb"] or attributes["rolcreaterole"]:
                violations.append(f"{role} has elevated cluster privileges")
            schema_owner = await connection.fetchval(
                """select owner.rolname from pg_namespace ns join pg_roles owner on owner.oid=ns.nspowner
                   where ns.nspname=$1""", schema
            )
            if schema_owner != owner_role:
                violations.append(f"schema {schema} owner is {schema_owner!r}, expected {owner_role}")
            public_create = await connection.fetchval(
                "select has_schema_privilege($1,'public','CREATE')", role
            )
            own_usage = await connection.fetchval(
                "select has_schema_privilege($1,$2,'USAGE')", role, schema
            )
            if public_create:
                violations.append(f"{role} can CREATE in public")
            if not own_usage:
                violations.append(f"{role} cannot USE owned schema {schema}")
            foreign_writes = await connection.fetch(
                """select distinct n.nspname table_schema,c.relname table_name,p.privilege_type
                     from pg_class c join pg_namespace n on n.oid=c.relnamespace
                     cross join unnest($4::text[]) p(privilege_type)
                    where n.nspname=any($2::text[]) and n.nspname<>$3 and c.relkind in ('r','p','v','m')
                      and has_table_privilege($1,format('%I.%I',n.nspname,c.relname),p.privilege_type)
                    order by 1,2,3""",
                role, list(SERVICE_SCHEMAS), schema, list(WRITE_PRIVILEGES),
            )
            if foreign_writes:
                violations.append(f"{role} has cross-schema writes: " + ", ".join(
                    f"{row['table_schema']}.{row['table_name']}:{row['privilege_type']}" for row in foreign_writes
                ))
            config = [entry.replace(" ", "") for entry in list(attributes["rolconfig"] or [])]
            expected_search_path = ("search_path=" + service["searchPath"]).replace(" ", "")
            if expected_search_path not in config:
                violations.append(f"{role} search_path is not {service['searchPath']}")
            role_results.append({
                "service": service["service"], "schema": schema, "schemaOwner": schema_owner,
                "runtimeRole": role, "ownSchemaUsage": own_usage, "publicCreate": public_create,
                "crossSchemaWriteGrantCount": len(foreign_writes), "searchPathConfigured": expected_search_path in config,
            })

        public_create = await connection.fetchval(
            """select exists(select 1 from pg_namespace n,
                       lateral aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) acl
                      where n.nspname='public' and acl.grantee=0 and acl.privilege_type='CREATE')"""
        )
        if public_create:
            violations.append("PUBLIC can CREATE in public schema")

        cross_fks = await connection.fetch(
            """select src_ns.nspname source_schema,src.relname source_table,c.conname,
                      dst_ns.nspname target_schema,dst.relname target_table
                 from pg_constraint c join pg_class src on src.oid=c.conrelid
                 join pg_namespace src_ns on src_ns.oid=src.relnamespace
                 join pg_class dst on dst.oid=c.confrelid
                 join pg_namespace dst_ns on dst_ns.oid=dst.relnamespace
                where c.contype='f' and src_ns.nspname=any($1::text[])
                  and dst_ns.nspname=any($1::text[]) and src_ns.nspname<>dst_ns.nspname
                order by 1,2,3""", list(SERVICE_SCHEMAS),
        )
        if cross_fks:
            violations.append("cross-service foreign keys remain: " + ", ".join(
                f"{row['source_schema']}.{row['source_table']}.{row['conname']}" for row in cross_fks
            ))

        legacy_public = await connection.fetch(
            """select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='public' and c.relkind in ('r','p','v','m') and c.relname=any($1::text[])
                order by c.relname""", manifest["legacyPublicRelations"],
        )
        legacy_schemas = [name for name in manifest["legacySchemas"] if await connection.fetchval(
            "select to_regnamespace($1) is not null", name
        )]
        if require_legacy_retired and legacy_public:
            violations.append(f"{len(legacy_public)} legacy public relations remain")
        if require_legacy_retired and legacy_schemas:
            violations.append("legacy schemas remain: " + ", ".join(legacy_schemas))
    finally:
        await connection.close()

    return {
        "auditedAt": datetime.now(timezone.utc).isoformat(),
        "manifestVersion": manifest["version"],
        "requireLegacyRetired": require_legacy_retired,
        "roles": role_results,
        "crossServiceForeignKeyCount": len(cross_fks),
        "legacyPublicRelations": [row["relname"] for row in legacy_public],
        "legacySchemas": legacy_schemas,
        "passed": not violations,
        "violations": violations,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only Phase 10 ownership/permission audit")
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--database-url")
    parser.add_argument("--require-legacy-retired", action="store_true")
    parser.add_argument("--evidence")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    dsn = args.database_url or values.get("DB_READ_URL") or values.get("DB_WRITE_URL") or ""
    if not dsn:
        raise SystemExit("read-only/admin database DSN is not configured")
    result = asyncio.run(audit(dsn, args.require_legacy_retired))
    serialized = json.dumps(result, indent=2) + "\n"
    if args.evidence:
        path = ROOT / args.evidence
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(serialized, encoding="utf-8")
    print(serialized, end="")
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
