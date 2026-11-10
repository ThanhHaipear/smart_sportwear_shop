import argparse
import psycopg

parser = argparse.ArgumentParser()
parser.add_argument("database_url")
args = parser.parse_args()
with psycopg.connect(args.database_url) as connection:
    connection.autocommit = False
    role = connection.execute("select current_user").fetchone()[0]
    if role != "identity_app": raise SystemExit(f"expected identity_app, got {role}")
    if not connection.execute("select has_schema_privilege(current_user,'identity','USAGE')").fetchone()[0]:
        raise SystemExit("identity schema is not usable")
    if connection.execute("select has_schema_privilege(current_user,'public','CREATE')").fetchone()[0]:
        raise SystemExit("identity_app can CREATE in public")
    probe = connection.execute("insert into identity.users(email,full_name) values('permission-probe@example.invalid','probe') returning id").fetchone()[0]
    connection.execute("delete from identity.users where id=%s", (probe,))
    try:
        connection.execute("create table public.identity_permission_probe(id integer)")
        raise SystemExit("identity_app unexpectedly wrote public")
    except psycopg.Error:
        connection.rollback()
print("identity_app write-own/deny-public permission probe passed")
