param(
    [switch]$IncludeRabbitMqIntegration
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][scriptblock]$Command
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

$backendTests = @(
    "CatalogEventContractTest",
    "CatalogOutboxServiceTest",
    "CatalogOutboxPublisherTest",
    "IntegrationEventsEndpointTest"
) -join ","

Invoke-Checked `
    -Name "Backend event contract and outbox tests" `
    -WorkingDirectory (Join-Path $projectRoot "backend") `
    -Command { & .\mvnw.cmd "-Dtest=$backendTests" test }

$visualTests = @(
    "tests/test_catalog_event_contract.py",
    "tests/test_catalog_consumer.py",
    "tests/test_catalog_indexer.py",
    "tests/test_rabbitmq_topology.py"
)
Invoke-Checked `
    -Name "Visual Search contract, idempotency and retry tests" `
    -WorkingDirectory (Join-Path $projectRoot "visual-search-service") `
    -Command { & python -m pytest -q @visualTests }

$chatbotPython = Join-Path $projectRoot "chatbot-service\.venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $chatbotPython)) {
    throw "Chatbot virtual environment not found at $chatbotPython"
}
Invoke-Checked `
    -Name "Product Search contract, idempotency and retry tests" `
    -WorkingDirectory (Join-Path $projectRoot "chatbot-service") `
    -Command { & $chatbotPython -m pytest -q tests/unit/test_product_search_indexing.py }

if ($IncludeRabbitMqIntegration) {
    $previousValue = $env:RUN_RABBITMQ_INTEGRATION
    try {
        $env:RUN_RABBITMQ_INTEGRATION = "1"
        Invoke-Checked `
            -Name "RabbitMQ publish, consume, ACK and retry integration" `
            -WorkingDirectory (Join-Path $projectRoot "visual-search-service") `
            -Command { & python -m pytest -q tests/test_rabbitmq_integration.py }
    }
    finally {
        $env:RUN_RABBITMQ_INTEGRATION = $previousValue
    }
}

Write-Host "`nMicroservice Phase 2 event-platform checks passed." -ForegroundColor Green
