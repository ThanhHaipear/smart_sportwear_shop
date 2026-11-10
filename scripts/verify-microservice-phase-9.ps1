param([switch]$SkipServiceTests)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$gateway = Get-Content -Raw (Join-Path $root "gateway\app\main.py")
foreach ($needle in @('traceparent','X-Correlation-ID','Rate limit exceeded','Upstream unavailable','/internal/','x-internal-service-token','StreamingResponse','/v1/traces')) {
    if ($gateway -notmatch [regex]::Escape($needle)) { throw "missing gateway invariant: $needle" }
}

$compose = Get-Content -Raw (Join-Path $root "docker-compose.yml")
foreach ($service in @('gateway','prometheus','tempo','otel-collector','grafana')) {
    if ($compose -notmatch "(?m)^  $([regex]::Escape($service)):") { throw "missing Phase 9 compose service: $service" }
}
foreach ($port in @('8090:8090','8091:8091','8092:8092','8093:8093','8094:8094','8002:8002')) {
    if ($compose -match [regex]::Escape($port)) { throw "application service port remains public: $port" }
}

& docker compose --profile gateway --profile observability --profile identity --profile catalog --profile sales --profile search --profile chatbot --profile recommendation config --quiet
if ($LASTEXITCODE -ne 0) { throw "Docker Compose Phase 9 config gate failed" }

$python = Join-Path $root "chatbot-service\.venv\Scripts\python.exe"
if (-not (Test-Path $python)) { $python = "python" }
$previousPythonPath = $env:PYTHONPATH
$env:PYTHONPATH = Join-Path $root "gateway"
try {
    & $python -m pytest (Join-Path $root "gateway\tests") -q
    if ($LASTEXITCODE -ne 0) { throw "gateway contract/resilience tests failed" }
} finally { $env:PYTHONPATH = $previousPythonPath }

$prometheus = Get-Content -Raw (Join-Path $root "infra\observability\prometheus\prometheus.yml")
$alerts = Get-Content -Raw (Join-Path $root "infra\observability\prometheus\alerts.yml")
$dashboard = Get-Content -Raw (Join-Path $root "infra\observability\grafana\dashboards\phase9-overview.json")
foreach ($job in @('gateway','identity-service','catalog-service','sales-service','search-service','chatbot-service','recommendation-service')) {
    if ($prometheus -notmatch [regex]::Escape("job_name: $job")) { throw "missing Prometheus scrape job: $job" }
}
if ($alerts -notmatch 'ServiceDown' -or $alerts -notmatch 'GatewayHigh5xxRate') { throw "Phase 9 SLO alerts are incomplete" }
$null = $dashboard | ConvertFrom-Json

if (-not $SkipServiceTests) {
    foreach ($service in @('identity-service','catalog-service','sales-service')) {
        & (Join-Path $root "backend\mvnw.cmd") -q -f (Join-Path $root "$service\pom.xml") test
        if ($LASTEXITCODE -ne 0) { throw "$service regression gate failed" }
    }
}

Write-Host "Phase 9 gateway, observability and resilience gate passed."

