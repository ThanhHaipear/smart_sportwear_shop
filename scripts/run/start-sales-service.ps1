. (Join-Path $PSScriptRoot "common-local.ps1")
$Host.UI.RawUI.WindowTitle = "Smart Sportswear - Sales :8094"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $script:ProjectRoot "sales-service\.env")
Assert-LocalPortAvailable -Port 8094 -ServiceName "Sales service"

$maven = Join-Path $script:ProjectRoot "mvnw.cmd"
$service = Join-Path $script:ProjectRoot "sales-service"
$code = Invoke-LoggedCommand -Name "sales-service" -WorkingDirectory $service -Executable $maven -Arguments @("-f", "pom.xml", "spring-boot:run")
exit $code
