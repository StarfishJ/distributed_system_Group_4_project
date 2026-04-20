#Requires -Version 5.1
<#
.SYNOPSIS
  读取 terraform 的 ALB DNS，用 -Jhost / -Jport=80 跑 HTTP 类 .jmx（经 ALB）。

  注意：请勿使用以 -J 开头的参数名（会与 JMeter 的 -Jprop=value 混淆）。请用 -TestPlan、-JMeterInstall。

.EXAMPLE
  cd "<仓库>\load-tests\jmeter"
  .\run-http-via-alb.ps1 -TestPlan health-baseline.jmx
  .\run-http-via-alb.ps1 -TestPlan health-baseline.jmx -JMeterInstall "<你的 JMeter 根目录>"
  .\run-http-via-alb.ps1 -TestPlan assignment-baseline-100k-5min.jmx -ReadLoops 500
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $TestPlan = 'health-baseline.jmx',

    [string] $TerraformDir = '',

    [string] $JMeterInstall = '',

    [string] $AlbHost = '',

    [int] $Port = 80,

    [string] $ResultsDir = '',

    [switch] $NoReport,

    # When > 0, forwarded as -Jread_loops / -Jwrite_loops (assignment-baseline-100k-5min.jmx).
    [int] $ReadLoops = 0,

    [int] $WriteLoops = 0,

    [string[]] $JMeterExtraArgs,

    # Skip loading jmeter-run.properties (next to this script).
    [switch] $SkipRunProperties,

    # Optional JVM args for JMeter (e.g. '-Xms1g -Xmx4g'). If unset, a modest heap is used for CLI runs.
    [string] $JvmArgs = '',

    # Stress only: gentler ramp + later metrics TG (helps Windows JMeter). Adds -Jramp_sec=900 -Jwrite_group_startup_delay_sec=180
    [switch] $StressWindows
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($TerraformDir)) {
    $TerraformDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path 'deployment\terraform'
}
else {
    $TerraformDir = (Resolve-Path -LiteralPath $TerraformDir).Path
}

function Find-JMeterBat {
    param([string]$ExplicitRoot)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitRoot)) {
        $cand = Join-Path $ExplicitRoot 'bin\jmeter.bat'
        if (Test-Path -LiteralPath $cand) { return (Resolve-Path $cand).Path }
        throw "在指定目录下找不到 bin\jmeter.bat: $ExplicitRoot"
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JMETER_HOME)) {
        $cand = Join-Path $env:JMETER_HOME.TrimEnd('\') 'bin\jmeter.bat'
        if (Test-Path -LiteralPath $cand) { return (Resolve-Path $cand).Path }
    }

    $where = & where.exe jmeter.bat 2>$null | Select-Object -First 1
    if ($where -and (Test-Path -LiteralPath $where.Trim())) {
        return $where.Trim()
    }

    foreach ($p in @(
            'C:\jmeter\bin\jmeter.bat',
            'D:\jmeter\bin\jmeter.bat',
            (Join-Path $env:USERPROFILE 'scoop\apps\jmeter\current\bin\jmeter.bat')
        )) {
        if ($p -and (Test-Path -LiteralPath $p)) { return (Resolve-Path $p).Path }
    }

    $pf = @($env:ProgramFiles, ${env:ProgramFiles(x86)}) | Where-Object { $_ }
    foreach ($root in $pf) {
        $dirs = Get-ChildItem -Path $root -Directory -Filter 'apache-jmeter*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        foreach ($d in $dirs) {
            $cand = Join-Path $d.FullName 'bin\jmeter.bat'
            if (Test-Path -LiteralPath $cand) { return $cand }
        }
    }

    throw @"
找不到 jmeter.bat。请任选其一：
  1) 安装 Apache JMeter 后执行（路径换成你的解压目录）：
     .\run-http-via-alb.ps1 -TestPlan health-baseline.jmx -JMeterInstall "C:\tools\apache-jmeter-5.6.3"
  2) 设置用户环境变量 JMETER_HOME 为 JMeter 根目录（内含 bin\jmeter.bat）
  3) 把 bin 加入 PATH，使 where jmeter.bat 能找到
"@
}

$jmxPath = Join-Path $PSScriptRoot $TestPlan
if (-not (Test-Path -LiteralPath $jmxPath)) { throw "找不到测试计划: $jmxPath" }

if ([string]::IsNullOrWhiteSpace($AlbHost)) {
    Push-Location $TerraformDir
    try {
        $AlbHost = (& terraform output -raw alb_dns_name).Trim()
        if ($LASTEXITCODE -ne 0) { throw "terraform output 失败（请在 deployment\terraform 下已 terraform apply）。" }
    }
    finally { Pop-Location }
    if ([string]::IsNullOrWhiteSpace($AlbHost)) { throw 'alb_dns_name 为空，请确认 enable_alb=true 且已 apply。' }
}

$jmeterBat = Find-JMeterBat -ExplicitRoot $JMeterInstall

$runProps = Join-Path $PSScriptRoot 'jmeter-run.properties'
$useRunProps = (-not $SkipRunProperties) -and (Test-Path -LiteralPath $runProps)
if (-not [string]::IsNullOrWhiteSpace($JvmArgs)) {
    $env:JVM_ARGS = $JvmArgs.Trim()
}
elseif ($useRunProps) {
    $env:JVM_ARGS = '-Xms512m -Xmx2048m -XX:+UseG1GC'
}
if ($useRunProps) {
    Write-Host "Props:  $runProps" -ForegroundColor Gray
}
if (-not [string]::IsNullOrWhiteSpace($env:JVM_ARGS)) {
    Write-Host "JVM:    $($env:JVM_ARGS)" -ForegroundColor Gray
}

if ($ReadLoops -gt 0 -and $WriteLoops -eq 0) { $WriteLoops = $ReadLoops }
if ($WriteLoops -gt 0 -and $ReadLoops -eq 0) { $ReadLoops = $WriteLoops }

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $PSScriptRoot 'results'
}
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$base = [System.IO.Path]::GetFileNameWithoutExtension($TestPlan)
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$jtl = Join-Path $ResultsDir "${base}-alb-${ts}.jtl"
$html = Join-Path $ResultsDir "${base}-alb-${ts}-report"

Write-Host "JMeter: $jmeterBat" -ForegroundColor Gray
Write-Host "ALB:    $AlbHost port $Port" -ForegroundColor Cyan
Write-Host "JMX:    $jmxPath" -ForegroundColor Cyan
Write-Host "JTL:    $jtl" -ForegroundColor Cyan

$jmArgs = @('-n')
if ($useRunProps) {
    $jmArgs += @('-q', $runProps)
}
$jmArgs += @(
    '-t', $jmxPath,
    '-Jhost', $AlbHost,
    '-Jport', $Port.ToString(),
    '-l', $jtl
)
if (-not $NoReport) {
    $jmArgs += @('-e', '-o', $html)
}
if ($ReadLoops -gt 0) { $jmArgs += "-Jread_loops=$ReadLoops" }
if ($WriteLoops -gt 0) { $jmArgs += "-Jwrite_loops=$WriteLoops" }
if ($StressWindows) {
    $jmArgs += @('-Jramp_sec=900', '-Jwrite_group_startup_delay_sec=180')
}
if ($JMeterExtraArgs -and $JMeterExtraArgs.Count -gt 0) {
    $jmArgs += $JMeterExtraArgs
}

& $jmeterBat @jmArgs
if ($LASTEXITCODE -ne 0) { throw "JMeter 退出码: $LASTEXITCODE" }

Write-Host "`n完成。HTML 报告: $html" -ForegroundColor Green
