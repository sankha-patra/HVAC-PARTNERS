[CmdletBinding()]
param([switch]$ValidateOnly,[switch]$DedicatedWorkers,[switch]$Cdc)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$settings=Join-Path $projectRoot '.env.billing.local'
if(!(Test-Path -LiteralPath $settings)) {
    function New-BillingLocalSecret {
        $bytes=New-Object byte[] 48
        $random=[System.Security.Cryptography.RandomNumberGenerator]::Create()
        try {$random.GetBytes($bytes)} finally {$random.Dispose()}
        return [Convert]::ToBase64String($bytes)
    }
    $lines=@(
        "BILLING_DB_PASSWORD=$(New-BillingLocalSecret)",
        "BILLING_JWT_SECRET=$(New-BillingLocalSecret)",
        "BILLING_GRAFANA_PASSWORD=$(New-BillingLocalSecret)",
        'BILLING_PROVIDER=SIMULATOR'
    )
    [System.IO.File]::WriteAllLines($settings,$lines,[System.Text.UTF8Encoding]::new($false))
    Write-Host 'Created ignored local billing secrets. Values are not printed.'
}
$compose=@('compose','--project-name','invoice-payments','--env-file',$settings,'-f',(Join-Path $projectRoot 'docker-compose.billing.yml'))
if($DedicatedWorkers -or $Cdc){$compose+=@('-f',(Join-Path $projectRoot 'docker-compose.workers.yml'))}
if($Cdc){$compose+=@('-f',(Join-Path $projectRoot 'docker-compose.cdc.yml'))}
& docker @compose config --quiet
if($LASTEXITCODE -ne 0) {throw 'Billing Compose configuration is invalid'}
if($ValidateOnly) {Write-Host 'Billing configuration is valid. No containers started.';return}
& docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
if($LASTEXITCODE -ne 0) {throw 'Start Docker Desktop, then run this script again. No existing application data was changed.'}
& docker @compose up -d --build
if($LASTEXITCODE -ne 0) {throw 'Billing stack startup failed; inspect the Compose logs'}
Write-Host 'Invoice payments: http://localhost:4400 | Grafana: http://localhost:3200'
Write-Host 'Local users: demo/demo123, other/other123, admin/admin123 (Carrier finance), reviewer/reviewer123 (Carrier approver), support/support123 (application team).'
Write-Host 'These explicitly enabled demo identities never run under the prod profile.'

