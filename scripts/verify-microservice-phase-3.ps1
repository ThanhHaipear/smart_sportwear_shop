param(
    [switch]$SkipBackendTests,
    [switch]$ImplementationOnly,
    [string]$AcceptanceReport = "evidence\image-search-v3\test-v3-final\metrics.json",
    [string]$AcceptanceConfig = "evidence\image-search-v3\test-v3-final\benchmark-config.json"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$searchRoot = Join-Path $projectRoot "services\search-service"

function Invoke-Checked([string]$Name, [string]$WorkingDirectory, [scriptblock]$Command) {
    Write-Host "==> $Name"
    Push-Location $WorkingDirectory
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}

Invoke-Checked "Search service unit and contract tests" $searchRoot { python -m pytest -q }

$definitions = Get-Content -Raw (Join-Path $projectRoot "rabbitmq\definitions.json") | ConvertFrom-Json
if (-not ($definitions.queues.name -contains "search.indexing.dlq")) {
    throw "Unified search DLQ is missing"
}

$forbidden = Get-ChildItem (Join-Path $searchRoot "app") -Recurse -Filter *.py |
    Select-String -Pattern '(?i)\b(from|join|update|insert into|delete from)\s+(public\.)?(products|product_images|product_variants|categories|brands)\b'
if ($forbidden) {
    $forbidden | Format-Table Path, LineNumber, Line
    throw "search-service contains direct catalog table SQL"
}

if (-not $SkipBackendTests) {
    Invoke-Checked "Catalog snapshot and image-client contracts" (Join-Path $projectRoot "backend") {
        & .\mvnw.cmd clean '-Dtest=VisualSearchClientTest,InternalCatalogIndexControllerTest' test
    }
}

Write-Host "Phase 3 implementation gate passed."

if (-not $ImplementationOnly) {
    $reportPath = Join-Path $projectRoot $AcceptanceReport
    $configPath = Join-Path $projectRoot $AcceptanceConfig
    if (-not (Test-Path $reportPath) -or -not (Test-Path $configPath)) {
        throw "Phase 3 acceptance evidence is missing. Freeze and independently review TEST v3 first."
    }
    $report = Get-Content -Raw $reportPath | ConvertFrom-Json
    $config = Get-Content -Raw $configPath | ConvertFrom-Json
    if ($config.dataset.split -ne 'TEST' -or $config.dataset.status -ne 'FROZEN') {
        throw "Acceptance dataset must be a frozen TEST split"
    }
    if (-not $report.acceptance.eligible -or -not $report.acceptance.passed) {
        throw "Phase 3 retrieval quality gate failed"
    }
    if ($report.positive.recall.at5 -lt 0.80 -or
        $report.positive.categoryAccuracy.value -lt 0.90 -or
        $report.requestFailureRate.value -ne 0 -or
        $report.latencyMs.p95 -gt 3000) {
        throw "Phase 3 locked acceptance thresholds are not satisfied"
    }
    Write-Host "Phase 3 acceptance gate passed."
}
