$ErrorActionPreference = "Stop"

$script:ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$script:RuntimeEnvFile = Join-Path $script:ProjectRoot ".env.phase10.local"
$script:LocalLogDirectory = Join-Path $script:ProjectRoot "logs\local"
$script:LocalRunDirectory = Join-Path $script:ProjectRoot "tmp\local-runtime"

function Import-LocalEnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [switch]$Optional
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        if ($Optional) { return }
        throw "Missing environment file: $Path"
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith("#")) { continue }
        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) { continue }
        $name = $parts[0].Trim()
        $value = $parts[1].Trim().Trim('"').Trim("'")
        if ($name -match '^[A-Za-z_][A-Za-z0-9_]*$') {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Initialize-LocalServiceEnvironment {
    param([string]$ServiceEnvFile)

    if ($ServiceEnvFile) { Import-LocalEnvFile -Path $ServiceEnvFile -Optional }
    Import-LocalEnvFile -Path $script:RuntimeEnvFile

    # Docker DNS names are not available to processes running on the Windows host.
    $env:REDIS_HOST = "127.0.0.1"
    $env:REDIS_PORT = "6379"
    $env:REDIS_URL = "redis://127.0.0.1:6379"
    $env:RABBITMQ_ADDRESSES = "127.0.0.1:5672"

    $rabbitUser = [Uri]::EscapeDataString($env:RABBITMQ_DEFAULT_USER)
    $rabbitPassword = [Uri]::EscapeDataString($env:RABBITMQ_DEFAULT_PASS)
    $env:RABBITMQ_USERNAME = $env:RABBITMQ_DEFAULT_USER
    $env:RABBITMQ_PASSWORD = $env:RABBITMQ_DEFAULT_PASS
    $env:RABBITMQ_URL = "amqp://${rabbitUser}:${rabbitPassword}@127.0.0.1:5672/"

    $env:CATALOG_API_URL = "http://127.0.0.1:8092"
    $env:CATALOG_API_BASE_URL = "http://127.0.0.1:8092"
    $env:IDENTITY_API_BASE_URL = "http://127.0.0.1:8093"
    $env:SALES_API_BASE_URL = "http://127.0.0.1:8094"
    $env:SEARCH_API_BASE_URL = "http://127.0.0.1:8090"
    $env:BACKEND_API_BASE_URL = "http://127.0.0.1:8085"
    $env:INTERNAL_SERVICE_TOKEN = $env:SEARCH_SERVICE_INTERNAL_TOKEN
    $env:SEARCH_SERVICE_TOKEN = $env:SEARCH_SERVICE_INTERNAL_TOKEN
    $env:CATALOG_SERVICE_TOKEN = $env:CATALOG_INTERNAL_TOKEN

    # Runtime startup never applies schema migrations. Migrations remain an explicit maintenance action.
    $env:CATALOG_FLYWAY_ENABLED = "false"
    $env:IDENTITY_FLYWAY_ENABLED = "false"
    $env:SALES_FLYWAY_ENABLED = "false"

    New-Item -ItemType Directory -Force -Path $script:LocalLogDirectory | Out-Null
    New-Item -ItemType Directory -Force -Path $script:LocalRunDirectory | Out-Null
}

function Test-LocalProcessSlot {
    param([Parameter(Mandatory = $true)][string]$Name)
    $pidFile = Join-Path $script:LocalRunDirectory "$Name.pid"
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) { return $false }
    $savedPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
    if ($savedPid -and (Get-Process -Id $savedPid -ErrorAction SilentlyContinue)) { return $true }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    return $false
}

function Enter-LocalProcessSlot {
    param([Parameter(Mandatory = $true)][string]$Name)
    if (Test-LocalProcessSlot -Name $Name) { throw "$Name is already running." }
    $pidFile = Join-Path $script:LocalRunDirectory "$Name.pid"
    Set-Content -LiteralPath $pidFile -Value $PID -Encoding ASCII
    return $pidFile
}

function Exit-LocalProcessSlot {
    param([string]$PidFile)
    if ($PidFile) { Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue }
}

function Test-LocalTcpPort {
    param([Parameter(Mandatory = $true)][int]$Port)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        return $task.Wait(1000) -and $client.Connected
    }
    catch { return $false }
    finally { $client.Dispose() }
}

function Assert-LocalPortAvailable {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$ServiceName
    )
    if (Test-LocalTcpPort -Port $Port) {
        throw "$ServiceName cannot start because port $Port is already in use."
    }
}

function Get-LocalPython {
    param([string]$PreferredPath)
    if ($PreferredPath -and (Test-Path -LiteralPath $PreferredPath -PathType Leaf)) {
        return (Resolve-Path -LiteralPath $PreferredPath).Path
    }
    $python = Get-Command python -ErrorAction Stop
    return $python.Source
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Executable,
        [string[]]$Arguments = @()
    )

    $logFile = Join-Path $script:LocalLogDirectory "$Name.log"
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] Starting $Name" | Tee-Object -FilePath $logFile
    Push-Location $WorkingDirectory
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Uvicorn and Maven write normal INFO lines to stderr. Windows PowerShell 5
        # otherwise promotes those lines to terminating NativeCommandError records.
        $ErrorActionPreference = "Continue"
        & $Executable @Arguments 2>&1 |
            ForEach-Object { $_.ToString() } |
            Tee-Object -FilePath $logFile -Append
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
}
