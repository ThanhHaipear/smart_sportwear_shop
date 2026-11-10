param(
    [switch]$ImplementationOnly,
    [string]$CatalogDatabaseUrl = $env:CATALOG_PERMISSION_TEST_URL
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$migration = Get-Content -Raw (Join-Path $root "catalog-service\src\main\resources\db\migration\V1__create_catalog_schema.sql")
if ($migration -notmatch 'revoke all on schema public from catalog_app' -or $migration -match 'references public\.') {
    throw "catalog_app permission/data boundary is invalid"
}
$required = @(
    'variants/{id}/sale-snapshot', 'inventory/reservations', 'for update',
    'idempotency_key varchar(200) not null unique', 'order by v.id for update'
)
$surface = $migration + (Get-Content -Raw (Join-Path $root "catalog-service\src\main\java\com\dunghaiquyen\catalog\api\CatalogApi.java")) +
    (Get-Content -Raw (Join-Path $root "catalog-service\src\main\java\com\dunghaiquyen\catalog\persistence\CatalogRepository.java"))
foreach ($needle in $required) { if ($surface -notmatch [regex]::Escape($needle)) { throw "missing Phase 6 invariant: $needle" } }

Push-Location (Join-Path $root "catalog-service")
try {
    & ..\backend\mvnw.cmd -q -f pom.xml test
    if ($LASTEXITCODE -ne 0) { throw "catalog-service test gate failed" }
} finally { Pop-Location }

$contract = Get-Content -Raw (Join-Path $root "contracts\rest\catalog-service-v1.yaml")
if ($contract -notmatch 'openapi: 3\.0\.3' -or $contract -notmatch 'X-Internal-Service-Token') { throw "catalog REST contract is invalid" }
$compose = Get-Content -Raw (Join-Path $root "docker-compose.yml")
if ($compose -notmatch '(?m)^  catalog-service:') { throw "catalog-service is missing from Docker Compose" }
Write-Host "Phase 6 implementation and concurrency gate passed."

if (-not $ImplementationOnly) {
    if ([string]::IsNullOrWhiteSpace($CatalogDatabaseUrl)) { throw "catalog_app DSN is required by the Phase 6 acceptance gate" }
    & python (Join-Path $root "catalog-service\scripts\verify_schema_permissions.py") $CatalogDatabaseUrl
    if ($LASTEXITCODE -ne 0) { throw "catalog_app permission test failed" }
    Write-Host "Phase 6 acceptance gate passed."
}
