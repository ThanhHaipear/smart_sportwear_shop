$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$nodeDir = Join-Path $projectRoot ".tools\node-v24.14.1-win-x64"
$nodeExe = Join-Path $nodeDir "node.exe"
$appDir = Join-Path $projectRoot "frontend\admin"

if (-not (Test-Path -LiteralPath $nodeExe -PathType Leaf)) { throw "Project-local Node.js 24 is missing at $nodeDir" }
function Test-TcpPort([int]$TargetPort) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try { $task = $client.ConnectAsync("127.0.0.1", $TargetPort); return $task.Wait(1000) -and $client.Connected }
    catch { return $false }
    finally { $client.Dispose() }
}
if (Test-TcpPort 3001) {
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:3001" -UseBasicParsing -TimeoutSec 3
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
            Write-Host "Admin is already running at http://localhost:3001." -ForegroundColor Yellow
            Write-Host "Stop it with Ctrl+C in the terminal where it is running before starting another instance." -ForegroundColor Cyan
            exit 0
        }
    }
    catch {
        # The port belongs to another process, or the admin app is not responding.
    }

    throw "Port 3001 is being used by another process. Stop that process before starting admin."
}
$env:Path = "$nodeDir;$env:Path"
Set-Location $appDir
if (-not (Test-Path "node_modules\next\package.json")) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
Write-Host "Admin: http://localhost:3001" -ForegroundColor Green
& npm.cmd run dev
exit $LASTEXITCODE
