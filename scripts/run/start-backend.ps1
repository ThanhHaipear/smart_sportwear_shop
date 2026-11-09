$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$envFile = Join-Path $projectRoot "backend\.env"
$javaHome = Join-Path $projectRoot ".tools\jdk-21"
$javaExe = Join-Path $javaHome "bin\java.exe"

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw "Missing $envFile" }
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
    throw "Project-local Java 21 is missing at $javaHome. Run .\scripts\setup\setup-local-tools.cmd first."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line -split "=", 2
        if ($parts.Count -eq 2) { [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process") }
    }
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
$env:REDIS_HOST = "localhost"
$env:REDIS_PORT = "6379"
$env:SERVER_PORT = "8082"
$port = 8082

function Test-TcpPort([int]$TargetPort) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try { $task = $client.ConnectAsync("127.0.0.1", $TargetPort); return $task.Wait(1000) -and $client.Connected }
    catch { return $false }
    finally { $client.Dispose() }
}

if (Test-TcpPort $port) {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 3
        if ($health.status -eq "UP") {
            Write-Host "Backend is already running at http://localhost:$port." -ForegroundColor Yellow
            Write-Host "Stop it with Ctrl+C in the terminal where it is running before starting another instance." -ForegroundColor Cyan
            exit 0
        }
    }
    catch {
        # The port belongs to another process, or the backend is not healthy.
    }

    throw "Port $port is being used by another process. Stop that process or change SERVER_PORT before starting the backend."
}
if (-not (Test-TcpPort 6379)) {
    throw "Redis is not running. Run 'docker compose up -d redis' first."
}

Write-Host "Java: $(& $javaExe --version | Select-Object -First 1)" -ForegroundColor Cyan
Write-Host "Backend: http://localhost:$port (Supabase: $env:DB_HOST)" -ForegroundColor Green
Set-Location (Join-Path $projectRoot "backend")
& .\mvnw.cmd spring-boot:run
exit $LASTEXITCODE
