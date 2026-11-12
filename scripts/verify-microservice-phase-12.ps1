[CmdletBinding()]
param(
    [switch]$StaticOnly,
    [switch]$LivePermissions,
    [string]$EvidencePath
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$startedAt = (Get-Date).ToUniversalTime()
$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check([string]$Name, [string]$Kind, [string]$Result) {
    $checks.Add([ordered]@{ name = $Name; kind = $Kind; result = $Result })
}

function Assert-File([string]$RelativePath) {
    $path = Join-Path $repo $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required file: $RelativePath" }
}

function Assert-Contains([string]$RelativePath, [string[]]$Needles) {
    Assert-File $RelativePath
    $content = Get-Content -Raw -Encoding UTF8 (Join-Path $repo $RelativePath)
    foreach ($needle in $Needles) {
        if ($content -notmatch [regex]::Escape($needle)) {
            throw "$RelativePath does not contain required contract marker: $needle"
        }
    }
}

function Invoke-Gate([string]$Name, [string]$Directory, [string]$Command, [string[]]$Arguments) {
    Push-Location (Join-Path $repo $Directory)
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
        Add-Check $Name 'test' 'passed'
    } finally {
        Pop-Location
    }
}

$services = @(
    @{ name='search-service'; path='services/search-service'; schema='ai_search'; role='search_app'; migration='migrations/V1__create_ai_search.sql'; permission='scripts/verify_schema_permissions.py'; contract='contracts/rest/search-service-v1.yaml' },
    @{ name='chatbot-service'; path='chatbot-service'; schema='chatbot'; role='chatbot_app'; migration='migrations/V1__create_chatbot_schema.sql'; permission='scripts/verify_schema_permissions.py'; contract='contracts/rest/chatbot-service-v1.yaml' },
    @{ name='recommendation-service'; path='recommendation-service'; schema='recommendation'; role='recommendation_app'; migration='migrations/V1__create_recommendation_schema.sql'; permission='scripts/verify_schema_permissions.py'; contract='contracts/rest/recommendation-service-v1.yaml' },
    @{ name='catalog-service'; path='catalog-service'; schema='catalog'; role='catalog_app'; migration='src/main/resources/db/migration/V1__create_catalog_schema.sql'; permission='scripts/verify_schema_permissions.py'; contract='contracts/rest/catalog-service-v1.yaml' },
    @{ name='identity-service'; path='identity-service'; schema='identity'; role='identity_app'; migration='src/main/resources/db/migration/V1__create_identity_schema.sql'; permission='scripts/verify_schema_permissions.py'; contract='contracts/rest/identity-service-v1.yaml' },
    @{ name='sales-service'; path='sales-service'; schema='sales'; role='sales_app'; migration='src/main/resources/db/migration/V1__create_sales_schema.sql'; permission='scripts/verify_schema_permissions.py'; contract='contracts/rest/sales-service-v1.yaml' }
)

foreach ($service in $services) {
    foreach ($file in @(
        "$($service.path)/Dockerfile",
        "$($service.path)/README.md",
        "$($service.path)/$($service.migration)",
        "$($service.path)/$($service.permission)",
        $service.contract
    )) { Assert-File $file }
    Assert-Contains "$($service.path)/$($service.migration)" @($service.schema, $service.role)
    Assert-Contains $service.contract @('version: 1.0.0', 'paths:')
    $contractText = Get-Content -Raw -Encoding UTF8 (Join-Path $repo $service.contract)
    if ($contractText -notmatch '(?m)^openapi:\s+3\.(0|1)\.') { throw "$($service.contract) is not an OpenAPI 3 contract" }
    Add-Check "$($service.name)-artifacts" 'static' 'passed'
}

$contractMarkers = @{
    'contracts/rest/search-service-v1.yaml' = @('/internal/v1/search:', '/internal/v1/search/image:', 'X-Internal-Service-Token')
    'contracts/rest/chatbot-service-v1.yaml' = @('/chat:', '/chat/stream:', '/health/ready:')
    'contracts/rest/recommendation-service-v1.yaml' = @('/api/v1/recommendations/products/{product_id}:', '/internal/v1/events:', 'X-Internal-Service-Token')
    'contracts/rest/catalog-service-v1.yaml' = @('/internal/v1/variants/{id}/sale-snapshot:', '/internal/v1/inventory/reservations:')
    'contracts/rest/identity-service-v1.yaml' = @('/api/v1/auth/login:', '/api/v1/users/me:')
    'contracts/rest/sales-service-v1.yaml' = @('/api/v1/orders:', '/api/v1/webhooks/sepay:')
}
foreach ($entry in $contractMarkers.GetEnumerator()) { Assert-Contains $entry.Key $entry.Value }
Assert-File 'docs/microservice-phase-12-final-acceptance.md'
Add-Check 'versioned-rest-contracts' 'static' 'passed'

$composePath = Join-Path $repo 'docker-compose.yml'
$compose = Get-Content -Raw -Encoding UTF8 $composePath
foreach ($name in @('search-service','chatbot-service','recommendation-service','catalog-service','identity-service','sales-service','gateway')) {
    if ($compose -notmatch "(?m)^  $([regex]::Escape($name)):") { throw "Compose service missing: $name" }
}
if ($compose -match 'legacy_upstream|LEGACY_UPSTREAM|GATEWAY_(IDENTITY|CATALOG|SALES|CHATBOT|RECOMMENDATION)_CUTOVER') {
    throw 'Legacy routing or cut-over fallback is still configured.'
}
Add-Check 'independent-compose-deployments' 'static' 'passed'

& (Join-Path $repo 'scripts/verify-microservice-phase-11.ps1') -StaticOnly
Add-Check 'no-legacy-fallback' 'static' 'passed'

$python = 'python'
foreach ($service in $services) {
    & $python -m py_compile (Join-Path $repo "$($service.path)/$($service.permission)")
    if ($LASTEXITCODE -ne 0) { throw "Permission probe does not compile: $($service.name)" }
}
Add-Check 'permission-probes-compile' 'static' 'passed'

if (-not $StaticOnly) {
    Invoke-Gate 'search-tests' 'services/search-service' $python @('-m','pytest','-q')

    $chatbotPython = Join-Path $repo 'chatbot-service/.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $chatbotPython)) { $chatbotPython = $python }
    Invoke-Gate 'chatbot-tests' 'chatbot-service' $chatbotPython @('-m','pytest','-q')
    Invoke-Gate 'recommendation-tests' 'recommendation-service' $python @('-m','pytest','-q')

    $maven = Join-Path $repo 'mvnw.cmd'
    foreach ($service in @('catalog-service','identity-service','sales-service')) {
        Invoke-Gate "$service-tests" $service $maven @('-q','-f','pom.xml','test')
    }
    Invoke-Gate 'gateway-tests' 'gateway' $python @('-m','pytest','-q')
    Invoke-Gate 'storefront-typecheck' 'frontend/storefront' 'npm.cmd' @('run','typecheck')
    Invoke-Gate 'admin-lint' 'frontend/admin' 'npm.cmd' @('run','lint')
    Invoke-Gate 'compose-config' '.' 'docker' @('compose','config','--quiet')
}

if ($LivePermissions) {
    $permissionVariables = @{
        'search-service'='PHASE12_SEARCH_DATABASE_URL'
        'chatbot-service'='PHASE12_CHATBOT_DATABASE_URL'
        'recommendation-service'='PHASE12_RECOMMENDATION_DATABASE_URL'
        'catalog-service'='PHASE12_CATALOG_DATABASE_URL'
        'identity-service'='PHASE12_IDENTITY_DATABASE_URL'
        'sales-service'='PHASE12_SALES_DATABASE_URL'
    }
    foreach ($service in $services) {
        $variable = $permissionVariables[$service.name]
        $dsn = [Environment]::GetEnvironmentVariable($variable)
        if ([string]::IsNullOrWhiteSpace($dsn)) { throw "$variable is required for -LivePermissions" }
        Push-Location (Join-Path $repo $service.path)
        try {
            if ($service.name -eq 'search-service') {
                $temporaryEnv = Join-Path ([IO.Path]::GetTempPath()) ("phase12-search-" + [guid]::NewGuid() + '.env')
                try {
                    [IO.File]::WriteAllText($temporaryEnv, "DATABASE_URL=$dsn`n")
                    & $python $service.permission '--env-file' $temporaryEnv
                } finally {
                    if (Test-Path -LiteralPath $temporaryEnv) { Remove-Item -LiteralPath $temporaryEnv -Force }
                }
            } elseif ($service.name -eq 'chatbot-service') {
                & $python $service.permission $dsn
            } else {
                & $python $service.permission $dsn
            }
            if ($LASTEXITCODE -ne 0) { throw "$($service.name) live permission probe failed" }
            Add-Check "$($service.name)-live-permissions" 'live' 'passed'
        } finally { Pop-Location }
    }
}

$finishedAt = (Get-Date).ToUniversalTime()
if ($EvidencePath) {
    $absoluteEvidencePath = if ([IO.Path]::IsPathRooted($EvidencePath)) { $EvidencePath } else { Join-Path $repo $EvidencePath }
    $parent = Split-Path -Parent $absoluteEvidencePath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
    $evidence = [ordered]@{
        phase = 12
        status = 'passed'
        mode = if ($StaticOnly) { 'static' } elseif ($LivePermissions) { 'full-with-live-permissions' } else { 'full-local' }
        startedAt = $startedAt.ToString('o')
        finishedAt = $finishedAt.ToString('o')
        serviceCount = $services.Count
        checks = $checks
    }
    $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $absoluteEvidencePath -Encoding UTF8
    Write-Host "Evidence written to $absoluteEvidencePath"
}

Write-Host "Phase 12 final microservice acceptance gate passed ($($checks.Count) checks)."
