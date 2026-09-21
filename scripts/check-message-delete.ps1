# Checks that removing a message works on the connected parent phone.
# Steps are deliberately small: each one is verified before the next, so a failure
# is reported at the step that caused it instead of at the end.
$ErrorActionPreference = 'Continue'
$repo = 'C:\Users\dr-ed\ChildWatch'
$sdkLine = Get-Content (Join-Path $repo 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' }
$sdk = ($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$sam = 'R5CX71CKKJW'

function Say($m) { Write-Output $m }

function Dump($name) {
    & $adb -s $sam shell uiautomator dump "/sdcard/$name.xml" 2>&1 | Out-Null
    return (& $adb -s $sam shell cat "/sdcard/$name.xml" 2>&1) | Out-String
}

function TapNode($xml, $pattern, $label) {
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) { Say "   NOT FOUND: $label"; return $false }
    $g = $m.Groups
    $x = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
    $y = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
    Say "   tap $label at ($x,$y)"
    & $adb -s $sam shell input tap $x $y 2>&1 | Out-Null
    return $true
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 2

Say '0. closing the notification shade'
& $adb -s $sam shell input keyevent KEYCODE_ESCAPE 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $sam shell input keyevent KEYCODE_HOME 2>&1 | Out-Null
Start-Sleep -Seconds 2

Say '1. starting the application'
& $adb -s $sam shell am force-stop ru.example.childwatch 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $sam shell logcat -c 2>&1 | Out-Null
& $adb -s $sam shell monkey -p ru.example.childwatch -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Start-Sleep -Seconds 14

$xml = Dump 'h'
Say '2. opening the conversation list'
if (-not (TapNode $xml 'resource-id="[^"]*chatCard"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' 'chat card')) { exit 1 }
Start-Sleep -Seconds 7

$xml = Dump 'l'
Say '3. opening the family conversation'
if (-not (TapNode $xml '<node[^>]*text="Семья"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' 'conversation')) { exit 1 }
Start-Sleep -Seconds 8

$xml = Dump 'c'
$inputId = [regex]::Match($xml, 'resource-id="([^"]*messageInput)"').Groups[1].Value
$sendId = [regex]::Match($xml, 'resource-id="([^"]*sendButton)"').Groups[1].Value
Say "4. composer input='$inputId' send='$sendId'"
if (-not $inputId) {
    Say '   the composer input was not found; identifiers on this screen:'
    $ids = [regex]::Matches($xml, 'resource-id="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    $ids | Select-Object -First 25 | ForEach-Object { Say ('     ' + $_) }
    $texts = [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ }
    Say ('   visible text: ' + (($texts | Select-Object -First 10) -join ' | '))
    exit 1
}

$m = [regex]::Match($xml, ('resource-id="' + [regex]::Escape($inputId) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'))
if (-not $m.Success) { Say '   the input has no bounds'; exit 1 }
$g = $m.Groups
$ix = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
$iy = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
& $adb -s $sam shell input tap $ix $iy 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $sam shell input text 'ProverkaUdaleniya' 2>&1 | Out-Null
Start-Sleep -Seconds 2

$xml = Dump 's'
Say '5. sending the message'
if (-not (TapNode $xml ('resource-id="' + [regex]::Escape($sendId) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') 'send')) { Say '   send button missing'; exit 1 }
Start-Sleep -Seconds 8

Say '6. long press on the newest message'
$xml = Dump 'm'
$msgs = [regex]::Matches($xml, 'resource-id="[^"]*messageText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
Say "   messages on screen: $($msgs.Count)"
if ($msgs.Count -eq 0) { Say '   nothing to act on'; exit 1 }
$g = $msgs[$msgs.Count - 1].Groups
$mx = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
$my = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
& $adb -s $sam shell input swipe $mx $my $mx $my 1200 2>&1 | Out-Null
Start-Sleep -Seconds 3

$xml = Dump 'n'
$texts = [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ }
Say ('   menu: ' + (($texts | Select-Object -First 5) -join ' | '))

Say '7. choosing "remove for me"'
if (-not (TapNode $xml '<node[^>]*text="Удалить только у меня"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' 'remove for me')) { exit 1 }
Start-Sleep -Seconds 3

$xml = Dump 'o'
Say '8. confirming'
if (-not (TapNode $xml '<node[^>]*text="ОК"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' 'confirm')) { exit 1 }
Start-Sleep -Seconds 10

Say '9. what the application did'
$log = (& $adb -s $sam shell logcat -d 2>&1) -split "`n"
$log | Select-String -Pattern 'ChatV2Repository|deleteChatV2Message|DELETE api/chat' | Select-Object -Last 8 | ForEach-Object { Say ('   ' + ($_.Line -replace '^\S+\s+\S+\s+\S+\s+', '')) }
$crash = $log | Select-String -Pattern 'FATAL'
Say ('   crashes: ' + $(if ($crash) { 'yes' } else { 'none' }))
