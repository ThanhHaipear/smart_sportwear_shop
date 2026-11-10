import argparse
import psycopg

parser = argparse.ArgumentParser()
parser.add_argument("database_url")
args = parser.parse_args()
with psycopg.connect(args.database_url) as connection:
    connection.autocommit = False
    role = connection.execute("select current_user").fetchone()[0]
    if role != "recommendation_app": raise SystemExit(f"expected recommendation_app, got {role}")
    if not connection.execute("select has_schema_privilege(current_user,'recommendation','USAGE')").fetchone()[0]:
        raise SystemExit("recommendation schema is not usable")
    if connection.execute("select has_schema_privilege(current_user,'public','CREATE')").fetchone()[0]:
        raise SystemExit("recommendation_app can CREATE in public")
    connection.execute("insert into recommendation.processed_events(consumer_name,event_id) values('permission-probe',gen_random_uuid())")
    try:
        connection.execute("create table public.recommendation_permission_probe(id integer)")
        raise SystemExit("recommendation_app unexpectedly wrote public")
    except psycopg.Error:
        connection.rollback()
print("recommendation_app write/deny permission probe passed")
