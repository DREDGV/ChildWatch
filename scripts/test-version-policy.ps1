[CmdletBinding()]
param([string]$Java = '', [string]$GroovyLib = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'version-policy.ps1')
$fixture = Join-Path $repo '.runtime/version-policy-tests'
[void](New-Item -ItemType Directory -Path $fixture -Force)
$cases = @(
    @{ name='clone-no-git'; state=@{issued=2000000288L;built=0L;reserved=0L;gitCount=0L}; expected=2000000289L },
    @{ name='shallow-git'; state=@{issued=2000000288L;built=0L;reserved=0L;gitCount=1L}; expected=2000000289L },
    @{ name='git-overtakes'; state=@{issued=2000000288L;built=0L;reserved=0L;gitCount=400L}; expected=2000000401L },
    @{ name='manual-metadata'; state=@{issued=2000000288L;built=2000000350L;reserved=0L;gitCount=285L}; expected=2000000351L },
    @{ name='published'; state=@{issued=2000000400L;built=2000000350L;reserved=0L;gitCount=285L}; expected=2000000401L },
    @{ name='failed-reservation'; state=@{issued=2000000288L;built=2000000288L;reserved=2000000289L;gitCount=285L}; expected=2000000290L },
    @{ name='retry-partial'; state=@{issued=2000000288L;built=2000000289L;reserved=2000000289L;gitCount=285L}; requested=2000000289L; expected=2000000289L },
    @{ name='explicit-new'; state=@{issued=2000000288L;built=2000000288L;reserved=0L;gitCount=285L}; requested=2000000350L; expected=2000000350L },
    @{ name='reject-staged'; state=@{issued=2000000289L;built=2000000289L;reserved=2000000289L;gitCount=285L}; requested=2000000289L; reject=$true },
    @{ name='reject-older'; state=@{issued=2000000288L;built=2000000350L;reserved=0L;gitCount=285L}; requested=2000000289L; reject=$true },
    @{ name='reject-retry-behind-built'; state=@{issued=2000000288L;built=2000000290L;reserved=2000000289L;gitCount=285L}; requested=2000000289L; reject=$true },
    @{ name='reject-invalid'; state=@{issued=2000000288L;built=0L;reserved=0L;gitCount=0L}; requested='garbage'; reject=$true },
    @{ name='reject-zero'; state=@{issued=2000000288L;built=0L;reserved=0L;gitCount=0L}; requested=0; reject=$true },
    @{ name='reject-overflow'; state=@{issued=2000000288L;built=0L;reserved=0L;gitCount=0L}; requested=2100000001L; reject=$true },
    @{ name='exhausted'; state=@{issued=2100000000L;built=0L;reserved=0L;gitCount=0L}; reject=$true }
)
foreach ($case in $cases) {
    $actual = $null; $failed = $false
    try { $actual = Resolve-CwVersionCode $case.state $case.requested } catch { $failed = $true }
    if ($case.reject) { if (-not $failed) { throw "Expected rejection: $($case.name)" } }
    elseif ($failed -or $actual -ne $case.expected) { throw "Wrong result: $($case.name), $actual" }
}
function Write-FixtureJson($Relative, $Value) {
    $path = Join-Path $fixture $Relative
    [void](New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force)
    [IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
}
Write-FixtureJson 'release-version.json' @{minimumIssuedCode=2000000288L}
Write-FixtureJson 'releases/fixture/manifest.json' @{apps=@{parent=@{versionCode=2000000300L};child=@{versionCode=2000000301L}}}
Write-FixtureJson 'app/build/outputs/apk/debug/output-metadata.json' @{applicationId='ru.example.childwatch';elements=@(@{versionCode=2000000350L})}
Save-CwVersionReservation $fixture 2000000351L
$before = (Get-FileHash (Join-Path $fixture '.runtime/version-reservation.json')).Hash
$state = Get-CwVersionState $fixture
if ($state.issued -ne 2000000301L -or $state.built -ne 2000000350L -or $state.reserved -ne 2000000351L) { throw 'Discovery failed' }
for ($i=0;$i -lt 3;$i++) { if ((Resolve-CwVersionCode (Get-CwVersionState $fixture)) -ne 2000000352L) { throw 'Unstable configuration' } }
if ($before -ne (Get-FileHash (Join-Path $fixture '.runtime/version-reservation.json')).Hash) { throw 'Read changed reservation' }
Save-CwVersionReservation $fixture 2000000352L
if ((Get-CwVersionState $fixture).reserved -ne 2000000352L) { throw 'Atomic replacement failed' }
# Shared vectors exercise Groovy independently of Gradle and check parity.
Write-FixtureJson 'vectors.json' $cases
if (-not $Java) { $Java = (Get-Command java -ErrorAction Stop).Source }
if (-not $GroovyLib) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo '.gradle-agent-home/wrapper/dists') -Filter 'groovy-4*.jar' -Recurse | Select-Object -First 1
    if (-not $jar) { throw 'Pass -GroovyLib with a directory containing Groovy jars' }
    $GroovyLib = $jar.DirectoryName
}
& $Java -cp (Join-Path $GroovyLib '*') groovy.ui.GroovyMain (Join-Path $PSScriptRoot 'test-version-policy.groovy') $repo $fixture
if ($LASTEXITCODE -ne 0) { throw 'Groovy policy failed' }
# Exercise the safe runner/mutex with a fixture wrapper that only echoes args.
# It cannot invoke Gradle, ADB or build APKs.
[void](New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force)
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run-gradle-safe.ps1') -Destination (Join-Path $fixture 'scripts/run-gradle-safe.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'version-policy.ps1') -Destination (Join-Path $fixture 'scripts/version-policy.ps1') -Force
[IO.File]::WriteAllText((Join-Path $fixture 'gradlew.bat'), "@echo off`r`necho FixtureWrapper %*`r`nexit /b 0`r`n", [Text.ASCIIEncoding]::new())
$runner = Join-Path $fixture 'scripts/run-gradle-safe.ps1'
$before = (Get-FileHash (Join-Path $fixture '.runtime/version-reservation.json')).Hash
$output = & powershell -NoProfile -ExecutionPolicy Bypass -File $runner :app:compileDebugKotlin :parentwatch:compileDebugKotlin 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $before -ne (Get-FileHash (Join-Path $fixture '.runtime/version-reservation.json')).Hash) { throw 'Compile runner reserved a number' }
$output = & powershell -NoProfile -ExecutionPolicy Bypass -File $runner :app:assembleDebug :parentwatch:assembleDebug 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or ([regex]::Matches($output, '-PcwVersionCode=2000000353')).Count -ne 1 -or (Get-CwVersionState $fixture).reserved -ne 2000000353L) { throw 'Both APK tasks did not share one reservation' }
$output = & powershell -NoProfile -ExecutionPolicy Bypass -File $runner :app:assembleDebug :parentwatch:assembleDebug -PcwVersionCode=2000000353 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or (Get-CwVersionState $fixture).reserved -ne 2000000353L) { throw 'Explicit retry failed' }
Write-FixtureJson 'releases/fixture/manifest.json' @{apps=@{parent=@{versionCode=2000000353L};child=@{versionCode=2000000353L}}}
$ErrorActionPreference = 'Continue'
$output = & powershell -NoProfile -ExecutionPolicy Bypass -File $runner :app:assembleDebug -PcwVersionCode=2000000353 2>&1 | Out-String
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -eq 0 -or $output -like '*FixtureWrapper*') { throw 'Staged retry reached the packaging wrapper' }
$manifest = Join-Path $fixture 'releases/fixture/manifest.json'
[IO.File]::WriteAllText($manifest,'{"apps":{"parent":{"versionCode":"bad"}}}')
$failed = $false; try { Get-CwVersionState $fixture | Out-Null } catch { $failed = $true }
if (-not $failed) { throw 'Corrupt manifest was silently ignored' }
Write-Host "PASS: $($cases.Count) PowerShell policy vectors, discovery/read stability/atomic reservation/corrupt-manifest rejection/Groovy parity; fixture safe runner compile/both-APK reservation/retry/staged refusal. No real reservation was changed."
