param(
    [string]$OutputDirectory = "",
    [string]$PostgresImage = "pgvector/pgvector:pg17"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
# Historical public/ai_retrieval backup source retained after monolith retirement.
# Current microservice runtime configuration is .env.phase10.local.
$envFile = Join-Path $projectRoot ".env.legacy-backend.local"
$backupRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $projectRoot "backups\microservice-phase-0"
} else {
    [System.IO.Path]::GetFullPath($OutputDirectory)
}

function Invoke-Docker {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code $LASTEXITCODE"
    }
}

function Read-EnvFile([string]$Path) {
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line -split "=", 2
            $values[$parts[0].Trim()] = $parts[1].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Encrypt-File([string]$Source, [string]$Destination, [byte[]]$Key) {
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key = $Key
    $aes.GenerateIV()
    $inputStream = [System.IO.File]::OpenRead($Source)
    $outputStream = [System.IO.File]::Create($Destination)
    try {
        $outputStream.Write($aes.IV, 0, $aes.IV.Length)
        $cryptoStream = [System.Security.Cryptography.CryptoStream]::new(
            $outputStream,
            $aes.CreateEncryptor(),
            [System.Security.Cryptography.CryptoStreamMode]::Write)
        try { $inputStream.CopyTo($cryptoStream) } finally { $cryptoStream.Dispose() }
    } finally {
        $inputStream.Dispose()
        $outputStream.Dispose()
        $aes.Dispose()
    }
}

function Decrypt-File([string]$Source, [string]$Destination, [byte[]]$Key) {
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key = $Key
    $inputStream = [System.IO.File]::OpenRead($Source)
    $iv = [byte[]]::new(16)
    if ($inputStream.Read($iv, 0, $iv.Length) -ne $iv.Length) {
        throw "Encrypted backup is truncated"
    }
    $aes.IV = $iv
    $outputStream = [System.IO.File]::Create($Destination)
    try {
        $cryptoStream = [System.Security.Cryptography.CryptoStream]::new(
            $inputStream,
            $aes.CreateDecryptor(),
            [System.Security.Cryptography.CryptoStreamMode]::Read)
        try { $cryptoStream.CopyTo($outputStream) } finally { $cryptoStream.Dispose() }
    } finally {
        $inputStream.Dispose()
        $outputStream.Dispose()
        $aes.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw "Missing $envFile" }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker is required" }

$database = Read-EnvFile $envFile
$required = @("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD")
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace($database[$name])) { throw "Missing $name in $envFile" }
}
$databaseAddress = [System.Net.Dns]::GetHostAddresses($database.DB_HOST) |
    Where-Object AddressFamily -eq ([System.Net.Sockets.AddressFamily]::InterNetwork) |
    Select-Object -First 1
if ($null -eq $databaseAddress) { throw "Could not resolve an IPv4 address for the Supabase database host" }

New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$baseName = "supabase-v13-$timestamp"
$plainDump = Join-Path $backupRoot "$baseName.dump"
$encryptedDump = Join-Path $backupRoot "$baseName.dump.aes"
$protectedKeyFile = Join-Path $backupRoot "$baseName.key.dpapi"
$tocFile = Join-Path $backupRoot "$baseName.toc.txt"
$evidenceFile = Join-Path $backupRoot "$baseName.restore-evidence.json"
$temporaryDump = Join-Path ([System.IO.Path]::GetTempPath()) "$baseName-restore.dump"
$containerName = "ssw-v13-restore-$timestamp"
$restorePassword = [Guid]::NewGuid().ToString("N")
$containerStarted = $false
$completed = $false

try {
    Write-Host "Creating read-only logical backup of public and ai_retrieval..." -ForegroundColor Cyan
    Invoke-Docker run --rm `
        --add-host "$($database.DB_HOST):$($databaseAddress.IPAddressToString)" `
        --env "PGPASSWORD=$($database.DB_PASSWORD)" `
        --env "PGSSLMODE=require" `
        --volume "${backupRoot}:/backup" `
        $PostgresImage `
        pg_dump `
        --host $database.DB_HOST `
        --port $database.DB_PORT `
        --username $database.DB_USERNAME `
        --dbname $database.DB_NAME `
        --format custom `
        --no-owner `
        --no-privileges `
        --schema public `
        --schema ai_retrieval `
        --file "/backup/$baseName.dump"

    Invoke-Docker run --rm `
        --volume "${backupRoot}:/backup" `
        $PostgresImage `
        sh -c "pg_restore --list '/backup/$baseName.dump' > '/backup/$baseName.toc.txt'"

    $plainHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $plainDump).Hash
    $key = [byte[]]::new(32)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($key) } finally { $random.Dispose() }
    $protectedKey = [System.Security.Cryptography.ProtectedData]::Protect(
        $key,
        $null,
        [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    [System.IO.File]::WriteAllBytes($protectedKeyFile, $protectedKey)
    Encrypt-File $plainDump $encryptedDump $key
    $encryptedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $encryptedDump).Hash

    Decrypt-File $encryptedDump $temporaryDump $key
    $decryptedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryDump).Hash
    if ($decryptedHash -ne $plainHash) { throw "Decrypted backup checksum does not match the source dump" }

    Write-Host "Restoring into a disposable pgvector container..." -ForegroundColor Cyan
    Invoke-Docker run --detach `
        --name $containerName `
        --env "POSTGRES_PASSWORD=$restorePassword" `
        $PostgresImage
    $containerStarted = $true

    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker exec --env "PGPASSWORD=$restorePassword" $containerName `
            pg_isready --username postgres --dbname postgres *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "Restore container did not become ready" }

    Invoke-Docker exec --env "PGPASSWORD=$restorePassword" $containerName `
        psql --username postgres --dbname postgres --set ON_ERROR_STOP=1 `
        --command "create database smart_sportswear_restore;"
    Invoke-Docker exec --env "PGPASSWORD=$restorePassword" $containerName `
        psql --username postgres --dbname smart_sportswear_restore --set ON_ERROR_STOP=1 `
        --command "create schema if not exists extensions; create extension if not exists vector with schema extensions;"
    Invoke-Docker cp $temporaryDump "${containerName}:/tmp/backup.dump"
    Invoke-Docker exec --env "PGPASSWORD=$restorePassword" $containerName `
        pg_restore --username postgres --dbname smart_sportswear_restore `
        --clean --if-exists --no-owner --no-privileges --exit-on-error /tmp/backup.dump

    $validationSql = @"
select
  (select max(version::integer) from flyway_schema_history where success) as flyway_version,
  (select count(*) from products) as products,
  (select count(*) from products where status='ACTIVE') as active_products,
  (select count(*) from product_images) as images,
  (select count(*) from ai_retrieval.product_image_embeddings where status='READY') as ready_embeddings,
  (select count(*) from integration_outbox where status='PUBLISHED') as published_events;
"@
    $validation = & docker exec --env "PGPASSWORD=$restorePassword" $containerName `
        psql --username postgres --dbname smart_sportswear_restore `
        --tuples-only --no-align --field-separator "|" --command $validationSql
    if ($LASTEXITCODE -ne 0) { throw "Restore validation query failed" }
    $parts = ($validation | Select-Object -Last 1).Trim() -split "\|"
    if ($parts.Count -ne 6 -or $parts[0] -ne "13") { throw "Unexpected restore validation result: $validation" }

    $evidence = [ordered]@{
        createdAt = (Get-Date).ToString("o")
        source = "Supabase PostgreSQL"
        schemas = @("public", "ai_retrieval")
        encryptedBackup = [System.IO.Path]::GetFileName($encryptedDump)
        protectedKey = [System.IO.Path]::GetFileName($protectedKeyFile)
        keyProtection = "Windows DPAPI CurrentUser"
        plaintextSha256 = $plainHash
        encryptedSha256 = $encryptedHash
        toc = [System.IO.Path]::GetFileName($tocFile)
        restore = [ordered]@{
            image = $PostgresImage
            flywayVersion = [int]$parts[0]
            products = [int]$parts[1]
            activeProducts = [int]$parts[2]
            images = [int]$parts[3]
            readyImageEmbeddings = [int]$parts[4]
            publishedOutboxEvents = [int]$parts[5]
            verified = $true
        }
    }
    $evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidenceFile -Encoding utf8
    $completed = $true

    Write-Host "Backup and restore drill passed." -ForegroundColor Green
    Write-Host "Encrypted backup: $encryptedDump"
    Write-Host "Evidence: $evidenceFile"
} finally {
    if ($containerStarted) {
        & docker rm --force $containerName *> $null
    }
    if (Test-Path -LiteralPath $temporaryDump) { Remove-Item -LiteralPath $temporaryDump -Force }
    if (Test-Path -LiteralPath $plainDump) { Remove-Item -LiteralPath $plainDump -Force }
    if (-not $completed) {
        @($encryptedDump, $protectedKeyFile, $tocFile, $evidenceFile) | ForEach-Object {
            if (Test-Path -LiteralPath $_) { Remove-Item -LiteralPath $_ -Force }
        }
    }
}
