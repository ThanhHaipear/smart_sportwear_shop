$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
Set-Location $projectRoot

$applicationServices = @(
    "gateway", "catalog-service", "identity-service", "sales-service",
    "search-service", "search-worker", "chatbot-service",
    "recommendation-service", "recommendation-worker"
)
$profiles = @(
    "--profile", "gateway", "--profile", "catalog", "--profile", "identity",
    "--profile", "sales", "--profile", "search", "--profile", "chatbot",
    "--profile", "recommendation"
)

# Ensure application containers from a previous full-Compose run do not conflict with host terminals.
& docker compose @profiles stop @applicationServices
if ($LASTEXITCODE -ne 0) { throw "Cannot stop application containers." }

& docker compose up -d redis rabbitmq
if ($LASTEXITCODE -ne 0) { throw "Cannot start Redis and RabbitMQ." }

Write-Host "Docker infrastructure is ready: Redis :6379, RabbitMQ :5672/:15672." -ForegroundColor Green
Write-Host "Application services can now run as managed background processes." -ForegroundColor Cyan
