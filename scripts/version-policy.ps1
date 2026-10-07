# Pure local version discovery. The caller must hold the Gradle mutex when
# reserving; plain reads never mutate files or contact devices/the server.
function ConvertTo-CwVersionCode {
    param($Value, [string]$Source)
    $code = 0L
    if ([string]$Value -notmatch '^[0-9]+$' -or
        -not [long]::TryParse([string]$Value, [ref]$code) -or
        $code -le 0 -or $code -gt 2100000000L) { throw "Invalid Android version code in $Source" }
    return $code
}
function Get-CwVersionState {
    param([string]$Root)
    $floor = Get-Content -LiteralPath (Join-Path $Root 'release-version.json') -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    $issued = ConvertTo-CwVersionCode $floor.minimumIssuedCode 'release-version.json'
    $built = 0L; $reserved = 0L
    $manifests = @(Get-ChildItem -LiteralPath (Join-Path $Root 'releases') -Filter manifest.json -File -Recurse -ErrorAction SilentlyContinue)
    $updates = Join-Path $Root 'updates/manifest.json'
    if (Test-Path -LiteralPath $updates) { $manifests += Get-Item -LiteralPath $updates }
    foreach ($file in $manifests) {
        $data = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        foreach ($appName in @('parent', 'child')) {
            $issued = [Math]::Max($issued, (ConvertTo-CwVersionCode $data.apps.$appName.versionCode $file.FullName))
        }
    }
    foreach ($path in @('app/build/outputs/apk', 'parentwatch/build/outputs/apk', '.codex-build', 'artifacts/android')) {
        $directory = Join-Path $Root $path
        if (-not (Test-Path -LiteralPath $directory)) { continue }
        foreach ($file in (Get-ChildItem -LiteralPath $directory -Filter output-metadata.json -File -Recurse)) {
            if ($file.FullName.Replace('\','/') -notlike '*/outputs/apk/*') { continue }
            $data = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
            if ($data.applicationId -notin @('ru.example.childwatch', 'ru.example.parentwatch', 'ru.example.parentwatch.debug')) { continue }
            foreach ($element in $data.elements) { $built = [Math]::Max($built, (ConvertTo-CwVersionCode $element.versionCode $file.FullName)) }
        }
    }
    $reservation = Join-Path $Root '.runtime/version-reservation.json'
    if (Test-Path -LiteralPath $reservation) {
        $data = Get-Content -LiteralPath $reservation -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        $reserved = ConvertTo-CwVersionCode $data.versionCode $reservation
    }
    $gitCount = 0L
    try {
        $countText = (& git -C $Root rev-list --count HEAD 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0) { [void][long]::TryParse($countText, [ref]$gitCount) }
    } catch { $gitCount = 0L }
    return @{ issued=$issued; built=$built; reserved=$reserved; gitCount=$gitCount }
}
function Resolve-CwVersionCode {
    param($State, $RequestedCode = $null)
    $highest = (@($State.issued, $State.built, $State.reserved, (2000000000L + [Math]::Max(0L,$State.gitCount))) | Measure-Object -Maximum).Maximum
    if ($null -ne $RequestedCode) {
        $code = ConvertTo-CwVersionCode $RequestedCode 'cwVersionCode'
        $retry = $code -eq $State.reserved -and $code -gt $State.issued -and $code -ge $State.built
        if ($code -le $highest -and -not $retry) { throw "Requested version $code must exceed known $highest; only the latest un-staged reservation can be retried" }
        return $code
    }
    if ($highest -ge 2100000000L) { throw 'Version code limit reached' }
    return [long]($highest + 1)
}
function Save-CwVersionReservation {
    param([string]$Root, [long]$Code)
    [void](ConvertTo-CwVersionCode $Code 'reservation')
    $directory = Join-Path $Root '.runtime'
    [void](New-Item -ItemType Directory -Path $directory -Force)
    $path = Join-Path $directory 'version-reservation.json'
    $temporary = "$path.tmp"
    $text = @{ versionCode=$Code; reservedAtUtc=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json
    [IO.File]::WriteAllText($temporary, $text, [Text.UTF8Encoding]::new($false))
    # Replace an existing state atomically; under the project mutex.
    if (Test-Path -LiteralPath $path) { [IO.File]::Replace($temporary, $path, "$path.bak") }
    else { [IO.File]::Move($temporary, $path) }
}
