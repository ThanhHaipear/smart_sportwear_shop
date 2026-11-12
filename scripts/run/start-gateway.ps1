. (Join-Path $PSScriptRoot "common-local.ps1")
$service = Join-Path $script:ProjectRoot "gateway"
Initialize-LocalServiceEnvironment
$env:IDENTITY_UPSTREAM = "http://127.0.0.1:8093"
$env:CATALOG_UPSTREAM = "http://127.0.0.1:8092"
$env:SALES_UPSTREAM = "http://127.0.0.1:8094"
$env:CHATBOT_UPSTREAM = "http://127.0.0.1:8002"
$env:RECOMMENDATION_UPSTREAM = "http://127.0.0.1:8091"
$env:OTEL_EXPORTER_OTLP_ENDPOINT = ""

$python = Get-LocalPython -PreferredPath (Join-Path $service ".venv\Scripts\python.exe")
$Host.UI.RawUI.WindowTitle = "Smart Sportswear - Gateway :8085"
Assert-LocalPortAvailable -Port 8085 -ServiceName "Gateway"
$code = Invoke-LoggedCommand -Name "gateway" -WorkingDirectory $service -Executable $python -Arguments @("-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8085")
exit $code
