. (Join-Path $PSScriptRoot "common-local.ps1")
$Host.UI.RawUI.WindowTitle = "Smart Sportswear - Identity :8093"
Initialize-LocalServiceEnvironment -ServiceEnvFile (Join-Path $script:ProjectRoot "identity-service\.env")
Assert-LocalPortAvailable -Port 8093 -ServiceName "Identity service"

$maven = Join-Path $script:ProjectRoot "mvnw.cmd"
$service = Join-Path $script:ProjectRoot "identity-service"
$code = Invoke-LoggedCommand -Name "identity-service" -WorkingDirectory $service -Executable $maven -Arguments @("-f", "pom.xml", "spring-boot:run")
exit $code
