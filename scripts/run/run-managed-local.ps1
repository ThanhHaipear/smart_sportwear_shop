param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$TargetScript,
    [Parameter(Mandatory = $true)][string]$TargetArgumentsBase64
)

$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$logDirectory = Join-Path $projectRoot "logs\local"
$runDirectory = Join-Path $projectRoot "tmp\local-runtime"
$pidFile = Join-Path $runDirectory "$Name.managed.pid"
$logFile = Join-Path $logDirectory "$Name.log"
$argumentJson = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($TargetArgumentsBase64))
$decodedArguments = ConvertFrom-Json -InputObject $argumentJson
$targetArguments = if ($null -eq $decodedArguments) { @() } else { @($decodedArguments) }
$targetParameters = @{}
for ($index = 0; $index -lt $targetArguments.Count; $index += 2) {
    $parameterName = [string]$targetArguments[$index]
    if (-not $parameterName.StartsWith("-") -or $index + 1 -ge $targetArguments.Count) {
        throw "Managed target arguments must be supplied as named parameter/value pairs."
    }
    $targetParameters[$parameterName.TrimStart("-")] = $targetArguments[$index + 1]
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

try {
    & $TargetScript @targetParameters
    exit $LASTEXITCODE
}
catch {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] FATAL managed launcher failed: $($_.Exception.Message)" |
        Out-File -LiteralPath $logFile -Append -Encoding Unicode
    exit 1
}
finally {
    if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
        $savedContent = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
        $savedPid = $null
        try {
            if ($savedContent -match '^\s*\{') { $savedPid = ($savedContent | ConvertFrom-Json).pid }
            elseif ($savedContent -match '^\s*(\d+)\s*$') { $savedPid = $Matches[1] }
        }
        catch { $savedPid = $null }
        if ([string]$savedPid -eq [string]$PID) {
            Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        }
    }
}
