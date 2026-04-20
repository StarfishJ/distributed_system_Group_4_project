#Requires -Version 5.1
<#
.SYNOPSIS
  根据 terraform output 一次性检查本机可访问的服务（ALB、可选 consumer/server 公网、Rabbit 公网探测）。

.DESCRIPTION
  在 deployment 目录执行；依赖已 terraform apply 且本机可访问 AWS 公网 ALB。
  RDS / ElastiCache / 私网 MQ 无法从公网直连时，会以 [SKIP]/[INFO] 说明（属正常）。

.PARAMETER TerraformDir
  terraform 工作目录，默认为本脚本旁的 terraform 子目录。

.PARAMETER StrictAll
  若指定，则任一项 FAIL 时进程退出码为 1。默认仅当 ALB 的 /health 或 /metrics 失败时退出 1（直连 EC2 失败不算，因常见 SG 仅放行 ALB）。

.EXAMPLE
  .\check-services.ps1
  .\check-services.ps1 -TerraformDir "D:\6650\assignment 3\deployment\terraform"
  .\check-services.ps1 -StrictAll
#>
[CmdletBinding()]
param(
    [string] $TerraformDir = $null,
    [switch] $StrictAll
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
}
catch {}

if ([string]::IsNullOrEmpty($TerraformDir)) {
    $here = $PSScriptRoot
    if ([string]::IsNullOrEmpty($here)) {
        $here = Split-Path -Parent -LiteralPath $MyInvocation.MyCommand.Path
    }
    $TerraformDir = Join-Path $here 'terraform'
}
$script:Ok = 0
$script:Fail = 0
$script:Skip = 0
$script:AlbFail = 0

function Get-TfOutputJson {
    param([string] $Dir)
    Push-Location $Dir
    try {
        $raw = terraform output -json 2>&1
        if ($LASTEXITCODE -ne 0) { throw "terraform output 失败: $raw" }
    }
    finally { Pop-Location }
    return ($raw | ConvertFrom-Json)
}

function Get-TfProp {
    param([object] $Json, [string] $Name)
    $p = $Json.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value.value
}

function Write-CheckResult {
    param(
        [string] $Label,
        [bool] $Passed,
        [string] $Detail = '',
        [switch] $IsAlbCheck
    )
    if ($Passed) {
        $script:Ok++
        Write-Host "  [OK]   $Label" -ForegroundColor Green
    }
    else {
        $script:Fail++
        if ($IsAlbCheck) { $script:AlbFail++ }
        Write-Host "  [FAIL] $Label" -ForegroundColor Red
    }
    if ($Detail) { Write-Host "         $Detail" -ForegroundColor DarkGray }
}

function Write-Skip {
    param([string] $Label, [string] $Reason)
    $script:Skip++
    Write-Host "  [SKIP] $Label — $Reason" -ForegroundColor DarkYellow
}

function Test-HttpGet {
    param(
        [string] $Uri,
        [int[]] $AcceptStatus = @(200),
        [int] $TimeoutSec = 15
    )
    try {
        $r = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec $TimeoutSec -Method Get
        return @{
            Ok     = ($AcceptStatus -contains $r.StatusCode)
            Status = [int]$r.StatusCode
            Error  = $null
        }
    }
    catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode.value__ }
        return @{ Ok = $false; Status = $code; Error = $_.Exception.Message }
    }
}

function Test-TcpConnect {
    param([string] $HostName, [int] $Port, [int] $TimeoutMs = 3000)
    try {
        $c = Test-NetConnection -ComputerName $HostName -Port $Port -WarningAction SilentlyContinue
        return $c.TcpTestSucceeded
    }
    catch { return $false }
}

# --- main ---
Write-Host "`n=== CS6650 服务检查（terraform + HTTP/TCP）===" -ForegroundColor Cyan
if (-not (Test-Path -LiteralPath $TerraformDir)) {
    throw "目录不存在: $TerraformDir"
}

try {
    $tf = Get-TfOutputJson -Dir $TerraformDir
}
catch {
    throw "无法读取 Terraform 输出。请先: cd `"$TerraformDir`" ; terraform apply（或至少 init + output）。`n$_"
}

$region = Get-TfProp -Json $tf -Name 'aws_region'
$albDns = Get-TfProp -Json $tf -Name 'alb_dns_name'
$pgMode = Get-TfProp -Json $tf -Name 'postgres_mode'
$pgAddr = Get-TfProp -Json $tf -Name 'postgres_address'
$pgRo   = Get-TfProp -Json $tf -Name 'postgres_read_replica_address'
$mqMode = Get-TfProp -Json $tf -Name 'rabbitmq_mode'
$mqHost = Get-TfProp -Json $tf -Name 'rabbitmq_host'
$mqPub  = Get-TfProp -Json $tf -Name 'rabbitmq_public_ip'
$redisEp = Get-TfProp -Json $tf -Name 'redis_elasticache_primary_address'
$redisPt = Get-TfProp -Json $tf -Name 'redis_elasticache_port'
$bucket  = Get-TfProp -Json $tf -Name 'app_artifacts_bucket'
$asgSrv  = Get-TfProp -Json $tf -Name 'server_autoscaling_group_name'

$serverIps = @()
$srvRaw = Get-TfProp -Json $tf -Name 'server_public_ips'
if ($srvRaw) { $serverIps = @($srvRaw) }
$consIps = @()
$cRaw = Get-TfProp -Json $tf -Name 'consumer_public_ips'
if ($cRaw) { $consIps = @($cRaw) }

Write-Host "`n[配置摘要]" -ForegroundColor Cyan
Write-Host "  Region: $(if ($region) { $region } else { '(unknown)' })"
Write-Host "  Postgres: mode=$pgMode  host=$pgAddr"
if (-not [string]::IsNullOrEmpty($pgRo)) { Write-Host "  Postgres RO: $pgRo" }
Write-Host "  RabbitMQ: mode=$mqMode  app连接(host)=$mqHost"
Write-Host "  Redis: $(if ($redisEp) { "ElastiCache $redisEp`:$redisPt" } else { 'Docker/未输出 ElastiCache' })"
Write-Host "  应用发布: $(if ($bucket) { "S3 ASG (bucket=$bucket)" } else { 'EC2 + SSH（无 app_artifacts_bucket）' })"
if ($asgSrv) { Write-Host "  Server ASG: $asgSrv" }

# --- ALB ---
Write-Host "`n[ALB — 对外入口]" -ForegroundColor Cyan
if ([string]::IsNullOrEmpty($albDns)) {
    Write-Skip 'ALB' 'alb_dns_name 为空（可能 enable_alb=false 或 enable_eks=true）'
}
else {
    $base = "http://$albDns"
    $h = Test-HttpGet -Uri "$base/health"
    Write-CheckResult "GET $base/health" -Passed $h.Ok -Detail ("HTTP " + $(if ($h.Status) { $h.Status } else { '—' }) + $(if ($h.Error) { " — $($h.Error)" } else { '' })) -IsAlbCheck

    $m = Test-HttpGet -Uri "$base/metrics"
    Write-CheckResult "GET $base/metrics" -Passed $m.Ok -Detail ("HTTP " + $(if ($m.Status) { $m.Status } else { '—' })) -IsAlbCheck

    Write-Host "  提示: JMeter 请打 http://$albDns/ （80）" -ForegroundColor DarkGray
}

# --- Server EC2 direct (optional) ---
Write-Host "`n[Server EC2 — 直连 :8080，常仅 VPC/安全组放行]" -ForegroundColor Cyan
if ($serverIps.Count -eq 0) {
    Write-Skip 'server :8080/health' '无 server_public_ips（可能 ASG 未就绪或仅私网）'
}
else {
    $i = 0
    foreach ($ip in $serverIps) {
        if ([string]::IsNullOrEmpty($ip)) { continue }
        $u = "http://${ip}:8080/health"
        $t = Test-HttpGet -Uri $u -TimeoutSec 8
        Write-CheckResult "GET $u" -Passed $t.Ok -Detail $(if (-not $t.Ok) { '若 FAIL：安全组可能仅允许 ALB 访问 8080' } else { '' })
        $i++
        if ($i -ge 3) { break }
    }
}

# --- Consumer EC2 direct ---
Write-Host "`n[Consumer EC2 — 直连 :8081/actuator/health]" -ForegroundColor Cyan
if ($consIps.Count -eq 0) {
    Write-Skip 'consumer actuator' '无 consumer_public_ips'
}
else {
    $j = 0
    foreach ($ip in $consIps) {
        if ([string]::IsNullOrEmpty($ip)) { continue }
        $u = "http://${ip}:8081/actuator/health"
        $t = Test-HttpGet -Uri $u -TimeoutSec 8
        Write-CheckResult "GET $u" -Passed $t.Ok -Detail $(if (-not $t.Ok) { '若 FAIL：通常仅允许 ALB:8081 或内网' } else { '' })
        $j++
        if ($j -ge 3) { break }
    }
}

# --- RabbitMQ (only if public IP and EC2 mode) ---
Write-Host "`n[RabbitMQ — 公网探测]" -ForegroundColor Cyan
if ($mqMode -ne 'ec2' -or [string]::IsNullOrEmpty($mqPub)) {
    Write-Skip 'AMQP 5672 / UI 15672' '非 EC2 模式或无 rabbitmq_public_ip（管理面多在 VPC 内）'
}
else {
    $amqp = Test-TcpConnect -HostName $mqPub -Port 5672
    Write-CheckResult "TCP $mqPub`:5672 (AMQP)" -Passed $amqp -Detail $(if (-not $amqp) { '若 FAIL：安全组可能未对你公网开放 5672' } else { '' })
    $ui = Test-HttpGet -Uri "http://${mqPub}:15672/" -AcceptStatus @(200, 401) -TimeoutSec 8
    Write-CheckResult "GET http://${mqPub}:15672/ (管理 UI)" -Passed $ui.Ok -Detail '200/401 均视为可达'
}

# --- RDS / Redis: info only ---
Write-Host "`n[仅说明 — 无法从本机公网直连属正常]" -ForegroundColor Cyan
$pgLabel = if ($pgAddr) { "${pgAddr}:5432" } else { '(见 terraform output postgres_address)' }
Write-Host "  [INFO] RDS $pgLabel ：一般仅允许应用安全组访问，本脚本不探测。" -ForegroundColor DarkGray
if ($redisEp) {
    Write-Host "  [INFO] Redis ElastiCache ($redisEp`:$(if ($redisPt) { $redisPt } else { 6379 }))：仅在 VPC 内可达。" -ForegroundColor DarkGray
}
else {
    Write-Host "  [INFO] Redis：若用 Docker 在 MQ/Postgres EC2，同样仅 VPC 内访问。" -ForegroundColor DarkGray
}

# --- Summary ---
Write-Host "`n=== 汇总: OK=$script:Ok  FAIL=$script:Fail  SKIP=$script:Skip  ALB失败=$script:AlbFail ===" -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Yellow' })
if ($script:Fail -gt 0 -and $script:AlbFail -eq 0) {
    Write-Host "说明: 直连 EC2 失败多为安全组仅允许 ALB，属预期；对外以 ALB 为准。" -ForegroundColor DarkGray
}
if (-not $StrictAll -and $script:Fail -gt 0 -and $script:AlbFail -eq 0) {
    Write-Host "退出码: 0（默认仅 ALB 失败才非零）。若要对所有项严格：加 -StrictAll" -ForegroundColor DarkGray
}
Write-Host ""

$exitBad = if ($StrictAll) { $script:Fail -gt 0 } else { $script:AlbFail -gt 0 }
exit $(if ($exitBad) { 1 } else { 0 })
