#!/bin/bash
# Publishes staged releases into the folder the server serves updates from.
set -euo pipefail
DIR=$(pm2 env 0 2>/dev/null | grep -o 'CW_UPDATE_DIR=.*' | cut -d= -f2- || true)
if [ -z "$DIR" ]; then DIR=/var/www/childwatch-updates; fi
echo "update directory: $DIR"
mkdir -p "$DIR"
mkdir -p /tmp/cw-updates-in
# Serialize the final version check and manifest replacement.
exec 9>"$DIR/.publish.lock"
flock -x 9
node - "$DIR" /tmp/cw-updates-in <<'NODE'
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const [dir,input]=process.argv.slice(2);
const previous=JSON.parse(fs.readFileSync(path.join(dir,'manifest.json'),'utf8'));
const next=JSON.parse(fs.readFileSync(path.join(input,'manifest.json'),'utf8'));
for(const name of ['parent','child']) {
 const a=next.apps?.[name],b=previous.apps?.[name];
 if(!a||!b||!Number.isSafeInteger(a.versionCode)||!Number.isSafeInteger(b.versionCode)||a.versionCode<=b.versionCode||a.versionCode>2147483647) throw Error(name+': version must be newer');
 if(a.packageName!==b.packageName||!a.signingCertSha256||a.signingCertSha256!==b.signingCertSha256) throw Error(name+': package/certificate mismatch');
 if(typeof a.file!=='string'||path.basename(a.file)!==a.file||!a.file.endsWith('.apk')) throw Error('invalid APK name');
 const file=fs.readFileSync(path.join(input,a.file));
 const hash=crypto.createHash('sha256').update(file).digest('hex');
 if(hash!==a.sha256||file.length!==a.sizeBytes) throw Error(name+': staged bytes do not match manifest');
 const dest=path.join(dir,a.file);
 if(fs.existsSync(dest)&&crypto.createHash('sha256').update(fs.readFileSync(dest)).digest('hex')!==hash) throw Error('refusing to overwrite a different APK with the same name');
}
NODE
# APKs first; atomic manifest switch last. Never expose a partial manifest.
while IFS= read -r file; do
 cp "/tmp/cw-updates-in/$file" "$DIR/$file.pending"
 chown adminuser:adminuser "$DIR/$file.pending"
 mv "$DIR/$file.pending" "$DIR/$file"
done < <(node -e 'const m=require("/tmp/cw-updates-in/manifest.json"); for(const n of ["parent","child"]) console.log(m.apps[n].file)')
cp /tmp/cw-updates-in/manifest.json "$DIR/manifest.json.pending"
chown adminuser:adminuser "$DIR/manifest.json.pending"
mv "$DIR/manifest.json.pending" "$DIR/manifest.json"
echo
echo "=== published files ==="
ls -l "$DIR"
echo
echo "=== manifest as served ==="
curl -s http://localhost:3000/updates/manifest > /tmp/cw-manifest-served.json
head -c 900 /tmp/cw-manifest-served.json
echo
