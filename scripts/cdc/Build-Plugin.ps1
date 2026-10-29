[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AwsProviderArchive,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$AwsProviderSha256,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
# Obtain r0.4.0 from https://github.com/aws-samples/msk-config-providers/releases/tag/r0.4.0
# Supply a reviewed checksum; the script never executes downloaded shell code or uploads to AWS.
$archive=(Resolve-Path -LiteralPath $AwsProviderArchive).Path
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $AwsProviderSha256){throw 'AWS provider archive checksum mismatch'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'Choose a new output directory to avoid mixing plugin versions'}
$output=[IO.Path]::GetFullPath($OutputDirectory)
$bundle=Join-Path $output 'plugin'
$postgresPlugin=Join-Path $bundle 'debezium-postgres'
$awsPlugin=Join-Path $bundle 'aws-config-providers'
New-Item -ItemType Directory -Path $postgresPlugin,$awsPlugin -Force | Out-Null
$image='quay.io/debezium/connect:3.6.3.Final@sha256:bb86eca40edbd3b9aaf52b60115f4d01abdc3f86d8f058c2022a8400ea07a275'
$container=(& docker create $image).Trim()
if($LASTEXITCODE -ne 0 -or $container -notmatch '^[a-f0-9]{12,64}$'){throw 'Cannot create pinned plugin extraction container'}
try {
    & docker cp "${container}:/kafka/connect/debezium-connector-postgres/." $postgresPlugin
    if($LASTEXITCODE -ne 0){throw 'PostgreSQL plugin extraction failed'}
} finally {& docker rm $container | Out-Null}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach($entry in $zip.Entries){
        # Only jars are needed; reject absolute/traversal paths before flattening.
        if($entry.FullName -match '(^[/\\]|(^|[/\\])\.\.([/\\]|$)|:)'){throw 'Unsafe provider archive path'}
        if($entry.Name.EndsWith('.jar',[StringComparison]::OrdinalIgnoreCase)){
            $destination=Join-Path $awsPlugin $entry.Name
            if(Test-Path -LiteralPath $destination){throw "Duplicate plugin dependency: $($entry.Name)"}
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$false)
        }
    }
} finally {$zip.Dispose()}
if(!(Get-ChildItem -LiteralPath $awsPlugin -Filter '*msk-config-providers*.jar')){throw 'The archive did not contain the AWS config provider'}
$pluginZip=Join-Path $output 'billing-cdc-plugin.zip'
Compress-Archive -Path (Join-Path $bundle '*') -DestinationPath $pluginZip
$manifest=@{debeziumImage=$image;awsProviderVersion='0.4.0';awsProviderSha256=$AwsProviderSha256.ToLowerInvariant();pluginSha256=(Get-FileHash -LiteralPath $pluginZip -Algorithm SHA256).Hash.ToLowerInvariant();jars=@(Get-ChildItem -LiteralPath $bundle -Filter '*.jar' -Recurse | Sort-Object FullName | ForEach-Object{@{name=$_.FullName.Substring($bundle.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
Write-Output "Built $pluginZip. Register the verified archive as an MSK Connect custom plugin before enabling CDC."
