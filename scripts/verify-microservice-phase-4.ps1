param([switch]$SkipBackendCompile)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

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
