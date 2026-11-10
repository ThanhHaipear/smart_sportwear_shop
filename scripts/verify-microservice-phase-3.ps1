param([switch]$SkipBackendTests)

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
        & .\mvnw.cmd '-Dtest=VisualSearchClientTest,InternalCatalogIndexControllerTest' test
    }
}

Write-Host "Phase 3 implementation gate passed. Retrieval-quality acceptance remains governed by the locked TEST reports."
