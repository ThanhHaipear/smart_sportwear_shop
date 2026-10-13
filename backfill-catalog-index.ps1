$ErrorActionPreference = "Stop"

$serviceDir = Join-Path $PSScriptRoot "chatbot-service"
$pythonExe = Join-Path $serviceDir ".venv\Scripts\python.exe"
$backendEnv = Join-Path $PSScriptRoot "backend\.env"

if (-not (Test-Path -LiteralPath $pythonExe -PathType Leaf)) {
    throw "Missing chatbot-service virtual environment at $pythonExe"
}

if (-not (Test-Path -LiteralPath $backendEnv -PathType Leaf)) {
    throw "Missing backend database configuration at $backendEnv"
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

Set-Location $serviceDir
$env:PYTHONUTF8 = "1"
& $pythonExe scripts\backfill_catalog_read_model.py
exit $LASTEXITCODE
