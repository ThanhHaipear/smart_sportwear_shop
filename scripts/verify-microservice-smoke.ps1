[CmdletBinding()]
param([switch]$StaticOnly)

$ErrorActionPreference = "Stop"
$acceptanceScript = Join-Path $PSScriptRoot "verify-microservice-phase-12.ps1"

Write-Host "`n==> Current microservice verification" -ForegroundColor Cyan
if ($StaticOnly) {
    & $acceptanceScript -StaticOnly
} else {
    & $acceptanceScript
}
if ($LASTEXITCODE -ne 0) {
    throw "Current microservice verification failed with exit code $LASTEXITCODE"
}

Write-Host "`nCurrent gateway and six-service verification passed." -ForegroundColor Green
