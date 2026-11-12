param(
    [switch]$ImplementationOnly,
    [string]$IdentityDatabaseUrl = $env:IDENTITY_PERMISSION_TEST_URL
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$migration = Get-Content -Raw (Join-Path $root "identity-service\src\main\resources\db\migration\V1__create_identity_schema.sql")
if ($migration -notmatch 'revoke all on schema public from identity_app' -or $migration -match 'references public\.') {
    throw "identity_app permission/data boundary is invalid"
}
$surface = $migration + (Get-Content -Raw (Join-Path $root "identity-service\src\main\java\com\dunghaiquyen\identity\domain\IdentityService.java")) +
    (Get-Content -Raw (Join-Path $root "identity-service\src\main\java\com\dunghaiquyen\identity\security\JwtService.java"))
foreach ($needle in @('REUSE_DETECTED','previousAccessSecret','identity.user.created.v1','identity.user.updated.v1')) {
    if ($surface -notmatch [regex]::Escape($needle)) { throw "missing Phase 7 invariant: $needle" }
}
Push-Location (Join-Path $root "identity-service")
try {
    & ..\mvnw.cmd -q -f pom.xml test
    if ($LASTEXITCODE -ne 0) { throw "identity-service test gate failed" }
} finally { Pop-Location }
$contract = Get-Content -Raw (Join-Path $root "contracts\rest\identity-service-v1.yaml")
if ($contract -notmatch 'openapi: 3\.0\.3' -or $contract -notmatch '/api/v1/auth/refresh') { throw "identity REST contract is invalid" }
$event = Get-Content -Raw (Join-Path $root "contracts\identity-user-event-v1.schema.json") | ConvertFrom-Json
if ($event.title -ne 'Identity user event v1') { throw "identity event contract is invalid" }
$compose = Get-Content -Raw (Join-Path $root "docker-compose.yml")
if ($compose -notmatch '(?m)^  identity-service:') { throw "identity-service is missing from Docker Compose" }
Write-Host "Phase 7 implementation, auth rotation and contract gate passed."
if (-not $ImplementationOnly) {
    if ([string]::IsNullOrWhiteSpace($IdentityDatabaseUrl)) { throw "identity_app DSN is required by the Phase 7 acceptance gate" }
    & python (Join-Path $root "identity-service\scripts\verify_schema_permissions.py") $IdentityDatabaseUrl
    if ($LASTEXITCODE -ne 0) { throw "identity_app permission test failed" }
    Write-Host "Phase 7 acceptance gate passed."
}
