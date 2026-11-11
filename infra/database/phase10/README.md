# Phase 10 database finalization

These files are deliberately outside every service's automatic migration location.

1. Run the read-only audit and resolve every violation.
2. Apply `V1` and `V2` with the migration role inside a reviewed transaction.
3. Run the six runtime-role permission probes and the audit again.
4. Only after all route/write cut-overs, an approved stability window, and a verified restore point,
   set the three transaction-local gates and execute `V3` manually.

Example for the final step (values are examples, not approval):

```sql
begin;
set local phase10.cutover_approved='true';
set local phase10.backup_restore_verified='true';
set local phase10.data_parity_verified='true';
set local phase10.stability_window_ended_at='2026-11-11T00:00:00Z';
\i V3__retire_legacy_structures.sql
commit;
```

`V3` uses `RESTRICT`, never `CASCADE`. An unexpected dependency aborts the transaction. Deleted legacy
data can only be restored from the verified backup; the temporary-grant rollback below does not restore data.

Temporary permission rollback (maximum 24 hours) must be applied manually and accompanied by an incident
record and expiry job:

```sql
begin;
set local phase10.rollback_expires_at='2026-11-12T00:00:00Z';
-- Grant only the named relation/privilege needed by the incident; never grant a whole foreign schema.
grant select on public.<relation> to <runtime_role>;
commit;
```

Revoke the exceptional grant before the timestamp and rerun the audit. There is intentionally no blanket
rollback script because it would recreate the shared-database coupling Phase 10 removes.
