[CmdletBinding()]
param([switch]$StaticOnly)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$gateway = Get-Content -LiteralPath (Join-Path $repo 'gateway/app/main.py') -Raw
if ($gateway -match 'legacy_upstream|chatbot_legacy_upstream|cutover_enabled') { throw 'Legacy gateway fallback is still present.' }
$compose = Get-Content -LiteralPath (Join-Path $repo 'docker-compose.yml') -Raw
if ($compose -match 'LEGACY_UPSTREAM|GATEWAY_(IDENTITY|CATALOG|SALES|CHATBOT|RECOMMENDATION)_CUTOVER') { throw 'Legacy gateway configuration is still present.' }
$deprecated = rg -n '/api/v1/(realtime|inventory)/stream' (Join-Path $repo 'frontend') 2>$null
if ($LASTEXITCODE -eq 0 -and $deprecated) { throw "Deprecated frontend SSE contract remains:`n$deprecated" }
if (-not $StaticOnly) {
    Push-Location (Join-Path $repo 'gateway'); try { python -m pytest -q; if ($LASTEXITCODE) { throw 'Gateway tests failed.' } } finally { Pop-Location }
    Push-Location (Join-Path $repo 'sales-service'); try { & (Join-Path $repo 'mvnw.cmd') -q -f pom.xml test; if ($LASTEXITCODE) { throw 'Sales tests failed.' } } finally { Pop-Location }
    Push-Location (Join-Path $repo 'catalog-service'); try { & (Join-Path $repo 'mvnw.cmd') -q -f pom.xml test; if ($LASTEXITCODE) { throw 'Catalog tests failed.' } } finally { Pop-Location }
    Push-Location (Join-Path $repo 'frontend/storefront'); try { npm.cmd run typecheck; if ($LASTEXITCODE) { throw 'Storefront typecheck failed.' } } finally { Pop-Location }
    Push-Location (Join-Path $repo 'frontend/admin'); try { npm.cmd run lint; if ($LASTEXITCODE) { throw 'Admin lint failed.' } } finally { Pop-Location }
}
Write-Host 'Phase 11 local regression gate passed.'
