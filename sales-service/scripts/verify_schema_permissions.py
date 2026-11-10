import argparse
import psycopg

parser=argparse.ArgumentParser();parser.add_argument("database_url");args=parser.parse_args()
with psycopg.connect(args.database_url) as connection:
    if connection.execute("select current_user").fetchone()[0].split(".",1)[0] != "sales_app": raise SystemExit("expected sales_app runtime role")
    if not connection.execute("select has_schema_privilege(current_user,'sales','USAGE')").fetchone()[0]: raise SystemExit("sales schema is not usable")
    if connection.execute("select has_schema_privilege(current_user,'public','CREATE')").fetchone()[0]: raise SystemExit("sales_app can CREATE in public")
    connection.execute("insert into sales.carts(session_id) values('permission-probe')")
    try:
        connection.execute("create table public.sales_permission_probe(id integer)");raise SystemExit("sales_app unexpectedly wrote public")
    except psycopg.Error: connection.rollback()
print("sales_app write-own/deny-public permission probe passed")
