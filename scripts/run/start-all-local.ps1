param(
    [switch]$NoFrontends,
    [switch]$VisibleTerminals,
    [switch]$FollowLogs
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common-local.ps1")

function Start-LocalService {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptName,
        [string[]]$Arguments = @()
    )
    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    $process = Start-ManagedLocalProcess -Name $Name -ScriptPath $scriptPath -Arguments $Arguments -Visible:$VisibleTerminals
    Write-Host "$Name launcher started (PID $($process.Id))." -ForegroundColor DarkGray
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

if (-not (Test-LocalTcpPort -Port 8092)) { Start-LocalService -Name "catalog-service" -ScriptName "start-catalog-service.ps1" }
if (-not (Test-LocalTcpPort -Port 8093)) { Start-LocalService -Name "identity-service" -ScriptName "start-identity-service.ps1" }
Wait-LocalPort -Port 8092 -Name "catalog-service"
Wait-LocalPort -Port 8093 -Name "identity-service"

if (-not (Test-LocalTcpPort -Port 8094)) { Start-LocalService -Name "sales-service" -ScriptName "start-sales-service.ps1" }
if (-not (Test-LocalTcpPort -Port 8090)) { Start-LocalService -Name "search-service" -ScriptName "start-search-service.ps1" }
if (-not (Test-LocalTcpPort -Port 8091)) { Start-LocalService -Name "recommendation-service" -ScriptName "start-recommendation-service.ps1" }
Wait-LocalPort -Port 8094 -Name "sales-service"
Wait-LocalPort -Port 8090 -Name "search-service"
Wait-LocalPort -Port 8091 -Name "recommendation-service"

if (-not (Test-LocalProcessSlot -Name "search-worker")) {
    Start-LocalService -Name "search-worker" -ScriptName "start-search-service.ps1" -Arguments @("-Mode", "worker")
}
if (-not (Test-LocalProcessSlot -Name "recommendation-worker")) {
    Start-LocalService -Name "recommendation-worker" -ScriptName "start-recommendation-service.ps1" -Arguments @("-Mode", "worker")
}

if (-not (Test-LocalTcpPort -Port 8002)) { Start-LocalService -Name "chatbot-service" -ScriptName "start-chatbot-service.ps1" }
Wait-LocalPort -Port 8002 -Name "chatbot-service"

if (-not (Test-LocalTcpPort -Port 8085)) { Start-LocalService -Name "gateway" -ScriptName "start-gateway.ps1" }
Wait-LocalPort -Port 8085 -Name "gateway"

if (-not $NoFrontends) {
    if (-not (Test-LocalTcpPort -Port 3000)) { Start-LocalService -Name "storefront" -ScriptName "start-storefront.ps1" }
    if (-not (Test-LocalTcpPort -Port 3001)) { Start-LocalService -Name "admin" -ScriptName "start-admin.ps1" }
    Wait-LocalPort -Port 3000 -Name "storefront" -TimeoutSeconds 180
    Wait-LocalPort -Port 3001 -Name "admin" -TimeoutSeconds 180
}

Write-Host ""
Write-Host "Local microservice runtime started successfully." -ForegroundColor Green
Write-Host "Gateway:    http://localhost:8085" -ForegroundColor Cyan
Write-Host "Storefront: http://localhost:3000" -ForegroundColor Cyan
Write-Host "Admin:      http://localhost:3001" -ForegroundColor Cyan
Write-Host "Logs:       .\logs\local" -ForegroundColor Cyan
Write-Host "Status:     .\scripts\run\status-local.cmd" -ForegroundColor Cyan
Write-Host "Stop:       .\scripts\run\stop-all-local.cmd" -ForegroundColor Cyan

if ($FollowLogs) {
    & (Join-Path $PSScriptRoot "show-local-logs.ps1") -Service "all"
}
