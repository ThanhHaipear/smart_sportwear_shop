from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]


def load_json(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


async def finalize(dsn: str, sql: str, stability_ended_at: str) -> dict:
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"), timeout=30)
    try:
        before_relations = await connection.fetchval(
            """select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
                 where n.nspname='public' and c.relkind in ('r','p')"""
        )
        async with connection.transaction():
            await connection.execute("select set_config('phase10.cutover_approved','true',true)")
            await connection.execute("select set_config('phase10.backup_restore_verified','true',true)")
            await connection.execute("select set_config('phase10.data_parity_verified','true',true)")
            await connection.execute(
                "select set_config('phase10.stability_window_ended_at',$1,true)", stability_ended_at
            )
            await connection.execute(sql)
        legacy_schema = await connection.fetchval("select to_regnamespace('ai_retrieval') is not null")
        legacy_relations = await connection.fetchval(
            """select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
                 where n.nspname='public' and c.relkind in ('r','p')
                   and c.relname<> 'flyway_schema_history'"""
        )
        return {
            "publicRelationsBefore": before_relations,
            "legacySchemaPresentAfter": legacy_schema,
            "legacyPublicRelationsAfter": legacy_relations,
        }
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Fail-closed Phase 10 destructive finalization")
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--database-url")
    parser.add_argument("--restore-evidence", required=True)
    parser.add_argument("--parity-evidence", required=True)
    parser.add_argument("--writer-evidence", required=True)
    parser.add_argument("--stability-ended-at", required=True)
    parser.add_argument("--approval-id", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not args.apply:
        raise SystemExit("Refusing destructive finalization without --apply")
    restore = load_json(args.restore_evidence)
    parity = load_json(args.parity_evidence)
    writers = load_json(args.writer_evidence)
    if not restore.get("restore", {}).get("verified"):
        raise SystemExit("Restore evidence is not verified")
    if not parity.get("summary", {}).get("readyForLegacyRetirement"):
        raise SystemExit("Parity evidence is not ready for legacy retirement")
    if writers.get("legacyWriterSessionDetected"):
        raise SystemExit("Writer audit detected a legacy session")
    stability = datetime.fromisoformat(args.stability_ended_at.replace("Z", "+00:00"))
    if stability > datetime.now(timezone.utc):
        raise SystemExit("Stability window end is in the future")
    if not args.approval_id.strip():
        raise SystemExit("Approval ID is empty")
    values = dotenv_values(ROOT / args.admin_env)
    dsn = args.database_url or values.get("DB_WRITE_URL") or ""
    if not dsn:
        raise SystemExit("Writable admin database DSN is not configured")
    sql = (ROOT / "infra/database/phase10/V3__retire_legacy_structures.sql").read_text(encoding="utf-8")
    result = asyncio.run(finalize(dsn, sql, args.stability_ended_at))
    evidence = {
        "phase": 10,
        "finalizedAt": datetime.now(timezone.utc).isoformat(),
        "approvalId": args.approval_id,
        "restoreEvidence": args.restore_evidence,
        "parityEvidence": args.parity_evidence,
        "writerEvidence": args.writer_evidence,
        "stabilityWindowEndedAt": args.stability_ended_at,
        "transactionCommitted": True,
        **result,
        "secretsPrinted": False,
    }
    output = json.dumps(evidence, indent=2) + "\n"
    path = ROOT / args.evidence
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
