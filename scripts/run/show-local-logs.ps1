param(
    [string]$Service = "all",
    [int]$Tail = 10
)

$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$logDirectory = Join-Path $projectRoot "logs\local"

if ($Service -eq "infra") {
    Set-Location $projectRoot
    & docker compose logs -f redis rabbitmq
    exit $LASTEXITCODE
}

if (-not (Test-Path -LiteralPath $logDirectory -PathType Container)) {
    Write-Host "No local logs have been created yet. Start the application first." -ForegroundColor Yellow
    exit 0
}

if ($Service -eq "errors") {
    $patterns = "ERROR|Exception|Traceback|FATAL|Caused by:"
    $matches = Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" -File |
        Select-String -Pattern $patterns -CaseSensitive:$false

    if (-not $matches) {
        Write-Host "No error patterns found." -ForegroundColor Green
        exit 0
    }

    $matches | ForEach-Object {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($_.Path)
        Write-Host "[${name}:$($_.LineNumber)] " -NoNewline -ForegroundColor Cyan
        Write-Host $_.Line -ForegroundColor Red
    }
    exit 0
}

if ($Service -ne "all") {
    $logFile = Join-Path $logDirectory "$Service.log"
    if (-not (Test-Path -LiteralPath $logFile -PathType Leaf)) {
        $available = (Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" -File |
            ForEach-Object BaseName) -join ", "
        throw "Log '$Service' does not exist. Available logs: $available"
    }

    Write-Host "Following $Service in real time. Press Ctrl+C to stop watching." -ForegroundColor Green
    Get-Content -LiteralPath $logFile -Tail 150 -Wait
    exit 0
}

function Write-PrefixedLogLine {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Line
    )

    Write-Host "[$Name] " -NoNewline -ForegroundColor Cyan
    if ($Line -match "ERROR|Exception|Traceback|FATAL|Caused by:") {
        Write-Host $Line -ForegroundColor Red
    }
    elseif ($Line -match "\bWARN(?:ING)?\b") {
        Write-Host $Line -ForegroundColor Yellow
    }
    else {
        Write-Host $Line
    }
}

function Get-LogEncoding {
    param([Parameter(Mandatory = $true)][string]$Path)

    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $bytes = New-Object byte[] 3
        $count = $stream.Read($bytes, 0, $bytes.Length)
        if ($count -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
            return [System.Text.Encoding]::Unicode
        }
        if ($count -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF) {
            return [System.Text.Encoding]::BigEndianUnicode
        }
        return [System.Text.UTF8Encoding]::new($false)
    }
    finally { $stream.Dispose() }
}

function Read-AppendedLogContent {
    param(
        [Parameter(Mandatory = $true)][System.IO.FileInfo]$File,
        [Parameter(Mandatory = $true)][hashtable]$Positions
    )

    $path = $File.FullName
    $position = if ($Positions.ContainsKey($path)) { [long]$Positions[$path] } else { [long]0 }
    if ($File.Length -lt $position) { $position = 0 }
    if ($File.Length -eq $position) { return }

    $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        [void]$stream.Seek($position, [System.IO.SeekOrigin]::Begin)
        $encoding = Get-LogEncoding -Path $path
        $reader = [System.IO.StreamReader]::new($stream, $encoding, $false, 4096, $true)
        try {
            $content = $reader.ReadToEnd()
            $Positions[$path] = $stream.Position
        }
        finally { $reader.Dispose() }
    }
    finally { $stream.Dispose() }

    if ($content) {
        $content -split "\r?\n" | Where-Object { $_ -ne "" } | ForEach-Object {
            Write-PrefixedLogLine -Name $File.BaseName -Line $_
        }
    }
}

$positions = @{}
$files = @(Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" -File | Sort-Object Name)
if (-not $files) {
    Write-Host "No local logs have been created yet. Start the application first." -ForegroundColor Yellow
    exit 0
}

Write-Host "Following all application logs in real time. Press Ctrl+C to stop watching." -ForegroundColor Green
Write-Host "Tip: use 'show-local-logs.cmd gateway' for one service or 'show-local-logs.cmd errors' for errors only." -ForegroundColor DarkGray
Write-Host ""

foreach ($file in $files) {
    Get-Content -LiteralPath $file.FullName -Tail $Tail | ForEach-Object {
        Write-PrefixedLogLine -Name $file.BaseName -Line $_
    }
    $positions[$file.FullName] = $file.Length
}

while ($true) {
    foreach ($file in @(Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" -File | Sort-Object Name)) {
        Read-AppendedLogContent -File $file -Positions $positions
    }
    Start-Sleep -Milliseconds 250
}
