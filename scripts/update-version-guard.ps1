# Pure validation: no network calls or publication.
function Assert-NewerUpdateManifest {
    param($Candidate, $Published)
    foreach ($appName in @('parent', 'child')) {
        $entry = $Candidate.apps.$appName
        $previous = $Published.apps.$appName
        if (-not $entry -or -not $previous) { throw "Missing $appName manifest entry" }
        $newCode = 0L; $oldCode = 0L
        if (-not [long]::TryParse([string]$entry.versionCode, [ref]$newCode) -or
            -not [long]::TryParse([string]$previous.versionCode, [ref]$oldCode) -or
            $newCode -le 0 -or $oldCode -le 0 -or $newCode -gt 2147483647) { throw "Invalid $appName version code" }
        if ($newCode -le $oldCode) { throw "$appName version $newCode is not newer than published $oldCode" }
        if ($entry.packageName -ne $previous.packageName) { throw "$appName package changed; this is not an in-place update" }
        if (-not $entry.signingCertSha256 -or $entry.signingCertSha256 -ne $previous.signingCertSha256) { throw "$appName certificate differs from published" }
    }
}
