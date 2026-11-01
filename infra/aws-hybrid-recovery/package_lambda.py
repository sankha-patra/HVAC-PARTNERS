"""Package the billing Spring Boot JAR as the recovery Lambda zip (classes + lib/). No upload or AWS calls.

Usage: python package_lambda.py --application-jar billing-service/target/billing-service-*.jar --tailscale-layer <tailscale.zip> --output <zip>
"""
from pathlib import Path
import argparse, hashlib, json, stat, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--application-jar',required=True,type=Path)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--tailscale-layer',type=Path,required=True)
args=parser.parse_args()
jar=args.application_jar.resolve()
if jar.suffix!='.jar' or not jar.is_file():raise SystemExit('A packaged verified application JAR is required')
files={}
with zipfile.ZipFile(jar) as source:
    for entry in source.infolist():
        if entry.is_dir():continue
        name=entry.filename
        if name.startswith('BOOT-INF/classes/'):
            target=name.removeprefix('BOOT-INF/classes/')
        elif name.startswith('BOOT-INF/lib/'):
            target='lib/'+name.removeprefix('BOOT-INF/lib/')
        else:continue
        if '..' in Path(target).parts or target.startswith('/') or '.env' in Path(target).parts:raise SystemExit('Unexpected application archive entry')
        files[target]=source.read(entry)
required='com/invoicepayments/billing/recovery/RecoveryLambdaHandler.class'
if required not in files:raise SystemExit('Built JAR does not include the new recovery handler')
if any('/test/' in name or name.endswith('Test.class') for name in files):raise SystemExit('Test classes must not enter the managed package')
if not any(name.startswith('lib/ssm-') for name in files):raise SystemExit('Runtime SSM dependency is missing')
if 'recovery-logback.xml' not in files:raise SystemExit('Lambda logging configuration is missing (debug logging would expose secrets)')
total=sum(map(len,files.values()))
layer=args.tailscale_layer.resolve()
if not layer.is_file():raise SystemExit('Verified native private-tunnel layer is required')
with zipfile.ZipFile(layer) as z:layer_unzipped=sum(entry.file_size for entry in z.infolist())
if total+layer_unzipped>250*1024*1024:raise SystemExit('Combined Lambda code and layer exceed unzipped size limit')
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as out:
    for name,content in sorted(files.items()):
        info=zipfile.ZipInfo(name,(2026,11,1,0,0,0));info.create_system=3;info.external_attr=(stat.S_IFREG|0o644)<<16;info.compress_type=zipfile.ZIP_DEFLATED
        out.writestr(info,content)
manifest={'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'zip_sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),
    'zip_bytes':args.output.stat().st_size,'unzipped_bytes':total,'layer_sha256':hashlib.sha256(layer.read_bytes()).hexdigest(),
    'combined_unzipped_bytes':total+layer_unzipped,'file_sha256':{name:hashlib.sha256(content).hexdigest() for name,content in sorted(files.items())},
    'handler':'com.invoicepayments.billing.recovery.RecoveryLambdaHandler::handleRequest','uploaded':False,'deployed':False,'secret_values_read':False,
    'limitation':'Packaging records binary identity; acceptance evidence must separately freeze and match the tested application source and dependencies.'}
args.output.with_suffix('.manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in manifest.items() if k!='file_sha256'},indent=2))
