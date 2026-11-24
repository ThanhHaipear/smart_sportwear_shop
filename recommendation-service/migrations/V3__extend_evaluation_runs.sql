-- Phase 9: evaluation_runs stores the full offline metric set (docs/recommendation-design.md 7.6, 7.8).
-- New columns are nullable so rows written before V3 (precision/recall only) stay valid.
-- Apply with the migration/owner role, like V1; the runtime role recommendation_app cannot alter tables.
set role recommendation_owner;
set search_path = recommendation, pg_catalog;

alter table evaluation_runs
    add column if not exists task varchar(30),
    add column if not exists segment varchar(10),
    add column if not exists case_count integer,
    add column if not exists hit_rate_at_k double precision,
    add column if not exists ndcg_at_k double precision,
    add column if not exists coverage_at_k double precision,
    add column if not exists report_sha256 char(64);

do $$ begin
    if not exists (select 1 from pg_constraint where conname = 'chk_evaluation_runs_v3_ranges') then
        alter table evaluation_runs add constraint chk_evaluation_runs_v3_ranges check (
            (hit_rate_at_k is null or hit_rate_at_k between 0 and 1)
            and (ndcg_at_k is null or ndcg_at_k between 0 and 1)
            and (coverage_at_k is null or coverage_at_k between 0 and 1)
            and (case_count is null or case_count >= 0)
            and (segment is null or segment in ('all', 'warm', 'cold')));
    end if;
end $$;

comment on column evaluation_runs.report_sha256 is 'SHA-256 of the evidence report the row was copied from';

reset role;
