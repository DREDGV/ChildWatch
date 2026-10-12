[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReleaseDirectory)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$release=[IO.Path]::GetFullPath($ReleaseDirectory)
$allowed=[IO.Path]::GetFullPath((Join-Path $root 'releases'))+[IO.Path]::DirectorySeparatorChar
if(-not $release.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Release must be inside workspace releases'}
$manifest=Get-Content -LiteralPath (Join-Path $release 'manifest.json') -Raw | ConvertFrom-Json
$version=$manifest.apps.parent.versionName
$patch=Join-Path $release 'server-patch'
if(Test-Path -LiteralPath $patch){throw 'Server package exists; inspect it instead of overwriting'}
$runtime=@('services/FamilyGameService.js','services/BattleshipRules.js','services/SnakeRules.js','services/SnakeSessionService.js','services/SnakeSocketService.js','services/ArcanoidRules.js','services/ArcanoidSessionService.js','services/ArcanoidSocketService.js','routes/family-games.js')
$reference=@('index.js','services/DeviceAccessService.js','middleware/SocketAuthMiddleware.js','validators/DataValidator.js','auth/AuthManager.js','database/DatabaseManager.js','package.json','package-lock.json')
$records=@()
function New-PortableZip([string]$Archive,[string]$Base,[string[]]$Files){
 Add-Type -AssemblyName System.IO.Compression
 $stream=[IO.File]::Open($Archive,[IO.FileMode]::CreateNew)
 $zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
 try{
  foreach($file in $Files){
   $relative=[IO.Path]::GetFullPath($file).Substring($Base.Length).TrimStart('\','/').Replace('\','/')
   $entry=$zip.CreateEntry($relative,[IO.Compression.CompressionLevel]::Optimal)
   $input=[IO.File]::OpenRead($file);$output=$entry.Open()
   try{$input.CopyTo($output)}finally{$output.Dispose();$input.Dispose()}
  }
 }finally{$zip.Dispose();$stream.Dispose()}
}
foreach($kind in @('server','reference')){
 $files=if($kind -eq 'server'){$runtime}else{$reference}
 foreach($relative in $files){
  $source=Join-Path $root ('server/'+$relative);$destination=Join-Path $patch ($kind+'/'+$relative)
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination)|Out-Null
  Copy-Item -LiteralPath $source -Destination $destination
  $records+=@{path=$kind+'/'+$relative;sizeBytes=(Get-Item -LiteralPath $destination).Length;sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant();purpose=$kind}
 }
}
$sourceCommit=(& git -C $root rev-parse HEAD|Out-String).Trim()
$utf8=[Text.UTF8Encoding]::new($false)
$readme=[IO.File]::ReadAllText((Join-Path $root 'docs/family-games-server-deploy-2026-10-12.md')).Replace('__VERSION__',$version).Replace('__SOURCE__',$sourceCommit)
[IO.File]::WriteAllText((Join-Path $patch 'README-DEPLOY.md'),$readme,$utf8)
$proof=@{schema=1;version=$version;sourceCommit=$sourceCommit;runtimeCount=$runtime.Count;files=$records;dataIncluded=$false;newNpmDependencies=$false}
[IO.File]::WriteAllText((Join-Path $patch 'verification.json'),($proof|ConvertTo-Json -Depth 7),$utf8)
$sums=$records|ForEach-Object{"$($_.sha256)  $($_.path)"}
[IO.File]::WriteAllLines((Join-Path $patch 'SHA256SUMS.txt'),[string[]]$sums,$utf8)
New-PortableZip (Join-Path $release 'server-patch.zip') $release @((Get-ChildItem -LiteralPath $patch -File -Recurse).FullName)
$otaFiles=@((Join-Path $release 'manifest.json'))
foreach($name in @('parent','child')){
 $entry=$manifest.apps.$name;$file=Join-Path $release $entry.file
 if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256){throw 'OTA hash mismatch'}
 $otaFiles+=$file
}
New-PortableZip (Join-Path $release 'ota.zip') $release $otaFiles
$archiveSums=@('server-patch.zip','ota.zip')|ForEach-Object{ $file=Join-Path $release $_; "$((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant())  $_" }
[IO.File]::WriteAllLines((Join-Path $release 'ARCHIVE-SHA256SUMS.txt'),[string[]]$archiveSums,$utf8)
Write-Host "Prepared ${version}: runtime=$($runtime.Count), references=$($reference.Count); no upload/install"
