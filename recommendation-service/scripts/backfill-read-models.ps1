# Runs backfill_read_models.py with the same environment as the local launcher.
# Dry-run by default; pass -Execute to write schema `recommendation`.
param([switch]$Execute)

$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
. (Join-Path $repo "scripts\run\common-local.ps1")
$service = Join-Path $repo "recommendation-service"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $service ".env")
$python = Get-LocalPython -PreferredPath (Join-Path $service ".venv\Scripts\python.exe")

$arguments = @((Join-Path $service "scripts\backfill_read_models.py"))
if ($Execute) { $arguments += "--execute" }
Push-Location $service
try { & $python @arguments; exit $LASTEXITCODE }
finally { Pop-Location }
