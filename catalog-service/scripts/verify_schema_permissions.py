import argparse
import psycopg

parser = argparse.ArgumentParser()
parser.add_argument("database_url")
args = parser.parse_args()
with psycopg.connect(args.database_url) as connection:
    connection.autocommit = False
    role = connection.execute("select current_user").fetchone()[0]
    if role != "catalog_app": raise SystemExit(f"expected catalog_app, got {role}")
    if not connection.execute("select has_schema_privilege(current_user,'catalog','USAGE')").fetchone()[0]:
        raise SystemExit("catalog schema is not usable")
    if connection.execute("select has_schema_privilege(current_user,'public','CREATE')").fetchone()[0]:
        raise SystemExit("catalog_app can CREATE in public")
    probe = connection.execute("insert into catalog.brands(code,name,slug) values('PERMISSION_PROBE','probe','permission-probe') returning id").fetchone()[0]
    connection.execute("delete from catalog.brands where id=%s", (probe,))
    try:
        connection.execute("create table public.catalog_permission_probe(id integer)")
        raise SystemExit("catalog_app unexpectedly wrote public")
    except psycopg.Error:
        connection.rollback()
print("catalog_app write-own/deny-public permission probe passed")
