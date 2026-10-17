[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]{12}$')]
    [string]$ExpectedAccountId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z][a-z0-9-]{2,62}$')]
    [string]$DatabaseIdentifier,

    [ValidateSet('us-east-1', 'us-east-2', 'us-west-2')]
    [string]$Region = 'us-east-1',

    [string]$AwsProfile,
    [switch]$Wait
)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    throw 'AWS CLI v2 is required. Sign in with the authorized backup operator role first.'
}
$commonArguments = @('--region', $Region, '--no-cli-pager')
if ($AwsProfile) { $commonArguments += @('--profile', $AwsProfile) }
$actualAccount = & aws sts get-caller-identity @commonArguments --query Account --output text
if ($LASTEXITCODE -ne 0) { throw 'Could not verify the AWS account.' }
if ($actualAccount.Trim() -ne $ExpectedAccountId) { throw 'AWS account mismatch; no snapshot was requested.' }

$description = & aws rds describe-db-instances @commonArguments --db-instance-identifier $DatabaseIdentifier --query 'DBInstances[0].{Encrypted:StorageEncrypted,Status:DBInstanceStatus}' --output json
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the selected database.' }
$database = $description | ConvertFrom-Json
if (-not $database.Encrypted) { throw 'The selected database is not encrypted; no snapshot was requested.' }
if ($database.Status -ne 'available') { throw "Database status is $($database.Status); retry the backup when it is available." }

$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$snapshotIdentifier = "$DatabaseIdentifier-manual-$timestamp"
if ($PSCmdlet.ShouldProcess("$ExpectedAccountId/$Region/$DatabaseIdentifier", "Create encrypted RDS snapshot $snapshotIdentifier")) {
    & aws rds create-db-snapshot @commonArguments --db-instance-identifier $DatabaseIdentifier --db-snapshot-identifier $snapshotIdentifier --tags Key=Purpose,Value=billing-operator-backup Key=RetentionReview,Value=30-days --query 'DBSnapshot.{Identifier:DBSnapshotIdentifier,Status:Status}' --output json
    if ($LASTEXITCODE -ne 0) { throw 'RDS rejected the snapshot request.' }
    if ($Wait) {
        & aws rds wait db-snapshot-available @commonArguments --db-snapshot-identifier $snapshotIdentifier
        if ($LASTEXITCODE -ne 0) { throw "Snapshot waiter did not finish successfully. Inspect $snapshotIdentifier; do not create duplicate backups blindly." }
    }
    Write-Output "Snapshot requested: $snapshotIdentifier. Manual snapshots are retained until an authorized operator removes them; the retention tag is not an automatic deletion policy."
}
