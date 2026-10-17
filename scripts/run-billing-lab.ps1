[CmdletBinding()]
param(
    [string]$Maven='mvn.cmd',
    [string]$MavenRepository='',
    [string]$Psql='psql.exe',
    [switch]$InitializeDatabase,
    [switch]$Online,
    [ValidateRange(10,300)][int]$SteadyCount=40,
    [ValidateRange(1,30)][int]$SteadyRate=10,
    [ValidateRange(40,500)][int]$BurstCount=120,
    [ValidateRange(200,1000)][int]$BurstRate=500,
    [ValidateRange(2,16)][int]$Concurrency=8,
    [ValidateRange(1,64)][int]$QueueCapacity=16
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if($env:BILLING_LAB_JDBC_URL -cne 'jdbc:postgresql://127.0.0.1:15432/invoice_payments_simulation_test' -or
   $env:BILLING_LAB_DB_USER -cne 'billing_test' -or !$env:BILLING_LAB_DB_PASSWORD) {
    throw 'Set the exact disposable lab URL, billing_test user and BILLING_LAB_DB_PASSWORD. This suite resets only that dedicated lab database; application databases are forbidden.'
}
if($InitializeDatabase) {
    $previousPgPassword=$env:PGPASSWORD
    try {
        $env:PGPASSWORD=$env:BILLING_LAB_DB_PASSWORD
        $present=& $Psql -h 127.0.0.1 -p 15432 -U billing_test -d postgres -v ON_ERROR_STOP=1 -Atc "SELECT 1 FROM pg_database WHERE datname='invoice_payments_simulation_test'"
        if($LASTEXITCODE -ne 0){throw 'Could not inspect the local test cluster'}
        if(!$present){
            & $Psql -h 127.0.0.1 -p 15432 -U billing_test -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE invoice_payments_simulation_test OWNER billing_test'
            if($LASTEXITCODE -ne 0){throw 'Could not create the dedicated lab database'}
        }
    } finally { $env:PGPASSWORD=$previousPgPassword }
}
if(!$MavenRepository -and (Test-Path -LiteralPath (Join-Path $projectRoot '.maven-cache'))){$MavenRepository=Join-Path $projectRoot '.maven-cache'}
$arguments=@()
if(!$Online){$arguments+='-o'}
if($MavenRepository){$arguments+="-Dmaven.repo.local=$MavenRepository"}
$arguments+=@('-f',(Join-Path $projectRoot 'billing-service/pom.xml'),'-Dtest=BillingSimulationLabTest',
    "-Dbilling.lab.steadyCount=$SteadyCount","-Dbilling.lab.steadyRate=$SteadyRate",
    "-Dbilling.lab.burstCount=$BurstCount","-Dbilling.lab.burstRate=$BurstRate",
    "-Dbilling.lab.concurrency=$Concurrency","-Dbilling.lab.queueCapacity=$QueueCapacity",'test')
$logDirectory=Join-Path $projectRoot '.tmp-notes/billing-lab'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$log=Join-Path $logDirectory 'test.log'
$reportPath=Join-Path $projectRoot 'billing-service/target/billing-lab-report.json'
if(Test-Path -LiteralPath $reportPath){Remove-Item -LiteralPath $reportPath}
Write-Host 'Running local synthetic PSP/bank/ERP fault scenarios and bounded arrival load. No live provider is contacted.'
& $Maven @arguments *> $log
if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 60;throw "Simulation lab failed; inspect $log"}
[xml]$junit=Get-Content -LiteralPath (Join-Path $projectRoot 'billing-service/target/surefire-reports/TEST-com.invoicepayments.billing.BillingSimulationLabTest.xml') -Raw
if([int]$junit.testsuite.tests -ne 7 -or [int]$junit.testsuite.failures -ne 0 -or [int]$junit.testsuite.errors -ne 0 -or [int]$junit.testsuite.skipped -ne 0){throw 'The complete seven-test simulation lab did not pass'}
$report=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$report | Add-Member -NotePropertyName junit -NotePropertyValue @{tests=7;failures=0;errors=0;skipped=0} -Force
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding utf8
foreach($phase in $report.loadPhases){
    Write-Host ("{0}: offered={1}, successful={2}, failed={3}, dropped={4}, completed workflows/s={5:N2}, p95 including queue={6:N2} ms" -f
        $phase.name,$phase.offered,$phase.successful,$phase.failed,$phase.droppedBeforeStart,$phase.successfulWorkflowsPerSecond,$phase.workflowLatencyIncludingQueueMs.p95)
}
Write-Host "Seven simulation scenarios passed. Evidence: $reportPath"
Write-Host 'These measurements apply only to the stated single-machine synthetic workload. Kafka transport is tested separately by verify-billing.ps1 -WithDatabase.'
