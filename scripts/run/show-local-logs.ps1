param(
    [string]$Service = "errors"
)

$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$logDirectory = Join-Path $projectRoot "logs\local"

if ($Service -eq "infra") {
    Set-Location $projectRoot
    & docker compose logs -f redis rabbitmq
    exit $LASTEXITCODE
}

if ($Service -eq "errors") {
    if (-not (Test-Path -LiteralPath $logDirectory)) {
        Write-Host "No local logs have been created yet."
        exit 0
    }
    & rg -n -i "ERROR|Exception|Traceback|FATAL|Caused by:" $logDirectory
    if ($LASTEXITCODE -eq 1) { Write-Host "No error patterns found." -ForegroundColor Green; exit 0 }
    exit $LASTEXITCODE
}

$logFile = Join-Path $logDirectory "$Service.log"
if (-not (Test-Path -LiteralPath $logFile -PathType Leaf)) {
    $available = if (Test-Path -LiteralPath $logDirectory) {
        (Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" | ForEach-Object BaseName) -join ", "
    } else { "none" }
    throw "Log '$Service' does not exist. Available logs: $available"
}

Get-Content -LiteralPath $logFile -Tail 150 -Wait
