[CmdletBinding()]
param(
    [string]$Maven = 'mvn.cmd',
    [string]$Node = 'node',
    [string]$MavenRepository = '',
    [switch]$WithDatabase,
    [switch]$Online,
    [switch]$SkipFrontend,
    [switch]$ListOnly
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if(!$MavenRepository -and (Test-Path -LiteralPath (Join-Path $projectRoot '.maven-cache'))) {
    $MavenRepository=Join-Path $projectRoot '.maven-cache'
}
# Database suites explicitly require a disposable database and reset their own fixtures.
# Never replace their allowlist with an application database.
if($WithDatabase -and !$ListOnly) {
    if($env:BILLING_TEST_JDBC_URL -cne 'jdbc:postgresql://127.0.0.1:15432/invoice_payments_test' -or
       $env:BILLING_TEST_DB_USER -cne 'billing_test' -or !$env:BILLING_TEST_DB_PASSWORD) {
        throw 'Database checks require the exact disposable URL/user and BILLING_TEST_DB_PASSWORD. They reset only the allowlisted billing test schema. Never supply an application database.'
    }
}
$paymentTests='StripeBillingProviderTest,StripeWebhookVerifierTest,BillingConfigurationTest,BillingMskIamConfigurationTest'
$identityTests='DemoUserSeederTest,IdentityConfigurationTest'
if($WithDatabase) {
    $paymentTests+=',SimulatorBillingProviderDatabaseTest,BillingFinancialDatabaseTest,BillingHttpSecurityDatabaseTest,BillingKafkaIntegrationTest,BillingMetricsDatabaseTest'
    $identityTests+=',IdentityApplicationDatabaseTest'
}
foreach($check in @(@{Module='identity-service';Tests=$identityTests},@{Module='billing-service';Tests=$paymentTests})) {
    $arguments=@()
    if(!$Online) {$arguments+='-o'}
    if($MavenRepository) {$arguments+="-Dmaven.repo.local=$MavenRepository"}
    $arguments+=@('-f',(Join-Path $projectRoot "$($check.Module)/pom.xml"),"-Dtest=$($check.Tests)",'test')
    Write-Host "$($check.Module): $($check.Tests)"
    if(!$ListOnly) {
        & $Maven @arguments
        if($LASTEXITCODE -ne 0) {throw "$($check.Module) billing checks failed"}
    }
}
if(!$SkipFrontend) {
    Write-Host 'Angular: billing, shell and authentication interceptor regression tests, then optimized build'
    if(!$ListOnly) {
        Push-Location -LiteralPath (Join-Path $projectRoot 'frontend')
        try {
            & $Node 'node_modules/@angular/cli/bin/ng.js' test --watch=false '--include=src/app/billing/**/*.spec.ts' '--include=src/app/app.spec.ts' '--include=src/app/shared/auth.interceptor.spec.ts'
            if($LASTEXITCODE -ne 0) {throw 'Angular billing tests failed'}
            & $Node 'node_modules/@angular/cli/bin/ng.js' build
            if($LASTEXITCODE -ne 0) {throw 'Angular build failed'}
        } finally {Pop-Location}
    }
}
if($ListOnly) {Write-Host 'Preview only: no checks executed.'}
elseif(!$WithDatabase) {Write-Host 'Selected checks passed. Database, Kafka transport, and cloud/provider tests were NOT run; use -WithDatabase for the disposable PostgreSQL/Kafka tests.'}
else {Write-Host 'Selected billing checks passed against the disposable database and embedded Kafka. This is not a live-provider, Docker, AWS, or production capacity test.'}
