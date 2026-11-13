param(
    [string]$Service = "active",
    [int]$Tail = 10
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common-local.ps1")
$projectRoot = $script:ProjectRoot
$logDirectory = $script:LocalLogDirectory

function Get-ActiveLocalServiceNames {
    $servicePorts = [ordered]@{
        storefront = 3000
        admin = 3001
        gateway = 8085
        "chatbot-service" = 8002
        "search-service" = 8090
        "recommendation-service" = 8091
        "catalog-service" = 8092
        "identity-service" = 8093
        "sales-service" = 8094
    }

    $active = @()
    foreach ($entry in $servicePorts.GetEnumerator()) {
        if (Test-LocalTcpPort -Port $entry.Value) { $active += [string]$entry.Key }
    }
    foreach ($workerName in @("search-worker", "recommendation-worker")) {
        if ((Test-LocalProcessSlot -Name $workerName) -or (Test-ManagedLocalProcess -Name $workerName)) {
            $active += $workerName
        }
    }
    return @($active)
}

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

if ($Service -ne "all" -and $Service -ne "active" -and -not $Service.Contains(",")) {
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

$selectedNames = if ($Service -eq "all") {
    $null
}
elseif ($Service -eq "active") {
    @(Get-ActiveLocalServiceNames)
}
else {
    @($Service.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
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
$allFiles = @(Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" -File | Sort-Object Name)
$files = if ($null -eq $selectedNames) {
    $allFiles
}
else {
    @($allFiles | Where-Object { $selectedNames -contains $_.BaseName })
}
if (-not $files) {
    Write-Host "No matching local logs have been created yet. Start the selected services first." -ForegroundColor Yellow
    exit 0
}

$scopeLabel = if ($Service -eq "all") { "all application services" }
    elseif ($Service -eq "active") { "currently running services" }
    else { $Service }
Write-Host "Following $scopeLabel logs in real time. Press Ctrl+C to stop watching." -ForegroundColor Green
if ($Service -eq "active") {
    Write-Host ("Detected: " + ($selectedNames -join ", ")) -ForegroundColor DarkGray
}
Write-Host "Use 'all' for every log file or a comma-separated service list for a custom selection." -ForegroundColor DarkGray
Write-Host ""

foreach ($file in $files) {
    Get-Content -LiteralPath $file.FullName -Tail $Tail | ForEach-Object {
        Write-PrefixedLogLine -Name $file.BaseName -Line $_
    }
    $positions[$file.FullName] = $file.Length
}

while ($true) {
    $currentFiles = @(Get-ChildItem -LiteralPath $logDirectory -Filter "*.log" -File | Sort-Object Name)
    if ($null -ne $selectedNames) {
        $currentFiles = @($currentFiles | Where-Object { $selectedNames -contains $_.BaseName })
    }
    foreach ($file in $currentFiles) {
        Read-AppendedLogContent -File $file -Positions $positions
    }
    Start-Sleep -Milliseconds 250
}
