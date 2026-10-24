[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$compose=@('compose','--project-name','invoice-payments-tests','-f',(Join-Path $root 'docker-compose.tests.yml'))
$stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$directory=Join-Path $root ".tmp-notes/container-tests/$stamp"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$exitCode=1
# Windows PowerShell 5.1 turns a native command's stderr into a terminating error under 'Stop', and Docker
# writes its progress to stderr. Native calls therefore run under 'Continue' and are judged by exit code.
function Invoke-Native([scriptblock]$Command){
    $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
    try{& $Command}finally{$ErrorActionPreference=$previous}
}
try{
    $up=@('up','--abort-on-container-exit','--exit-code-from','tests')
    if(!$SkipBuild){$up+='--build'}
    Invoke-Native {& docker @compose @up *> (Join-Path $directory 'test.log')}
    $exitCode=$LASTEXITCODE
    $id=([string](Invoke-Native {& docker @compose ps -a -q tests 2>$null})).Trim()
    if($id -notmatch '^[a-f0-9]{12,64}$'){throw 'Expected exactly one verification container'}
    foreach($module in @('identity-service','billing-service')){
        Invoke-Native {& docker cp "${id}:/source/$module/target/surefire-reports" (Join-Path $directory $module) *> $null}
        if($LASTEXITCODE -ne 0){throw "Could not collect $module test evidence"}
    }
    Invoke-Native {& docker cp "${id}:/source/billing-service/target/billing-lab-report.json" (Join-Path $directory 'billing-lab-report.json') *> $null}
    if($LASTEXITCODE -ne 0 -and $exitCode -eq 0){throw 'Missing synthetic lab report'}
    if($exitCode -ne 0){throw "Container tests failed; inspect $directory/test.log"}
    $totals=@{tests=0;failures=0;errors=0;skipped=0}
    foreach($report in Get-ChildItem -LiteralPath $directory -Recurse -Filter 'TEST-*.xml'){
        [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
        foreach($key in @('tests','failures','errors','skipped')){$totals[$key]+=[int]$xml.testsuite.GetAttribute($key)}
    }
    if($totals.tests -lt 100 -or $totals.failures -or $totals.errors -or $totals.skipped){throw 'Incomplete or failed verification suite'}
    $totals | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'summary.json') -Encoding utf8
    Write-Host "PASS: $($totals.tests) Java tests; no failures, errors or skips. Evidence: $directory"
}finally{
    # This fixed project contains only disposable test containers, not application volumes.
    Invoke-Native {& docker @compose down --remove-orphans *> (Join-Path $directory 'cleanup.log')}
}
