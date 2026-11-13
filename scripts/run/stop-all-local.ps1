param(
    [switch]$Infrastructure
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common-local.ps1")

function Stop-ManagedProcessTree {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$ProcessId
    )

    $process = Get-ManagedLocalProcess -Name $Name
    if (-not $process -or $process.Id -ne $ProcessId) { return }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & taskkill.exe /PID $ProcessId /T /F 2>&1 | Out-Null
        $taskkillExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousErrorActionPreference }

    if ($taskkillExitCode -ne 0) {
        # Some restricted shells block taskkill /T. Stopping the verified launcher
        # is still safe; status checks will expose any child that remains on a port.
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
        Write-Warning "Windows could not stop the complete process tree for $Name. Check status-local.cmd for remaining external processes."
    }
}

$pidFiles = @(Get-ChildItem -LiteralPath $script:LocalRunDirectory -Filter "*.managed.pid" -File -ErrorAction SilentlyContinue)
if (-not $pidFiles) {
    Write-Host "No managed local application processes were found." -ForegroundColor Yellow
}

foreach ($pidFile in $pidFiles) {
    $name = $pidFile.BaseName -replace '\.managed$', ''
    $process = Get-ManagedLocalProcess -Name $name
    if ($process) {
        Write-Host "Stopping $name (PID $($process.Id))..." -ForegroundColor Cyan
        Stop-ManagedProcessTree -Name $name -ProcessId $process.Id
    }
    Remove-Item -LiteralPath $pidFile.FullName -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $script:LocalRunDirectory "$name.pid") -Force -ErrorAction SilentlyContinue
}

if ($Infrastructure) {
    Push-Location $script:ProjectRoot
    try {
        & docker compose stop redis rabbitmq
        if ($LASTEXITCODE -ne 0) { throw "Cannot stop Redis and RabbitMQ." }
    }
    finally { Pop-Location }
}

Write-Host "Managed local runtime stopped." -ForegroundColor Green
if (-not $Infrastructure) {
    Write-Host "Redis and RabbitMQ are still running. Use -Infrastructure to stop them too." -ForegroundColor DarkGray
}
