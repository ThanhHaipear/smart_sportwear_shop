param(
    [switch]$NoFrontends
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common-local.ps1")

function Start-LocalTerminal {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptName,
        [string[]]$Arguments = @()
    )
    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    $processArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $scriptPath) + $Arguments
    Start-Process -FilePath "powershell.exe" -ArgumentList $processArgs -WorkingDirectory $script:ProjectRoot
}

function Wait-LocalPort {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$Name,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalTcpPort -Port $Port) {
            Write-Host "$Name is ready on port $Port." -ForegroundColor Green
            return
        }
        Start-Sleep -Milliseconds 750
    }
    throw "$Name did not open port $Port within $TimeoutSeconds seconds. Check logs/local/$Name.log."
}

& (Join-Path $PSScriptRoot "start-infrastructure.ps1")

if (-not (Test-LocalTcpPort -Port 8092)) { Start-LocalTerminal "start-catalog-service.ps1" }
if (-not (Test-LocalTcpPort -Port 8093)) { Start-LocalTerminal "start-identity-service.ps1" }
Wait-LocalPort -Port 8092 -Name "catalog-service"
Wait-LocalPort -Port 8093 -Name "identity-service"

if (-not (Test-LocalTcpPort -Port 8094)) { Start-LocalTerminal "start-sales-service.ps1" }
if (-not (Test-LocalTcpPort -Port 8090)) { Start-LocalTerminal "start-search-service.ps1" }
if (-not (Test-LocalTcpPort -Port 8091)) { Start-LocalTerminal "start-recommendation-service.ps1" }
Wait-LocalPort -Port 8094 -Name "sales-service"
Wait-LocalPort -Port 8090 -Name "search-service"
Wait-LocalPort -Port 8091 -Name "recommendation-service"

if (-not (Test-LocalProcessSlot -Name "search-worker")) {
    Start-LocalTerminal "start-search-service.ps1" @("-Mode", "worker")
}
if (-not (Test-LocalProcessSlot -Name "recommendation-worker")) {
    Start-LocalTerminal "start-recommendation-service.ps1" @("-Mode", "worker")
}

if (-not (Test-LocalTcpPort -Port 8002)) { Start-LocalTerminal "start-chatbot-service.ps1" }
Wait-LocalPort -Port 8002 -Name "chatbot-service"

if (-not (Test-LocalTcpPort -Port 8085)) { Start-LocalTerminal "start-gateway.ps1" }
Wait-LocalPort -Port 8085 -Name "gateway"

if (-not $NoFrontends) {
    if (-not (Test-LocalTcpPort -Port 3000)) { Start-LocalTerminal "start-storefront.ps1" }
    if (-not (Test-LocalTcpPort -Port 3001)) { Start-LocalTerminal "start-admin.ps1" }
}

Write-Host ""
Write-Host "Local microservice runtime started successfully." -ForegroundColor Green
Write-Host "Gateway:    http://localhost:8085" -ForegroundColor Cyan
Write-Host "Storefront: http://localhost:3000" -ForegroundColor Cyan
Write-Host "Admin:      http://localhost:3001" -ForegroundColor Cyan
Write-Host "Logs:       .\logs\local" -ForegroundColor Cyan
