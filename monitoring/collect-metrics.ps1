# CS6650 Assignment 3 — snapshot server metrics for reports / submission
# Usage: .\monitoring\collect-metrics.ps1 [-BaseUrl http://localhost:8080] [-RefreshMviews]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$RefreshMviews
)

$outDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$ts = Get-Date -Format "yyyyMMdd-HHmmss"
$base = $BaseUrl.TrimEnd('/')

function Save-Get($path, $fileSuffix) {
    $url = "$base$path"
    $out = Join-Path $outDir "$ts-$fileSuffix.json"
    try {
        Invoke-WebRequest -Uri $url -UseBasicParsing | Select-Object -ExpandProperty Content | Set-Content -Path $out -Encoding UTF8
        Write-Host "OK $url -> $out"
    } catch {
        Write-Warning "FAIL $url : $_"
    }
}

Save-Get "/health" "health"
$m = if ($RefreshMviews) { "/metrics?refreshMaterializedViews=true" } else { "/metrics" }
Save-Get $m "metrics"

Write-Host "Done. Files under: $outDir"
