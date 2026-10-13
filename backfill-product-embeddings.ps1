param(
    [ValidateSet("text", "image", "all")]
    [string]$Modality = "all",
    [switch]$CoverageOnly,
    [switch]$Verify
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $MyInvocation.MyCommand.Path
$service = Join-Path $workspace "chatbot-service"
$python = Join-Path $service ".venv\Scripts\python.exe"
$backendEnv = Join-Path $workspace "backend\.env"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Missing chatbot-service virtual environment: $python"
}
if (-not (Test-Path -LiteralPath $backendEnv -PathType Leaf)) {
    throw "Missing backend database configuration: $backendEnv"
}

$database = @{}
foreach ($line in Get-Content -LiteralPath $backendEnv) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $database[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
    }
}
foreach ($required in @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD')) {
    if (-not $database.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($database[$required])) {
        throw "Missing $required in backend database configuration"
    }
}
$dbUser = [Uri]::EscapeDataString($database['DB_USERNAME'])
$dbPassword = [Uri]::EscapeDataString($database['DB_PASSWORD'])
$dbName = [Uri]::EscapeDataString($database['DB_NAME'])
$dbParams = $database['DB_PARAMS']
$query = if ([string]::IsNullOrWhiteSpace($dbParams)) { '' } else { '?' + $dbParams.TrimStart('?') }
$env:DB_WRITE_URL = "postgresql://${dbUser}:${dbPassword}@$($database['DB_HOST']):$($database['DB_PORT'])/${dbName}${query}"

Push-Location $service
try {
    $arguments = @("scripts/backfill_product_embeddings_v2.py", "--modality", $Modality)
    if ($CoverageOnly) {
        $arguments += "--coverage-only"
    }
    if ($Verify) {
        $arguments += "--verify"
    }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Embedding backfill failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
