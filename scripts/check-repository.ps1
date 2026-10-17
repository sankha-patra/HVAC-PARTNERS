[CmdletBinding()]
param([switch]$CheckArtifacts)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$gitPath=Join-Path $root '.git'
if(!(Test-Path -LiteralPath $gitPath -PathType Container)){throw 'Expected an independent .git directory, not an attached worktree'}
$top=(& git -C $root rev-parse --show-toplevel).Trim()
if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($top) -ine $root){throw 'Git root does not match this project'}
$tracked=@(& git -C $root ls-files)
if($LASTEXITCODE -ne 0 -or !$tracked.Count){throw 'No tracked source files to audit'}
foreach($file in $tracked){
    if($file -match '(^|/)(target|node_modules|dist|\.tmp-notes|\.terraform|\.maven-cache)/|(^|/)\.env(?!\.example$)|\.tfstate|\.dump$|\.gitmodules$'){throw "Generated/private/nested repository file tracked: $file"}
    $item=Get-Item -LiteralPath (Join-Path $root $file)
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Linked source file: $file"}
}
$modules=@('identity-service','billing-service','api-gateway')
foreach($module in $modules){
    $pom=Get-Content -LiteralPath (Join-Path $root "$module/pom.xml") -Raw
    if($pom -match '(?i)razorpay|redis|eureka|dynamodb|ebusiness|\.\./\.\./'){throw "Legacy/external source dependency in $module"}
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $root "$module/src/main") -File -Recurse){
        if(($file.FullName -match '\.java$|\.ya?ml$|\.properties$') -and (Get-Content -LiteralPath $file.FullName -Raw) -match 'com\.ebusiness|APP_MODE|ebusiness-platform|payment-service|checkout\.razorpay'){throw "Legacy runtime reference: $($file.FullName)"}
    }
}
foreach($directory in @('backend','payment-service','frontend/src/app/products','frontend/src/app/orders','frontend/src/app/payments','frontend/src/app/notifications')){
    if(Test-Path -LiteralPath (Join-Path $root $directory)){throw "Legacy source included: $directory"}
}
$manifest=Get-Content -LiteralPath (Join-Path $root 'docs/migration-checksums.json') -Raw | ConvertFrom-Json
if(@($manifest).Count -ne 9){throw 'Expected nine preserved migrations'}
foreach($entry in $manifest){
    $module=if($entry.schema -ceq 'billing'){'billing-service'}elseif($entry.schema -ceq 'identity'){'identity-service'}else{throw 'Unexpected migration schema'}
    $path=Join-Path $root "$module/src/main/resources/db/$($entry.schema)/$($entry.file)"
    if(!(Test-Path -LiteralPath $path)){$path=Join-Path $root "$module/src/main/resources/db/migration/$($entry.file)"}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine $entry.sha256){throw "Migration bytes changed: $($entry.file)"}
}
if($CheckArtifacts){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach($module in $modules){
        $jar=Join-Path $root "$module/target/$module-1.0.0.jar"
        $zip=[IO.Compression.ZipFile]::OpenRead($jar)
        try{
            $bad=@($zip.Entries | Where-Object {$_.FullName -match 'BOOT-INF/classes/com/ebusiness/|BOOT-INF/lib/.*(razorpay|redis|eureka|dynamodb)|BOOT-INF/classes/.*/(ProductController|OrderController|PaymentController)\.class$'})
            if($bad.Count){throw "Legacy runtime classes/libraries packaged in $module"}
        }finally{$zip.Dispose()}
    }
}
Write-Host "PASS: independent Git root; tracked source boundary; nine unchanged migrations; artifacts=$CheckArtifacts"
