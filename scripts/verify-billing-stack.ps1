[CmdletBinding()]
param([switch]$SkipBuild,[switch]$Quick)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$project='invoice-payments-drill'
$settings=Join-Path $root '.env.billing.local'
if(!(Test-Path -LiteralPath $settings)){throw 'Run scripts/start-billing.ps1 -ValidateOnly to initialize ignored local secrets first.'}
$compose=@('compose','--project-name',$project,'--env-file',$settings,'-f',(Join-Path $root 'docker-compose.billing.yml'))
$savedEnvironment=@{}
foreach($entry in @{BILLING_FRONTEND_PORT='4500';BILLING_GATEWAY_PORT='8380';BILLING_PROMETHEUS_PORT='9390';BILLING_GRAFANA_PORT='3300';BILLING_PROVIDER='SIMULATOR'}.GetEnumerator()){
    $savedEnvironment[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key)
    [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value)
}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$restoreDatabase='invoice_restore_'+[guid]::NewGuid().ToString('N').Substring(0,16)
$directory=Join-Path $root ".tmp-notes/billing-stack/$stamp"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$checks=[System.Collections.Generic.List[object]]::new()
$report=[ordered]@{startedUtc=[DateTime]::UtcNow.ToString('o');project=$project;provider='SIMULATOR';mode=$(if($Quick){'smoke'}else{'full-recovery'});status='RUNNING';checks=$checks;scope='Local Docker stack; no real PSP, bank, ERP or cloud. Backup is a logical snapshot, not PITR or cross-host failover.'}
$base='http://localhost:4500'
$prom='http://127.0.0.1:9390'
$grafana='http://127.0.0.1:3300'
$stopped=[System.Collections.Generic.HashSet[string]]::new()
function Save-Report {
    $report | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $directory 'report.json') -Encoding utf8
}
function Compose([string[]]$Arguments){
    $output=& docker @compose @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "Docker Compose $($Arguments[0]) failed: $($output -join [Environment]::NewLine)"}
    return $output
}
function Container([string]$Service){
    $id=(@(Compose @('ps','-a','-q',$Service)) -join '').Trim()
    if($id -notmatch '^[a-f0-9]{12,64}$'){throw "Expected exactly one drill container for $Service"}
    $label=& docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' $id
    if($LASTEXITCODE -ne 0 -or $label -cne "$project|$Service"){throw 'Container ownership mismatch; refusing drill mutation'}
    return $id
}
function Sql([string]$Query,[string]$Database='invoice_payments'){
    if($Database -cne 'invoice_payments' -and $Database -cne $script:restoreDatabase){throw 'Database outside drill allowlist'}
    $id=Container 'postgres'
    $output=& docker exec $id psql -X -U invoice_local -d $Database -v ON_ERROR_STOP=1 -Atc $Query 2>&1
    if($LASTEXITCODE -ne 0){throw "Drill SQL failed: $($output -join ' ')"}
    return ($output -join "`n").Trim()
}
function Wait-For([string]$Description,[scriptblock]$Predicate,[int]$Seconds=180){
    $watch=[Diagnostics.Stopwatch]::StartNew();$lastProgress=-30
    while($watch.Elapsed.TotalSeconds -lt $Seconds){
        try{if(& $Predicate){return [math]::Round($watch.Elapsed.TotalSeconds,3)}}catch{}
        if($watch.Elapsed.TotalSeconds-$lastProgress -ge 30){Write-Host "Waiting: $Description ($([int]$watch.Elapsed.TotalSeconds)s)";$lastProgress=$watch.Elapsed.TotalSeconds}
        Start-Sleep -Seconds 3
    }
    throw "Timed out: $Description after ${Seconds}s"
}
function Check([string]$Name,[scriptblock]$Action){
    if($Quick -and $Name -notin @('Full stack, browser assets, identity, CSRF and customer API','Simulated capture reaches Kafka and ERP exactly once','Provisioned Grafana dashboard queries live Prometheus data')){return}
    Write-Host "Checking: $Name";$watch=[Diagnostics.Stopwatch]::StartNew()
    try{$evidence=& $Action;$checks.Add([ordered]@{name=$Name;status='PASS';seconds=[math]::Round($watch.Elapsed.TotalSeconds,3);evidence=$evidence})}
    catch{$checks.Add([ordered]@{name=$Name;status='FAIL';seconds=[math]::Round($watch.Elapsed.TotalSeconds,3);error=$_.Exception.Message});throw}
    finally{Save-Report}
}
function Api([string]$Path,[string]$Method='GET',$Body=$null,[string]$Key=''){
    $headers=@{}
    if($script:webSession){
        $csrf=$script:webSession.Cookies.GetCookies($base)['XSRF-TOKEN']
        if($csrf){$headers['X-XSRF-TOKEN']=[Uri]::UnescapeDataString($csrf.Value)}
    }
    if($Key){$headers['Idempotency-Key']=$Key}
    $args=@{Uri="$base$Path";Method=$Method;UseBasicParsing=$true;TimeoutSec=12;Headers=$headers;WebSession=$script:webSession}
    if($null -ne $Body){$args.ContentType='application/json';$args.Body=ConvertTo-Json -InputObject $Body -Depth 8 -Compress}
    $response=Invoke-WebRequest @args
    if($response.Content){return ConvertFrom-Json -InputObject $response.Content}
}
function New-Payment([string]$Name){
    $invoice=[guid]::NewGuid().ToString()
    $null=Sql "INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES('$invoice','20000000-0000-0000-0000-000000000001','DRILL-$stamp-$Name','USD',12345,CURRENT_DATE+7)"
    $created=Api '/api/v1/billing/accounts/20000000-0000-0000-0000-000000000001/intents' 'POST' @{invoiceIds=@($invoice);method='ACH'} "drill-$stamp-$Name"
    $null=Api "/api/v1/billing/intents/$($created.id)/submit" 'POST'
    return [string]$created.id
}
function Captured([string]$Intent){return (Sql "SELECT status FROM billing.intents WHERE id='$Intent'") -ceq 'CAPTURED'}
function Delivered([string]$Intent){return (Sql "SELECT (EXISTS(SELECT 1 FROM billing.outbox WHERE aggregate_id='$Intent' AND state='PUBLISHED') AND EXISTS(SELECT 1 FROM billing.erp_postings WHERE intent_id='$Intent' AND status='ACKNOWLEDGED'))::text") -ceq 'true'}
function Integrity{
    $bad=Sql "SELECT (SELECT count(*) FROM (SELECT j.id FROM billing.journals j LEFT JOIN billing.ledger_entries e ON e.journal_id=j.id GROUP BY j.id HAVING count(e.line)<2 OR sum(CASE e.side WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)<>0) x)+(SELECT count(*) FROM billing.invoices WHERE paid_minor<0 OR reserved_minor<0 OR paid_minor+reserved_minor>total_minor)"
    if($bad -cne '0'){throw 'Financial invariants failed'}
    # Partial refunds are distinct effects on the same payment/kind. Their
    # canonical external references include the refund ID and must remain unique.
    $duplicates=Sql "SELECT count(*) FROM (SELECT external_reference FROM billing.erp_postings WHERE external_reference IS NOT NULL GROUP BY external_reference HAVING count(*)>1) x"
    if($duplicates -cne '0'){throw 'Duplicate ERP effects found'}
    return 'Balanced journals, valid invoice balances, no duplicate ERP effect'
}
function Stop-Service([string]$Service){$null=Container $Service;$null=$stopped.Add($Service);$null=Compose @('stop','-t','5',$Service)}
function Start-Service([string]$Service){$null=Compose @('start',$Service);$null=$stopped.Remove($Service)}
function PaymentReady{
    $id=Container 'billing-service'
    & docker exec $id curl -fsS --max-time 3 http://localhost:9090/actuator/health/readiness 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}
function Query([string]$Expression){return Invoke-RestMethod -Uri "$prom/api/v1/query?query=$([Uri]::EscapeDataString($Expression))" -TimeoutSec 8}
function AlertActive([string]$Name){$alerts=Invoke-RestMethod -Uri "$prom/api/v1/alerts" -TimeoutSec 8;return @($alerts.data.alerts | Where-Object {$_.labels.alertname -ceq $Name -and $_.state -ceq 'firing'}).Count -gt 0}
function Snapshot([string]$Database){
    $tables=(Sql "SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('billing','identity') ORDER BY schemaname,tablename" $Database) -split "`n"
    $lines=foreach($table in $tables){
        if($table -notmatch '^(billing|identity)\.[a-z_][a-z_0-9]*$'){throw 'Unexpected snapshot table'}
        Sql "SELECT '$table|'||count(*)||'|'||md5(COALESCE(string_agg(to_jsonb(t)::text,E'\n' ORDER BY to_jsonb(t)::text),'')) FROM $table t" $Database
    }
    return ($lines -join "`n")
}
Save-Report
try{
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Docker Desktop must be running'}
    $null=Compose @('config','--quiet')
    $startup=@('up','-d','--wait','--wait-timeout','360')
    if(!$SkipBuild){$startup+='--build'}
    $null=Compose $startup
    # Refresh mounted alert rules even when the existing monitoring container was reused.
    $null=Compose @('restart','prometheus')
    $report.gitCommit=(& git -C $root rev-parse HEAD).Trim()
    if($LASTEXITCODE -ne 0){throw 'Could not record the source commit'}
    $gitStatus=@(& git -C $root status --porcelain)
    if($LASTEXITCODE -ne 0){throw 'Could not record source status'}
    $report.gitWorkingTreeClean=($gitStatus.Count -eq 0)
    $report.sourceHashes=@{}
    foreach($file in @('billing-service/src/main/java/com/invoicepayments/billing/BillingWorkers.java',
        'billing-service/src/main/java/com/invoicepayments/billing/BillingKafkaConfiguration.java',
        'billing-service/src/main/java/com/invoicepayments/billing/BillingMetrics.java',
        'billing-service/src/main/java/com/invoicepayments/billing/provider/SimulatorBillingProvider.java',
        'billing-service/src/main/resources/db/billing/V9__ordered_outbox.sql',
        'billing-service/src/main/resources/db/billing/V10__outbox_quarantine.sql',
        'monitoring/prometheus/billing-rules.yml','scripts/verify-billing-stack.ps1')) {
        $report.sourceHashes[$file]=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root $file)).Hash.ToLowerInvariant()
    }
    Check 'Full stack, browser assets, identity, CSRF and customer API' {
        $script:webSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $html=Invoke-WebRequest -UseBasicParsing -Uri "$base/login" -TimeoutSec 15
        if($html.StatusCode -ne 200 -or $html.Content -notmatch '<app-root'){throw 'Angular shell not served'}
        if($html.Content -match 'checkout\.razorpay\.com'){throw 'Billing HTML unexpectedly loads the legacy PSP script'}
        $asset=[regex]::Match($html.Content,'<script\s+src="([^":]+\.js)"').Groups[1].Value
        if(!$asset){throw 'No Angular JavaScript bundle found'}
        $null=Invoke-WebRequest -UseBasicParsing -Uri ([Uri]::new([Uri]"$base/",$asset)) -TimeoutSec 15
        $null=Api '/api/v1/auth/login' 'POST' @{username='demo';password='demo123'}
        $accounts=Api '/api/v1/billing/accounts'
        if(@($accounts).Count -ne 1){throw 'Customer scope failed'}
        if(!$script:webSession.Cookies.GetCookies($base)['XSRF-TOKEN']){throw 'CSRF cookie missing'}
        foreach($service in @('identity-service','billing-service','api-gateway')){$null=Container $service}
        $null=Wait-For 'all three application Prometheus targets' { $result=Query 'count(up{job="invoice-payments"} == 1)';$result.data.result[0].value[1] -ceq '3' }
        $report.postgresql=Sql 'SELECT version()'
        # A healthy scrape can contain NaN business gauges. Require actual numeric
        # signals before waiting for business alerts, rather than hiding that defect.
        foreach($metric in @('billing_outbox_pending','billing_outbox_oldest_seconds','billing_payments_unresolved','billing_payments_stuck')){
            $result=Query $metric
            if(@($result.data.result).Count -ne 1 -or $result.data.result[0].value[1] -in @('NaN','+Inf','-Inf')){throw "Business metric unavailable: $metric"}
        }
        $rules=Invoke-RestMethod -Uri "$prom/api/v1/rules" -TimeoutSec 8
        $stuckRules=@($rules.data.groups | ForEach-Object {$_.rules} | Where-Object {$_.name -ceq 'BillingPaymentStuck'})
        if($stuckRules.Count -ne 1 -or $stuckRules[0].health -cne 'ok'){throw 'Stuck-payment alert rule is missing or unhealthy'}
        'Angular bundle -> nginx -> gateway -> identity and billing; cookie session and private metrics targets healthy'
    }
    if($Quick){
        $report.scope='Source-extraction smoke: real local stack and Kafka, simulated payment/ERP, live dashboard queries. No restart, outage, alert-firing or backup-restoration drill in this mode.'
        Check 'Simulated capture reaches Kafka and ERP exactly once' {
            $intent=New-Payment 'extraction-smoke'
            $null=Api "/api/v1/billing/intents/$intent/simulate" 'POST' @{outcome='CAPTURED'}
            $elapsed=Wait-For 'capture and real Kafka ERP acknowledgement' {(Captured $intent) -and (Delivered $intent)} 120
            if((Sql "SELECT count(*) FROM billing.journals WHERE intent_id='$intent'") -cne '1'){throw 'Expected one capture journal'}
            if((Sql "SELECT count(*) FROM billing.erp_postings WHERE intent_id='$intent'") -cne '1'){throw 'Expected one ERP posting'}
            @{intent=$intent;kafkaDeliverySeconds=$elapsed;journals=1;erpPostings=1;integrity=(Integrity)}
        }
    }
    Check 'Background payment recovery survives an abrupt application restart' {
        $script:restartIntent=New-Payment 'restart'
        $id=Container 'billing-service'
        $null=$stopped.Add('billing-service')
        # SIGKILL, rather than a graceful shutdown, leaves any durable leases to expire normally.
        $null=Compose @('kill','-s','SIGKILL','billing-service')
        # Stand-in external provider evidence arrives while the application is down.
        $null=Sql "UPDATE billing.provider_operations SET state='CAPTURED',updated_at=NOW() WHERE intent_id='$script:restartIntent' AND operation_kind='PAYMENT'"
        Start-Service 'billing-service'
        $elapsed=Wait-For 'scheduled recovery and real Kafka ERP acknowledgement after crash' { (Captured $script:restartIntent) -and (Delivered $script:restartIntent) } 180
        $count=Sql "SELECT count(*) FROM billing.journals WHERE intent_id='$script:restartIntent'"
        if($count -cne '1'){throw 'Expected one capture journal after restart'}
        $broker=Container 'kafka-1'
        $topic=& docker exec -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx64m' $broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic billing.events.v1
        if($LASTEXITCODE -ne 0 -or ($topic -join ' ') -notmatch 'ReplicationFactor:\s*3'){throw 'Billing topic is not replicated across three brokers'}
        $topic | Set-Content -LiteralPath (Join-Path $directory 'kafka-topic.txt') -Encoding utf8
        $payload=Sql "SELECT payload::text FROM billing.outbox WHERE aggregate_id='$script:restartIntent' AND type='PAYMENT_CAPTURED'"
        if(!$payload){throw 'Canonical capture event missing'}
        $payload | & docker exec -i -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx64m' $broker /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic billing.events.v1 --producer-property acks=all
        if($LASTEXITCODE -ne 0){throw 'Duplicate Kafka delivery failed'}
        # Wait for the ERP consumer group's committed offset to pass the duplicate too.
        $null=Wait-For 'ERP Kafka group drains the duplicate event' {
            $group=& docker exec -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx64m' $broker /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group billing-erp-simulator-v1 2>$null
            $lines=@($group | Where-Object {$_ -match '^billing-erp-simulator-v1\s+billing.events.v1\s+'})
            # Empty partitions legitimately have no committed offset and report lag '-'.
            # Every partition with any records must have committed through its log end.
            $LASTEXITCODE -eq 0 -and $lines.Count -gt 0 -and @($lines | Where-Object {
                $columns=$_ -split '\s+'
                $columns[5] -ne '0' -and !($columns[3] -ceq '-' -and $columns[4] -ceq '0')
            }).Count -eq 0
        } 90
        if((Sql "SELECT count(*) FROM billing.erp_postings WHERE intent_id='$script:restartIntent'") -cne '1'){throw 'Duplicate Kafka record duplicated the ERP effect'}
        @{intent=$script:restartIntent;recoverySeconds=$elapsed;topicReplicationFactor=3;duplicateKafkaDelivery='Consumed with one ERP effect';integrity=(Integrity);recoveryDriver='Actual scheduled worker; no manual recovery method invoked'}
    }
    Check 'Kafka outage retains outbox, fires a real alert and drains after restart' {
        foreach($service in @('kafka-1','kafka-2','kafka-3')){Stop-Service $service}
        try{
            $script:kafkaIntent=New-Payment 'kafka-outage'
            $null=Api "/api/v1/billing/intents/$script:kafkaIntent/simulate" 'POST' @{outcome='CAPTURED'}
            if(!(Captured $script:kafkaIntent)){throw 'Capture failed during broker outage'}
            if((Sql "SELECT count(*) FROM billing.outbox WHERE aggregate_id='$script:kafkaIntent' AND state<>'PUBLISHED'") -cne '1'){throw 'Expected one durable pending event'}
            if((Sql "SELECT count(*) FROM billing.erp_postings WHERE intent_id='$script:kafkaIntent'") -cne '0'){throw 'ERP acknowledged without Kafka'}
            $alertSeconds=Wait-For 'BillingOutboxNotDraining to fire using unchanged 60s + 3m thresholds' {AlertActive 'BillingOutboxNotDraining'} 360
            $alerts=Invoke-RestMethod -Uri "$prom/api/v1/alerts" -TimeoutSec 8
            $alerts | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $directory 'outbox-alert-firing.json') -Encoding utf8
        }finally{foreach($service in @('kafka-1','kafka-2','kafka-3')){Start-Service $service}}
        $drainSeconds=Wait-For 'scheduled outbox publish -> three-broker Kafka -> ERP consumer' {Delivered $script:kafkaIntent} 180
        $null=Wait-For 'outbox alert resolves' {!(AlertActive 'BillingOutboxNotDraining')} 90
        @{intent=$script:kafkaIntent;alertFiredAfterSeconds=$alertSeconds;drainSeconds=$drainSeconds;integrity=(Integrity)}
    }
    Check 'Application outage fires and resolves the scrape alert' {
        Stop-Service 'billing-service'
        try{
            $seconds=Wait-For 'ApplicationScrapeUnavailable to fire at its real 2m threshold' {AlertActive 'ApplicationScrapeUnavailable'} 210
            (Invoke-RestMethod -Uri "$prom/api/v1/alerts" -TimeoutSec 8) | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $directory 'application-alert-firing.json') -Encoding utf8
        }finally{Start-Service 'billing-service'}
        $null=Wait-For 'billing readiness after restart' {PaymentReady}
        $null=Wait-For 'scrape alert resolves after restart' {!(AlertActive 'ApplicationScrapeUnavailable')} 90
        @{alertFiredAfterSeconds=$seconds;integrity=(Integrity)}
    }
    Check 'Database outage rejects writes and recovery preserves committed money' {
        $script:dbIntent=New-Payment 'database-outage'
        $invoice=[guid]::NewGuid().ToString()
        $null=Sql "INSERT INTO billing.invoices(id,account_id,number,currency,total_minor,due_date) VALUES('$invoice','20000000-0000-0000-0000-000000000001','DRILL-$stamp-db-write','USD',2222,CURRENT_DATE+7)"
        $request=@{invoiceIds=@($invoice);method='ACH'};$key="drill-$stamp-db-write"
        Stop-Service 'postgres'
        try{
            Start-Sleep -Seconds 5
            if(PaymentReady){throw 'Billing remained ready with database stopped'}
            $status=0
            try{$null=Api '/api/v1/billing/accounts/20000000-0000-0000-0000-000000000001/intents' 'POST' $request $key;throw 'Write unexpectedly succeeded during database outage'}
            catch{if($_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode}else{if($_.Exception.Message -like '*unexpectedly succeeded*'){throw}}}
            if($status -ne 0 -and ($status -lt 500 -or $status -gt 599)){throw "Unexpected outage HTTP status: $status"}
        }finally{Start-Service 'postgres'}
        $null=Wait-For 'database reconnect and billing readiness' {PaymentReady} 120
        # A client timeout is ambiguous: reconnect may let the original command commit.
        # Retry its exact key/payload rather than assuming zero or creating a new payment.
        if([int](Sql "SELECT count(*) FROM billing.intents WHERE idempotency_key='$key'") -gt 1){throw 'Duplicate command committed during outage'}
        $first=Api '/api/v1/billing/accounts/20000000-0000-0000-0000-000000000001/intents' 'POST' $request $key
        $again=Api '/api/v1/billing/accounts/20000000-0000-0000-0000-000000000001/intents' 'POST' $request $key
        if($first.id -cne $again.id){throw 'Retry created a second payment'}
        if((Sql "SELECT count(*) FROM billing.intents WHERE idempotency_key='$key'") -cne '1'){throw 'Expected exactly one command after retries'}
        $null=Api "/api/v1/billing/intents/$($first.id)/cancel" 'POST'
        $null=Api "/api/v1/billing/intents/$script:dbIntent/simulate" 'POST' @{outcome='TIMEOUT_AFTER_ACCEPT'}
        $elapsed=Wait-For 'uncertain payment recovery and ERP after database reconnect' {(Captured $script:dbIntent) -and (Delivered $script:dbIntent)} 180
        foreach($intent in @($script:restartIntent,$script:kafkaIntent,$script:dbIntent)){
            if((Sql "SELECT count(*) FROM billing.journals WHERE intent_id='$intent'") -cne '1'){throw 'Capture lost or duplicated after database outage'}
        }
        @{outageHttpStatus=$status;retryIntent=$first.id;recoverySeconds=$elapsed;integrity=(Integrity)}
    }
    Check 'Logical backup restores every identity and billing table into a separate database' {
        # Alert holds are intentionally long; refresh the short-lived demo session
        # before quiescing identity so the restored API check uses a valid credential.
        $null=Api '/api/v1/auth/login' 'POST' @{username='demo';password='demo123'}
        foreach($service in @('billing-service','identity-service')){Stop-Service $service}
        try{
            $script:before=Snapshot 'invoice_payments'
            $before | Set-Content -LiteralPath (Join-Path $directory 'source-table-digests.txt') -Encoding utf8
            $pg=Container 'postgres'
            & docker exec $pg pg_dump -U invoice_local -d invoice_payments --format=custom --file=/tmp/billing-drill.dump
            if($LASTEXITCODE -ne 0){throw 'pg_dump failed'}
            $script:backup=Join-Path $directory 'billing-drill.dump'
            & docker cp "${pg}:/tmp/billing-drill.dump" $backup
            if($LASTEXITCODE -ne 0){throw 'Backup copy failed'}
        }finally{foreach($service in @('identity-service','billing-service')){Start-Service $service}}
        if((Sql "SELECT count(*) FROM pg_database WHERE datname='$restoreDatabase'") -cne '0'){throw 'Restore target already exists; refusing overwrite'}
        $null=Sql "CREATE DATABASE $restoreDatabase OWNER invoice_local"
        & docker exec $pg pg_restore -U invoice_local -d $restoreDatabase --exit-on-error --no-owner --no-privileges /tmp/billing-drill.dump
        if($LASTEXITCODE -ne 0){throw 'pg_restore failed'}
        $restored=Snapshot $restoreDatabase
        $restored | Set-Content -LiteralPath (Join-Path $directory 'restored-table-digests.txt') -Encoding utf8
        if($before -cne $restored){throw 'Restored table counts/content digests differ from the quiesced source'}
        $hash=(Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
        # No Kafka on this restored boot: replaying a historical DB into the original live
        # broker/group is not a safe disaster-recovery procedure. This is an isolated boot check.
        $restoreOutput=@(Compose @('run','-d','--no-deps','-e',"SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/$restoreDatabase",'-e','BILLING_KAFKA_ENABLED=false','-e','BILLING_WORKERS_ENABLED=false','-e','BILLING_DEMO_ENABLED=false','billing-service'))
        $ids=@($restoreOutput | ForEach-Object {"$_".Trim()} | Where-Object {$_ -cmatch '^[a-f0-9]{64}$'})
        if($ids.Count -ne 1){throw 'Expected exactly one restore container ID'}
        $restoreId=$ids[0]
        try{
            $seconds=Wait-For 'application boots on the restored database with migrations intact' {
                & docker exec $restoreId curl -fsS --max-time 3 http://localhost:9090/actuator/health/readiness 2>$null | Out-Null
                $LASTEXITCODE -eq 0
            } 180
            $cookie=$script:webSession.Cookies.GetCookies($base)['IP_AUTH'].Value
            $history=& docker exec $restoreId curl -fsS --max-time 8 -H "Cookie: IP_AUTH=$cookie" http://localhost:8084/api/v1/billing/accounts/20000000-0000-0000-0000-000000000001/intents
            if($LASTEXITCODE -ne 0){throw 'Restored application could not serve the real authenticated customer history'}
            $historyObjects=ConvertFrom-Json -InputObject ($history -join '')
            foreach($intent in @($script:restartIntent,$script:kafkaIntent,$script:dbIntent)){
                if(@($historyObjects | Where-Object {$_.id -ceq $intent -and $_.status -ceq 'CAPTURED'}).Count -ne 1){throw 'Captured payment absent from restored application history'}
            }
        }finally{
            & docker stop --time 5 $restoreId | Out-Null
            & docker rm $restoreId | Out-Null
        }
        @{tableCount=($before -split "`n").Count;restoredDatabase=$restoreDatabase;backupBytes=(Get-Item -LiteralPath $backup).Length;backupSha256=$hash;restoredBootSeconds=$seconds;restoredCapturedPayments=3;integrity=(Integrity)}
    }
    Check 'Provisioned Grafana dashboard queries live Prometheus data' {
        $secretLine=Get-Content -LiteralPath $settings | Where-Object {$_ -cmatch '^BILLING_GRAFANA_PASSWORD='} | Select-Object -First 1
        $password=$secretLine.Substring('BILLING_GRAFANA_PASSWORD='.Length)
        $authorization=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$password"))
        $headers=@{Authorization="Basic $authorization"}
        $dashboard=Invoke-RestMethod -Uri "$grafana/api/dashboards/uid/billing-reference" -Headers $headers -TimeoutSec 15
        if($dashboard.dashboard.uid -cne 'billing-reference'){throw 'Billing dashboard not provisioned'}
        $health=Invoke-RestMethod -Uri "$grafana/api/datasources/uid/invoice-payments-prometheus/health" -Headers $headers -TimeoutSec 15
        if($health.status -cne 'OK'){throw 'Grafana datasource unhealthy'}
        $expressions=@($dashboard.dashboard.panels | ForEach-Object {$_.targets} | Where-Object {$_ -and $_.expr} | ForEach-Object {$_.expr})
        foreach($expression in $expressions){
            $result=Invoke-RestMethod -Uri "$grafana/api/datasources/proxy/uid/invoice-payments-prometheus/api/v1/query?query=$([Uri]::EscapeDataString($expression))" -Headers $headers -TimeoutSec 15
            if($result.status -cne 'success'){throw "Dashboard query failed: $expression"}
        }
        $null=Wait-For 'all application scrape targets recovered' {$result=Query 'count(up{job="invoice-payments"} == 1)';$result.data.result[0].value[1] -ceq '3'}
        $live=Query 'billing_outbox_pending'
        if(@($live.data.result).Count -ne 1 -or $live.data.result[0].value[1] -cne '0'){throw 'Missing/nonzero live outbox gauge'}
        @{dashboardUid=$dashboard.dashboard.uid;queryCount=$expressions.Count;datasource='OK';outboxPending=0}
    }
    $report.status='PASS'
}catch{
    $report.status='FAIL';$report.error=$_.Exception.Message
    throw
}finally{
    foreach($service in @($stopped)){try{Start-Service $service}catch{Write-Warning "Could not restart drill service $service"}}
    $report.finishedUtc=[DateTime]::UtcNow.ToString('o')
    Save-Report
    try{Compose @('ps','-a') | Set-Content -LiteralPath (Join-Path $directory 'containers.txt') -Encoding utf8}catch{}
    try{Compose @('images','--format','json') | Set-Content -LiteralPath (Join-Path $directory 'images.json') -Encoding utf8}catch{}
    foreach($entry in $savedEnvironment.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value)}
    Write-Host "Drill status: $($report.status). Evidence: $directory/report.json"
}
if($Quick){Write-Host 'Three source-extraction smoke checks passed. Outage/restart/restore/alert drills were not run.'}
else{Write-Host 'Full-stack recovery drills passed.'}
Write-Host 'Isolated demo: http://localhost:4500; Grafana: http://localhost:3300; Prometheus: http://localhost:9390.'
Write-Host 'The backup contains local demo identity data. Keep .tmp-notes ignored; no live provider or production failover claim is made.'
