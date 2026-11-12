param([switch]$ImplementationOnly,[string]$SalesDatabaseUrl=$env:SALES_PERMISSION_TEST_URL)
$ErrorActionPreference="Stop";$root=Split-Path -Parent $PSScriptRoot
$migration=Get-Content -Raw (Join-Path $root "sales-service\src\main\resources\db\migration\V1__create_sales_schema.sql")
if($migration -notmatch 'revoke all on schema public from sales_app' -or $migration -match 'references public\.') {throw "sales permission/data boundary is invalid"}
$surface=$migration+(Get-Content -Raw (Join-Path $root "sales-service\src\main\java\com\dunghaiquyen\sales\domain\SalesDomainService.java"))+(Get-Content -Raw (Join-Path $root "sales-service\src\main\java\com\dunghaiquyen\sales\persistence\JdbcSalesRepository.java"))+(Get-Content -Raw (Join-Path $root "sales-service\src\main\java\com\dunghaiquyen\sales\api\SePaySignatureVerifier.java"))
foreach($needle in @('MessageDigest.isEqual','pg_advisory_xact_lock','for update','sales.payment.completed.v1','sales.refund.completed.v1','LATE_PAYMENT','integration_outbox')){if($surface -notmatch [regex]::Escape($needle)){throw "missing Phase 8 invariant: $needle"}}
Push-Location (Join-Path $root "sales-service")
try{& ..\mvnw.cmd -q -o -f pom.xml test;if($LASTEXITCODE -ne 0){throw "sales-service test gate failed"}}finally{Pop-Location}
$contract=Get-Content -Raw (Join-Path $root "contracts\rest\sales-service-v1.yaml")
if($contract -notmatch 'openapi: 3\.0\.3' -or $contract -notmatch '/api/v1/webhooks/sepay'){throw "sales REST contract is invalid"}
$compose=Get-Content -Raw (Join-Path $root "docker-compose.yml");if($compose -notmatch '(?m)^  sales-service:'){throw "sales-service is missing from Docker Compose"}
Write-Host "Phase 8 implementation, SePay idempotency and financial transaction gate passed."
if(-not $ImplementationOnly){if([string]::IsNullOrWhiteSpace($SalesDatabaseUrl)){throw "sales_app DSN is required by the Phase 8 acceptance gate"};& python (Join-Path $root "sales-service\scripts\verify_schema_permissions.py") $SalesDatabaseUrl;if($LASTEXITCODE -ne 0){throw "sales_app permission test failed"};Write-Host "Phase 8 acceptance gate passed."}
