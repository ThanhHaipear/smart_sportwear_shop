param(
    [ValidateSet("api", "worker")]
    [string]$Mode = "api"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$service = Join-Path $root "services\search-service"
$python = Join-Path $service ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    throw "Search service virtual environment is missing. Create services/search-service/.venv first."
}
Push-Location $service
try {
    if ($Mode -eq "worker") { & $python -m app.worker }
    else { & $python -m uvicorn app.main:app --host 0.0.0.0 --port 8090 }
} finally { Pop-Location }
