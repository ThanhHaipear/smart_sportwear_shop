. (Join-Path $PSScriptRoot "common-local.ps1")
$service = Join-Path $script:ProjectRoot "chatbot-service"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $service ".env")
$env:PYTHONUTF8 = "1"
$env:CHATBOT_HOST = "0.0.0.0"
$env:CHATBOT_PORT = "8002"
$env:DB_WRITE_URL = $env:CHATBOT_DATABASE_URL

$python = Get-LocalPython -PreferredPath (Join-Path $service ".venv\Scripts\python.exe")
$Host.UI.RawUI.WindowTitle = "Smart Sportswear - Chatbot :8002"
Assert-LocalPortAvailable -Port 8002 -ServiceName "Chatbot service"
$code = Invoke-LoggedCommand -Name "chatbot-service" -WorkingDirectory $service -Executable $python -Arguments @("run.py")
exit $code
