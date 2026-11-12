. (Join-Path $PSScriptRoot "common-local.ps1")
$Host.UI.RawUI.WindowTitle = "Smart Sportswear - Catalog :8092"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $script:ProjectRoot "catalog-service\.env")
Assert-LocalPortAvailable -Port 8092 -ServiceName "Catalog service"

$maven = Join-Path $script:ProjectRoot "mvnw.cmd"
$service = Join-Path $script:ProjectRoot "catalog-service"
$code = Invoke-LoggedCommand -Name "catalog-service" -WorkingDirectory $service -Executable $maven -Arguments @("-f", "pom.xml", "spring-boot:run")
exit $code
