param(
    [switch]$IncludeBackendIntegration,
    [switch]$SkipFrontendBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [scriptblock]$Command
    )

    Write-Host "`n==> $Name" -ForegroundColor Cyan
    Push-Location $WorkingDirectory
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$backendUnitTests = @(
    "MailProviderSelectionTest",
    "ImageStorageProviderSelectionTest",
    "PaymentProviderPropertiesTest",
    "OrderFinancialPolicyTest",
    "PendingPaymentExpiryServiceTest",
    "SePayWebhookSignatureServiceTest",
    "VietQrPaymentServiceTest",
    "ProductHybridSearchServiceTest",
    "ProductImageServiceTest",
    "AssociationRuleMiningServiceTest",
    "RecommendationCacheServiceTest",
    "RecommendationLogServiceTest",
    "RecommendationServiceTest",
    "VisualSearchClientTest",
    "VisualSearchRateLimiterTest",
    "VisualSearchServiceTest",
    "CatalogOutboxServiceTest"
) -join ","

Invoke-Checked `
    -Name "Backend unit baseline" `
    -WorkingDirectory (Join-Path $projectRoot "backend") `
    -Command { & .\mvnw.cmd "-Dtest=$backendUnitTests" test }

Invoke-Checked `
    -Name "Backend package" `
    -WorkingDirectory (Join-Path $projectRoot "backend") `
    -Command { & .\mvnw.cmd -DskipTests package }

Invoke-Checked `
    -Name "Visual Search tests" `
    -WorkingDirectory (Join-Path $projectRoot "visual-search-service") `
    -Command { & python -m pytest -q }

$chatbotPython = Join-Path $projectRoot "chatbot-service\.venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $chatbotPython)) {
    throw "Chatbot virtual environment not found at $chatbotPython"
}

Invoke-Checked `
    -Name "Chatbot and Product Search tests" `
    -WorkingDirectory (Join-Path $projectRoot "chatbot-service") `
    -Command { & $chatbotPython -m pytest -q }

Invoke-Checked `
    -Name "Storefront lint" `
    -WorkingDirectory (Join-Path $projectRoot "frontend\storefront") `
    -Command { & npm.cmd run lint }

Invoke-Checked `
    -Name "Admin lint" `
    -WorkingDirectory (Join-Path $projectRoot "frontend\admin") `
    -Command { & npm.cmd run lint }

if (-not $SkipFrontendBuild) {
    Invoke-Checked `
        -Name "Storefront production build" `
        -WorkingDirectory (Join-Path $projectRoot "frontend\storefront") `
        -Command { & npm.cmd run build }

    Invoke-Checked `
        -Name "Admin production build" `
        -WorkingDirectory (Join-Path $projectRoot "frontend\admin") `
        -Command { & npm.cmd run build }
}

if ($IncludeBackendIntegration) {
    Invoke-Checked `
        -Name "Backend current-runtime integration gate" `
        -WorkingDirectory (Join-Path $projectRoot "backend") `
        -Command {
            & .\mvnw.cmd `
                "-Dtest=!V2OneAMigrationTest,!V2OneBMigrationTest,!V2OneCMigrationTest" `
                "-DexcludedGroups=phase0-excluded" `
                test
        }
}

Write-Host "`nMicroservice migration baseline checks passed." -ForegroundColor Green
