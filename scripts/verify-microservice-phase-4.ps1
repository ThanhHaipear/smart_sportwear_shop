param(
    [switch]$SkipBackendCompile,
    [switch]$ImplementationOnly,
    [string]$ChatbotDatabaseUrl = $env:CHATBOT_PERMISSION_TEST_URL
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$migration = Get-Content -Raw (Join-Path $root "chatbot-service\migrations\V1__create_chatbot_schema.sql")
if ($migration -notmatch 'REVOKE CREATE ON SCHEMA public FROM chatbot_app' -or
    $migration -notmatch 'GRANT USAGE ON SCHEMA chatbot TO chatbot_app') {
    throw "chatbot_app permission boundary is missing from the migration"
}

Push-Location (Join-Path $root "chatbot-service")
try {
    & .\.venv\Scripts\python.exe -m pytest -q
    if ($LASTEXITCODE -ne 0) { throw "chatbot-service test gate failed" }
} finally {
    Pop-Location
}

if (-not $SkipBackendCompile) {
    Push-Location (Join-Path $root "backend")
    try {
        & .\mvnw.cmd -q -DskipTests compile
        if ($LASTEXITCODE -ne 0) { throw "backend contract compile gate failed" }
    } finally {
        Pop-Location
    }
}

Write-Host "Phase 4 implementation gate passed."

if (-not $ImplementationOnly) {
    if ([string]::IsNullOrWhiteSpace($ChatbotDatabaseUrl)) {
        throw "CHATBOT_PERMISSION_TEST_URL for chatbot_app is required by the Phase 4 acceptance gate"
    }
    Push-Location (Join-Path $root "chatbot-service")
    try {
        & .\.venv\Scripts\python.exe .\scripts\verify_schema_permissions.py $ChatbotDatabaseUrl
        if ($LASTEXITCODE -ne 0) { throw "chatbot_app permission test failed" }
    } finally {
        Pop-Location
    }
    Write-Host "Phase 4 acceptance gate passed."
}
