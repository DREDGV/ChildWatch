param(
    [ValidateSet("app", "parentwatch", "both")]
    [string]$Target = "both"
)

$scriptPath = Join-Path $PSScriptRoot "dev-workflow.ps1"
& $scriptPath -Action deploy -Target $Target
