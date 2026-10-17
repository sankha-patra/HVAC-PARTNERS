# No AWS calls: this function shadows the CLI for the helper's account/backup boundaries.
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$helper = Join-Path $repositoryRoot 'scripts/backup-billing.ps1'
$global:billingBackupTestAccount = '123456789012'
$global:billingBackupTestEncrypted = $true
$global:billingBackupTestCreateCalls = 0
function global:aws {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'sts') { return $global:billingBackupTestAccount }
    if ($args[1] -eq 'describe-db-instances') {
        return (@{ Encrypted = $global:billingBackupTestEncrypted; Status = 'available' } | ConvertTo-Json)
    }
    if ($args[1] -eq 'create-db-snapshot') {
        $global:billingBackupTestCreateCalls++
        return '{"Identifier":"mock-snapshot","Status":"creating"}'
    }
    throw 'Unexpected AWS operation in backup test.'
}
try {
    & $helper -ExpectedAccountId '123456789012' -DatabaseIdentifier 'test-billing' -WhatIf
    if ($global:billingBackupTestCreateCalls -ne 0) { throw 'WhatIf requested a snapshot.' }

    $mismatchRejected = $false
    try { & $helper -ExpectedAccountId '999999999999' -DatabaseIdentifier 'test-billing' } catch {
        if ($_.Exception.Message -notlike '*account mismatch*') { throw }
        $mismatchRejected = $true
    }
    if (-not $mismatchRejected -or $global:billingBackupTestCreateCalls -ne 0) { throw 'Wrong account was not safely rejected.' }

    $global:billingBackupTestEncrypted = $false
    $unencryptedRejected = $false
    try { & $helper -ExpectedAccountId '123456789012' -DatabaseIdentifier 'test-billing' } catch {
        if ($_.Exception.Message -notlike '*not encrypted*') { throw }
        $unencryptedRejected = $true
    }
    if (-not $unencryptedRejected -or $global:billingBackupTestCreateCalls -ne 0) { throw 'Unencrypted source was not safely rejected.' }

    $global:billingBackupTestEncrypted = $true
    & $helper -ExpectedAccountId '123456789012' -DatabaseIdentifier 'test-billing' -Confirm:$false
    if ($global:billingBackupTestCreateCalls -ne 1) { throw 'Expected exactly one simulated snapshot request.' }
    Write-Output 'Backup helper: 4 mocked boundary checks passed; no AWS requests.'
} finally {
    Remove-Item Function:\aws -ErrorAction SilentlyContinue
    Remove-Variable billingBackupTestAccount, billingBackupTestEncrypted, billingBackupTestCreateCalls -Scope Global -ErrorAction SilentlyContinue
}
