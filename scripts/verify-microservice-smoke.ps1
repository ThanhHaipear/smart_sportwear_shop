param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot "backend"

$smokeTests = @(
    "LoginIntegrationTest#login_success_returnsUserAndTokens",
    "ProductCatalogIntegrationTest#publicDetail_includesVariantsAndImages_withAvailableQuantityComputed",
    "OrderIntegrationTest#createOrder_fromCartWithMultipleItems_succeedsWithCorrectSnapshotsAndReservesStockAndClearsCart",
    "VietQrPaymentIntegrationTest#endpointCreatesThenResumesTheSamePendingPayment",
    "SePayWebhookIntegrationTest#signedIncomingWebhookMatchesPaymentExactlyOnceUnderConcurrentDelivery"
) -join ","

Write-Host "`n==> Microservice Phase 0 smoke baseline" -ForegroundColor Cyan
Push-Location $backendRoot
try {
    & .\mvnw.cmd "-Dtest=$smokeTests" test
    if ($LASTEXITCODE -ne 0) {
        throw "Microservice smoke baseline failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host "`nMicroservice smoke baseline passed (login, catalog, COD, VietQR and SePay)." -ForegroundColor Green
