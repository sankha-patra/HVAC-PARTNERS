<#
.SYNOPSIS
Reviewed source-offset reset for a self-managed Kafka Connect endpoint.
.DESCRIPTION
This helper uses Kafka Connect REST, not the AWS MSK Connect APIs. It does not
implement managed MSK connector offset reset or RDS failover recovery. AWS
managed pause/resume operations preserve offsets and are a different procedure.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$ConnectUrl,
    [string]$Connector='billing-outbox',
    [Parameter(Mandatory)][switch]$ReviewedFullResnapshot,
    [switch]$LocalTest
)
$ErrorActionPreference='Stop'
if(!$ReviewedFullResnapshot){throw 'A retained-source and consumer-lag review is required before resetting source offsets'}
if($Connector -notmatch '^[a-zA-Z0-9_-]+$' -or $ConnectUrl.UserInfo -or $ConnectUrl.Query -or $ConnectUrl.Fragment){throw 'Invalid connector endpoint'}
if($ConnectUrl.Scheme -ne 'https' -and !($LocalTest -and $ConnectUrl.Scheme -eq 'http')){throw 'Use the private authenticated HTTPS connector endpoint'}
$endpoint=$ConnectUrl.AbsoluteUri.TrimEnd('/')+"/connectors/$Connector"
function Request([string]$Method,[string]$Path){Invoke-RestMethod -Method $Method -Uri "$endpoint$Path" -TimeoutSec 15 | Out-Null}
function Wait-State([string]$State){
    $deadline=(Get-Date).AddSeconds(180)
    while((Get-Date) -lt $deadline){
        try{$status=Invoke-RestMethod -Uri "$endpoint/status" -TimeoutSec 10;if($status.connector.state -eq $State -and ($State -eq 'STOPPED' -or (@($status.tasks).Count -gt 0 -and @($status.tasks | Where-Object state -ne $State).Count -eq 0))){return}}catch{}
        Start-Sleep -Seconds 2
    }
    throw "Connector did not reach $State; inspect it before further action"
}
Request PUT '/stop'
Wait-State STOPPED
# No replication slot is dropped here. If a slot was lost, Debezium recreates it
# and snapshots retained stream rows after this explicit source-offset reset.
Request DELETE '/offsets'
Request PUT '/resume'
Wait-State RUNNING
Write-Output 'Self-managed connector resumed from retained source. Verify CDC pending age, Kafka receipts and consumer effects before closing the incident.'
