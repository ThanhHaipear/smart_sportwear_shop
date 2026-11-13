param(
    [switch]$SkipBackendTests,
    [switch]$ImplementationOnly,
    [string]$RecommendationDatabaseUrl = $env:RECOMMENDATION_PERMISSION_TEST_URL
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$migration = Get-Content -Raw (Join-Path $root "recommendation-service\migrations\V1__create_recommendation_schema.sql")
if ($migration -notmatch 'REVOKE ALL ON SCHEMA public FROM recommendation_app' -or
    $migration -match 'references public\.') {
    throw "recommendation_app permission/data boundary is invalid"
}

Push-Location (Join-Path $root "recommendation-service")
try {
    & python -m pytest -q
    if ($LASTEXITCODE -ne 0) { throw "recommendation-service test gate failed" }
} finally { Pop-Location }

if (-not $SkipBackendTests) {
    Write-Host "Legacy monolith architecture gate retired; Recommendation boundaries are verified in recommendation-service."
}

Get-Content -Raw (Join-Path $root "rabbitmq\definitions.json") | ConvertFrom-Json | Out-Null
Write-Host "Phase 5 implementation gate passed."

if (-not $ImplementationOnly) {
    if ([string]::IsNullOrWhiteSpace($RecommendationDatabaseUrl)) {
        throw "recommendation_app DSN is required by the Phase 5 acceptance gate"
    }
    Push-Location (Join-Path $root "recommendation-service")
    try {
        & python .\scripts\verify_schema_permissions.py $RecommendationDatabaseUrl
        if ($LASTEXITCODE -ne 0) { throw "recommendation_app permission test failed" }
    } finally { Pop-Location }
    Write-Host "Phase 5 acceptance gate passed."
}
