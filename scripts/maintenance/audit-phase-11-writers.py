from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import asyncpg
from dotenv import dotenv_values


ROOT = Path(__file__).resolve().parents[2]
LEGACY_MARKERS = ("legacy", "ecommerce", "backend-monolith", "smart-sportswear-backend")


async def audit(dsn: str) -> dict:
    connection = await asyncpg.connect(dsn.replace("postgresql+asyncpg://", "postgresql://"), timeout=20)
    try:
        rows = await connection.fetch(
            """select usename,coalesce(nullif(application_name,''),'unspecified') application_name,
                      state,count(*)::integer connection_count
                 from pg_stat_activity
                where datname=current_database() and pid<>pg_backend_pid()
                group by usename,application_name,state
                order by usename,application_name,state"""
        )
        sessions = [dict(row) for row in rows]
        suspected = [
            row for row in sessions
            if any(marker in row["application_name"].lower() for marker in LEGACY_MARKERS)
        ]
        return {
            "auditedAt": datetime.now(timezone.utc).isoformat(),
            "readOnly": True,
            "sessions": sessions,
            "legacyMarkers": list(LEGACY_MARKERS),
            "suspectedLegacySessions": suspected,
            "legacyWriterSessionDetected": bool(suspected),
            "secretsPrinted": False,
        }
    finally:
        await connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only Phase 11 database writer/session audit")
    parser.add_argument("--admin-env", default="chatbot-service/.env")
    parser.add_argument("--database-url")
    parser.add_argument("--evidence")
    args = parser.parse_args()
    values = dotenv_values(ROOT / args.admin_env)
    dsn = args.database_url or values.get("DB_READ_URL") or values.get("DB_WRITE_URL") or ""
    if not dsn:
        raise SystemExit("database DSN is not configured")
    result = asyncio.run(audit(dsn))
    output = json.dumps(result, indent=2) + "\n"
    if args.evidence:
        path = ROOT / args.evidence
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
