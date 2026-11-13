param(
    [string]$Output = ".env.phase10.local",
    [string]$SecretSource = ".env.phase10.local"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path $Path)) { throw "Required env file is missing: $Path" }
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $parts = $line.Split('=', 2)
        $values[$parts[0].Trim()] = $parts[1]
    }
    return $values
}

function Require-Value($Values, [string]$Key, [string]$Source) {
    $value = $Values[$Key]
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq 'change-me') {
        throw "Required value $Key is missing from $Source"
    }
    return $value
}

$destination = Join-Path $root $Output
$existingRuntime = Read-DotEnv (Join-Path $root $SecretSource)
$catalog = Read-DotEnv (Join-Path $root "catalog-service\.env")
$identity = Read-DotEnv (Join-Path $root "identity-service\.env")
$sales = Read-DotEnv (Join-Path $root "sales-service\.env")
$search = Read-DotEnv (Join-Path $root "services\search-service\.env")
$chatbot = Read-DotEnv (Join-Path $root "chatbot-service\.env")
$recommendation = Read-DotEnv (Join-Path $root "recommendation-service\.env")

$runtime = [ordered]@{
    RABBITMQ_DEFAULT_USER = 'visual_search'
    RABBITMQ_DEFAULT_PASS = 'change-me'
    GATEWAY_PUBLIC_PORT = '8085'
    CATALOG_DATABASE_URL = Require-Value $catalog 'CATALOG_DATABASE_URL' 'catalog-service/.env'
    CATALOG_DB_USERNAME = Require-Value $catalog 'CATALOG_DB_USERNAME' 'catalog-service/.env'
    CATALOG_DB_PASSWORD = Require-Value $catalog 'CATALOG_DB_PASSWORD' 'catalog-service/.env'
    CATALOG_INTERNAL_TOKEN = Require-Value $catalog 'CATALOG_INTERNAL_TOKEN' 'catalog-service/.env'
    CATALOG_EVENTS_ENABLED = 'true'
    CATALOG_FLYWAY_ENABLED = 'false'
    IDENTITY_DATABASE_URL = Require-Value $identity 'IDENTITY_DATABASE_URL' 'identity-service/.env'
    IDENTITY_DB_USERNAME = Require-Value $identity 'IDENTITY_DB_USERNAME' 'identity-service/.env'
    IDENTITY_DB_PASSWORD = Require-Value $identity 'IDENTITY_DB_PASSWORD' 'identity-service/.env'
    JWT_ACCESS_SECRET = Require-Value $existingRuntime 'JWT_ACCESS_SECRET' $SecretSource
    JWT_REFRESH_SECRET = Require-Value $existingRuntime 'JWT_REFRESH_SECRET' $SecretSource
    IDENTITY_EVENTS_ENABLED = 'true'
    IDENTITY_FLYWAY_ENABLED = 'false'
    SALES_DATABASE_URL = Require-Value $sales 'SALES_DATABASE_URL' 'sales-service/.env'
    SALES_DB_USERNAME = Require-Value $sales 'SALES_DB_USERNAME' 'sales-service/.env'
    SALES_DB_PASSWORD = Require-Value $sales 'SALES_DB_PASSWORD' 'sales-service/.env'
    SALES_INTERNAL_TOKEN = Require-Value $sales 'SALES_INTERNAL_TOKEN' 'sales-service/.env'
    SALES_EVENTS_ENABLED = 'true'
    SALES_FLYWAY_ENABLED = 'false'
    SEPAY_WEBHOOK_ENABLED = 'true'
    SEPAY_WEBHOOK_SECRET = Require-Value $existingRuntime 'SEPAY_WEBHOOK_SECRET' $SecretSource
    VIETQR_ACCOUNT_NUMBER = Require-Value $existingRuntime 'VIETQR_ACCOUNT_NUMBER' $SecretSource
    VIETQR_BANK_BIN = Require-Value $existingRuntime 'VIETQR_BANK_BIN' $SecretSource
    VIETQR_ACCOUNT_NAME = Require-Value $existingRuntime 'VIETQR_ACCOUNT_NAME' $SecretSource
    SEARCH_DATABASE_URL = Require-Value $search 'DATABASE_URL' 'services/search-service/.env'
    SEARCH_SERVICE_INTERNAL_TOKEN = Require-Value $search 'INTERNAL_SERVICE_TOKEN' 'services/search-service/.env'
    SEARCH_ENABLED = 'true'
    VISUAL_SEARCH_ENABLED = 'false'
    CHATBOT_DATABASE_URL = Require-Value $chatbot 'CHATBOT_DATABASE_URL' 'chatbot-service/.env'
    RECOMMENDATION_DATABASE_URL = Require-Value $recommendation 'RECOMMENDATION_DATABASE_URL' 'recommendation-service/.env'
    RECOMMENDATION_INTERNAL_TOKEN = Require-Value $recommendation 'RECOMMENDATION_INTERNAL_TOKEN' 'recommendation-service/.env'
    RECOMMENDATION_ENABLED = 'true'
    GATEWAY_IDENTITY_CUTOVER_ENABLED = 'true'
    GATEWAY_CATALOG_CUTOVER_ENABLED = 'true'
    GATEWAY_SALES_CUTOVER_ENABLED = 'true'
    GATEWAY_CHATBOT_CUTOVER_ENABLED = 'true'
    GATEWAY_RECOMMENDATION_CUTOVER_ENABLED = 'true'
}

$lines = $runtime.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
[System.IO.File]::WriteAllLines($destination, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Host "Prepared $Output with $($runtime.Count) runtime settings; values were not printed."
