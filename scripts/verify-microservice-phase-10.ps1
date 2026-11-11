param([switch]$LiveAudit, [switch]$RequireLegacyRetired)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$phase10 = Join-Path $root "infra\database\phase10"

$manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $phase10 "ownership.json") | ConvertFrom-Json
if ($manifest.services.Count -ne 6) { throw "Phase 10 ownership manifest must contain six services" }
$expectedSchemas = @('identity','catalog','sales','ai_search','chatbot','recommendation')
$actualSchemas = @($manifest.services | ForEach-Object { $_.schema })
foreach ($schema in $expectedSchemas) {
    if ($schema -notin $actualSchemas) { throw "missing schema ownership: $schema" }
}
if (($manifest.services.runtimeRole | Sort-Object -Unique).Count -ne 6) { throw "runtime roles are not unique" }
if (($manifest.services.ownerRole | Sort-Object -Unique).Count -ne 6) { throw "owner roles are not unique" }

$grants = Get-Content -Raw -Encoding UTF8 (Join-Path $phase10 "V1__finalize_service_role_grants.sql")
foreach ($role in $manifest.services.runtimeRole) {
    if ($grants -notmatch [regex]::Escape("alter role $role set search_path")) { throw "missing explicit search_path for $role" }
}
foreach ($needle in @('revoke create on schema public from public','revoke all on all tables','grant usage on schema')) {
    if ($grants -notmatch [regex]::Escape($needle)) { throw "missing grant invariant: $needle" }
}

$foreignKeys = Get-Content -Raw -Encoding UTF8 (Join-Path $phase10 "V2__remove_cross_service_foreign_keys.sql")
if ($foreignKeys -notmatch "contype='f'" -or $foreignKeys -notmatch 'drop constraint') {
    throw "cross-service FK cleanup is incomplete"
}
$retire = Get-Content -Raw -Encoding UTF8 (Join-Path $phase10 "V3__retire_legacy_structures.sql")
foreach ($gate in @('phase10.cutover_approved','phase10.backup_restore_verified','phase10.data_parity_verified','phase10.stability_window_ended_at')) {
    if ($retire -notmatch [regex]::Escape($gate)) { throw "legacy cleanup lacks safety gate $gate" }
}
if ($retire -match '(?im)^\s*drop\b[^;\r\n]*\bcascade\b') { throw "legacy cleanup must use RESTRICT, not CASCADE" }
if ($retire -notmatch 'drop schema if exists ai_retrieval restrict') { throw "ai_retrieval retirement is missing" }

$auditScript = Join-Path $root "scripts\maintenance\audit-phase-10-database.py"
$python = Join-Path $root "chatbot-service\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) { $python = "python" }
& $python -m py_compile $auditScript
if ($LASTEXITCODE -ne 0) { throw "Phase 10 audit script does not compile" }

if ($LiveAudit) {
    $arguments = @($auditScript)
    if ($RequireLegacyRetired) { $arguments += '--require-legacy-retired' }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Phase 10 live ownership audit failed" }
}

Write-Host "Phase 10 database ownership implementation gate passed."
