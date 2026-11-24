"""Run Recommendation retention or an approved account unlinking with the schema owner."""

import argparse
import json
import os
from uuid import UUID

import psycopg
from psycopg.rows import dict_row


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--expire", action="store_true", help="expire one batch per table")
    mode.add_argument("--erase-account", type=UUID, metavar="UUID", help="unlink an approved account")
    parser.add_argument("--batch-size", type=int, default=5000)
    parser.add_argument("--execute", action="store_true", help="commit changes instead of rolling back")
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 5000:
        parser.error("--batch-size must be between 1 and 5000")
    database_url = os.getenv("RECOMMENDATION_MAINTENANCE_DATABASE_URL")
    if not database_url:
        parser.error("RECOMMENDATION_MAINTENANCE_DATABASE_URL is required")

    with psycopg.connect(database_url, row_factory=dict_row) as connection:
        connection.isolation_level = psycopg.IsolationLevel.READ_COMMITTED
        connection.execute("set local role recommendation_owner")
        if args.expire:
            counts = connection.execute("select * from recommendation.expire_recommendation_data(%s)",
                                        (args.batch_size,)).fetchone()
        else:
            counts = connection.execute("select * from recommendation.erase_recommendation_account(%s)",
                                        (args.erase_account,)).fetchone()
        if not args.execute:
            connection.rollback()
    print(json.dumps({"committed": args.execute, "affected": counts}))


if __name__ == "__main__":
    main()
