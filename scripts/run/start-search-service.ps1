param(
    [ValidateSet("api", "worker")]
    [string]$Mode = "api"
)

. (Join-Path $PSScriptRoot "common-local.ps1")
$service = Join-Path $script:ProjectRoot "services\search-service"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $service ".env")
$env:DATABASE_URL = $env:SEARCH_DATABASE_URL
$env:SEARCH_ENABLED = "true"
$python = Get-LocalPython -PreferredPath (Join-Path $service ".venv\Scripts\python.exe")

if ($Mode -eq "worker") {
    $Host.UI.RawUI.WindowTitle = "Smart Sportswear - Search Worker"
    $pidFile = Enter-LocalProcessSlot -Name "search-worker"
    try { $code = Invoke-LoggedCommand -Name "search-worker" -WorkingDirectory $service -Executable $python -Arguments @("-m", "app.worker") }
    finally { Exit-LocalProcessSlot -PidFile $pidFile }
}
else {
    $Host.UI.RawUI.WindowTitle = "Smart Sportswear - Search :8090"
    Assert-LocalPortAvailable -Port 8090 -ServiceName "Search service"
    $code = Invoke-LoggedCommand -Name "search-service" -WorkingDirectory $service -Executable $python -Arguments @("run.py")
}
exit $code
