param(
    [ValidateSet("api", "worker")]
    [string]$Mode = "api"
)

. (Join-Path $PSScriptRoot "common-local.ps1")
$service = Join-Path $script:ProjectRoot "recommendation-service"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $service ".env")
$env:RECOMMENDATION_ENABLED = "true"
$python = Get-LocalPython -PreferredPath (Join-Path $service ".venv\Scripts\python.exe")

if ($Mode -eq "worker") {
    $Host.UI.RawUI.WindowTitle = "Smart Sportswear - Recommendation Worker"
    $pidFile = Enter-LocalProcessSlot -Name "recommendation-worker"
    try { $code = Invoke-LoggedCommand -Name "recommendation-worker" -WorkingDirectory $service -Executable $python -Arguments @("-m", "app.worker") }
    finally { Exit-LocalProcessSlot -PidFile $pidFile }
}
else {
    $Host.UI.RawUI.WindowTitle = "Smart Sportswear - Recommendation :8091"
    Assert-LocalPortAvailable -Port 8091 -ServiceName "Recommendation service"
    $code = Invoke-LoggedCommand -Name "recommendation-service" -WorkingDirectory $service -Executable $python -Arguments @("-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091")
}
exit $code
