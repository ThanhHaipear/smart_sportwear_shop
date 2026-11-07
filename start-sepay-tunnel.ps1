$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$envFile = Join-Path $projectRoot "backend\.env"
$scriptFile = Join-Path $projectRoot "scripts\sepay-ngrok-tunnel.js"
$sdkEntry = Join-Path $projectRoot ".tools\ngrok-sdk\node_modules\@ngrok\ngrok\index.js"

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw "Missing $envFile" }
if (-not (Test-Path -LiteralPath $scriptFile -PathType Leaf)) { throw "Missing $scriptFile" }
if (-not (Test-Path -LiteralPath $sdkEntry -PathType Leaf)) {
    throw "Ngrok SDK is missing. Run: npm install --prefix .tools\ngrok-sdk @ngrok/ngrok@1.7.0"
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line -split "=", 2
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
        }
    }
}

if ([string]::IsNullOrWhiteSpace($env:NGROK_AUTHTOKEN)) {
    throw "NGROK_AUTHTOKEN is empty in backend\.env. Copy it from https://dashboard.ngrok.com/get-started/your-authtoken"
}

function Test-TcpPort([int]$TargetPort) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync("127.0.0.1", $TargetPort)
        return $task.Wait(1000) -and $client.Connected
    }
    catch { return $false }
    finally { $client.Dispose() }
}

if (-not (Test-TcpPort 8082)) {
    throw "Backend is not running on port 8082. Start it before the SePay tunnel."
}
if (Test-TcpPort 18083) {
    throw "Port 18083 is already in use. Stop the existing SePay tunnel before starting another one."
}

& node.exe $scriptFile
exit $LASTEXITCODE
