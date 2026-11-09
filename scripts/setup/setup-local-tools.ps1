$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$toolsDir = Join-Path $projectRoot ".tools"
$javaHome = Join-Path $toolsDir "jdk-21"
$javaExe = Join-Path $javaHome "bin\java.exe"

if (Test-Path -LiteralPath $javaExe -PathType Leaf) {
    Write-Host "Java 21 is already available at $javaHome" -ForegroundColor Green
    exit 0
}

New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
$downloadFile = Join-Path $toolsDir "temurin-jdk21.zip"
$extractDir = Join-Path $toolsDir "jdk21-extract"
$resolvedTools = (Resolve-Path -LiteralPath $toolsDir).Path.TrimEnd('\') + '\'
foreach ($target in @($downloadFile, $extractDir, $javaHome)) {
    $absoluteTarget = [System.IO.Path]::GetFullPath($target)
    if (-not $absoluteTarget.StartsWith($resolvedTools, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside $toolsDir`: $absoluteTarget"
    }
}

Remove-Item -LiteralPath $downloadFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $extractDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
Write-Host "Resolving the latest Temurin Java 21 build..." -ForegroundColor Cyan
$assets = Invoke-RestMethod -Uri "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse"
$packageUrl = $assets[0].binary.package.link
if (-not $packageUrl) { throw "Adoptium did not return a Java 21 download URL." }
Write-Host "Downloading Java 21..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $packageUrl -OutFile $downloadFile
Expand-Archive -LiteralPath $downloadFile -DestinationPath $extractDir -Force
$extractedJdk = Get-ChildItem -LiteralPath $extractDir -Directory | Where-Object Name -Like "jdk-*" | Select-Object -First 1
if (-not $extractedJdk) { throw "The Java archive did not contain a JDK directory." }
if (-not ($extractedJdk.FullName.TrimEnd('\') + '\').StartsWith($resolvedTools, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to move a JDK from outside $toolsDir"
}
Move-Item -LiteralPath $extractedJdk.FullName -Destination $javaHome
Remove-Item -LiteralPath $downloadFile -Force
Remove-Item -LiteralPath $extractDir -Recurse -Force
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { throw "Java 21 setup failed." }
Write-Host "Installed $(& $javaExe --version | Select-Object -First 1) at $javaHome" -ForegroundColor Green
