#Requires -Version 5.1
<#
.SYNOPSIS
  从本机自动部署 EC2 路径：Postgres+PgBouncer（+ 可选本机 Docker Redis）、RabbitMQ（Docker）、可选 Amazon ElastiCache Redis、server-v2×N、consumer-v3。

.DESCRIPTION
  需要：OpenSSH（ssh/scp）、terraform、Java/Maven（默认会 **mvn clean package**，除非 -SkipBuild）。
  上传前会校验 JAR 为 Spring Boot fat jar，且在使用 -SkipBuild 时若源码/pom 比 JAR 新会直接报错。
  在 terraform apply 之后，于本机 PowerShell 执行。
  **Windows**：若私钥因 ACL 过宽无法加载，脚本会**自动 icacls 收紧并重试**一次（也可用 **-RepairKeyAcl** 在首轮校验前就收紧）。
  数据面仍用 **SSH 探针**；**server-v2 / consumer-v3** 在 **ASG + S3**：本机 **aws s3 cp** 上传 JAR 与 `*.env` 后 **start-instance-refresh**（需 **AWS CLI** 与 Terraform 同源凭证）。若仅更新数据面可 **-Phase DataPlane**。
  仍可用 **-Skip*** 强制不执行某块；**-Force** 关闭自动跳过、按阶段完整重跑（**-Skip*** 仍优先）。

.PARAMETER KeyPath
  与 Terraform public_key_path 对应的私钥（例如 C:\Users\you\.ssh\cs6650-aws 或 .pem）。

.PARAMETER Force
  禁用就绪探针自动跳过，按阶段完整执行部署（**-Skip*** 开关仍优先生效）。

.PARAMETER RepairKeyAcl
  （Windows）在首次校验**前**强制用 icacls 收紧私钥；未指定时若校验失败也会**自动收紧并重试一次**。

.EXAMPLE
  cd deployment\terraform
  .\deploy-ec2.ps1 -KeyPath "C:\Users\you\.ssh\cs6650-aws"
  .\deploy-ec2.ps1 -KeyPath "D:\6650\labsuser.pem" -SkipBuild
  .\deploy-ec2.ps1 -KeyPath "..." -Phase DataPlane
  .\deploy-ec2.ps1 -KeyPath "..." -Phase Apps
  .\deploy-ec2.ps1 -KeyPath "..." -Phase DataPlane -SkipPostgres   # 只部署 Rabbit
  .\deploy-ec2.ps1 -KeyPath "..." -Phase All -SkipPostgres -SkipRabbit  # 仅重发应用
  .\deploy-ec2.ps1 -KeyPath "..." -Phase DataPlane -Force               # 探针通过也重跑数据面
  .\deploy-ec2.ps1 -KeyPath "C:\Users\you\.ssh\key.pem" -RepairKeyAcl -SkipBuild  # 私钥权限过宽时自动收紧
#>
[CmdletBinding()]
param(
    [string] $TerraformDir = $PSScriptRoot,
    [string] $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [Parameter(Mandatory = $true)]
    [string] $KeyPath,
    [switch] $SkipBuild,
    [ValidateSet('All', 'DataPlane', 'Apps')]
    [string] $Phase = 'All',
    [switch] $SkipPostgres,
    [switch] $SkipRabbit,
    [switch] $SkipServer,
    [switch] $SkipConsumer,
    [switch] $SkipInstanceRefresh,
    [switch] $Force,
    [switch] $RepairKeyAcl,
    # Force server+consumer to run with Redis disabled (env SERVER_/CONSUMER_REDIS_ENABLED=false) even if ElastiCache exists — for baseline A/B vs Redis on.
    [switch] $AppRedisDisabled
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $KeyPath)) {
    throw "Key not found: $KeyPath"
}
$KeyPath = (Resolve-Path -LiteralPath $KeyPath).Path

function Repair-OpenSshPrivateKeyAcl {
    param([Parameter(Mandatory)][string]$Path)
    $who = (& whoami.exe).Trim()
    Write-Host "正在收紧私钥 ACL（inheritance 关闭 + 仅 $who 读）: $Path" -ForegroundColor Yellow
    & icacls.exe $Path /c /inheritance:r
    if ($LASTEXITCODE -ne 0) { throw "icacls /inheritance:r 失败（exit $LASTEXITCODE）" }
    & icacls.exe $Path /c /grant:r "${who}:(R)"
    if ($LASTEXITCODE -ne 0) { throw "icacls /grant:r 失败（exit $LASTEXITCODE）" }
}

if ($env:OS -eq 'Windows_NT') {
    $keyAclRepaired = $false
    if ($RepairKeyAcl) {
        Repair-OpenSshPrivateKeyAcl -Path $KeyPath
        $keyAclRepaired = $true
    }
    $probe = & ssh-keygen.exe -y -f $KeyPath 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $keyAclRepaired) {
        Write-Host "私钥未通过 OpenSSH 校验，自动收紧 ACL 并重试…" -ForegroundColor Yellow
        Repair-OpenSshPrivateKeyAcl -Path $KeyPath
        $keyAclRepaired = $true
        $probe = & ssh-keygen.exe -y -f $KeyPath 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        $who = (& whoami.exe).Trim()
        $fix = @"
Windows OpenSSH 仍无法加载该私钥（可能不是权限问题，或 icacls 未生效）。可手动执行：

  icacls `"$KeyPath`" /inheritance:r
  icacls `"$KeyPath`" /grant:r `"${who}:(R)`"

并确认密钥格式正确、路径无错。

ssh-keygen 诊断: $probe
"@
        throw "私钥无法通过 OpenSSH 校验。`n$fix"
    }
}

$SshBase = @('-i', $KeyPath, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=30')

function Get-TfOutputProp {
    param(
        [Parameter(Mandatory)][object] $TfJson,
        [Parameter(Mandatory)][string] $Name
    )
    $p = $TfJson.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value.value
}

function Get-TfOutput {
    param([string] $Dir)
    Push-Location $Dir
    try {
        $raw = terraform output -json 2>&1
        if ($LASTEXITCODE -ne 0) { throw "terraform output failed: $raw" }
    }
    finally { Pop-Location }
    $obj = $raw | ConvertFrom-Json
    return @{
        RabbitMode   = $obj.rabbitmq_mode.value
        RabbitHost   = $obj.rabbitmq_host.value
        RabbitUser   = $obj.rabbitmq_username.value
        RabbitPass   = $obj.rabbitmq_password.value
        RabbitPub    = $obj.rabbitmq_public_ip.value
        RabbitPriv   = $obj.rabbitmq_private_ip.value
        PostgresMode = $obj.postgres_mode.value
        PostgresAddr = $obj.postgres_address.value
        PostgresJdbc = $obj.postgres_jdbc_url.value
        PostgresUser = $obj.postgres_username.value
        PostgresPass = $obj.postgres_password.value
        PostgresPub  = $obj.postgres_public_ip.value
        PostgresPriv = $obj.postgres_private_ip.value
        PostgresReadReplicaAddr = $obj.postgres_read_replica_address.value
        PostgresReadReplicaJdbc = $obj.postgres_read_replica_jdbc_url.value
        ConsumerPub  = $obj.consumer_public_ip.value
        ConsumerPubs = @($obj.consumer_public_ips.value)
        ServerPubs   = @($obj.server_public_ips.value)
        AlbDns       = $obj.alb_dns_name.value
        RedisElasticacheHost = $obj.redis_elasticache_primary_address.value
        RedisElasticachePort = $obj.redis_elasticache_port.value
        AwsRegion            = (Get-TfOutputProp -TfJson $obj -Name 'aws_region')
        AppArtifactsBucket   = (Get-TfOutputProp -TfJson $obj -Name 'app_artifacts_bucket')
        AppArtifactsPrefix   = (Get-TfOutputProp -TfJson $obj -Name 'app_artifacts_s3_prefix')
        ServerAsgName        = (Get-TfOutputProp -TfJson $obj -Name 'server_autoscaling_group_name')
        ConsumerAsgName      = (Get-TfOutputProp -TfJson $obj -Name 'consumer_autoscaling_group_name')
    }
}

function Normalize-BashText {
    param([string] $Text)
    # Windows CRLF 经 ssh 喂给 bash -s 会破坏 set/docker compose，必须只用 LF
    return ($Text -replace "`r`n", "`n" -replace "`r", "`n").TrimEnd()
}

function Invoke-RemoteScript {
    param([string] $Target, [string] $BashScript)
    $script = Normalize-BashText $BashScript
    $script | & ssh @SshBase $Target 'bash', '-s'
    if ($LASTEXITCODE -ne 0) { throw "Remote script failed on $Target" }
}

# 经 scp 上传 LF 脚本再执行，避免 Windows 管道/CRLF 导致 rm、set、docker compose 异常
function Invoke-RemoteBashFromFile {
    param(
        [string] $Target,
        [string] $ScriptBody,
        [string] $RemotePath = '/tmp/cs6650-remote.sh'
    )
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $body = "#!/bin/bash`n$(Normalize-BashText $ScriptBody)`n"
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cs6650-" + [Guid]::NewGuid().ToString() + '.sh')
    try {
        [System.IO.File]::WriteAllText($tmp, $body, $utf8)
        & scp @SshBase $tmp "${Target}:${RemotePath}"
        if ($LASTEXITCODE -ne 0) { throw 'scp deploy script failed' }
        & ssh @SshBase $Target 'bash', $RemotePath
        if ($LASTEXITCODE -ne 0) { throw "Remote script failed: $Target $RemotePath" }
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Assert-AwsCli {
    if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
        throw "未找到 AWS CLI（aws）。ASG+S3 路径需要安装 AWS CLI，并配置与 Terraform 相同的凭证（可先运行 aws sts get-caller-identity）。"
    }
}

function Start-AsgInstanceRefresh {
    param(
        [string] $AsgName,
        [string] $Region
    )
    if ([string]::IsNullOrEmpty($AsgName)) { return }
    Write-Host "  instance-refresh: $AsgName" -ForegroundColor Gray
    $prefs = 'MinHealthyPercentage=0,MaxHealthyPercentage=100,InstanceWarmup=120'
    & aws autoscaling start-instance-refresh --auto-scaling-group-name $AsgName --preferences $prefs --region $Region
    if ($LASTEXITCODE -ne 0) { throw "start-instance-refresh 失败: $AsgName" }
}

function Copy-RemoteTextFile {
    param(
        [string] $Target,
        [string] $Text,
        [string] $RemotePath
    )
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cs6650-text-" + [Guid]::NewGuid().ToString() + '.txt')
    try {
        [System.IO.File]::WriteAllText($tmp, (Normalize-BashText $Text) + "`n", $utf8)
        & scp @SshBase $tmp "${Target}:${RemotePath}"
        if ($LASTEXITCODE -ne 0) { throw 'scp text file failed' }
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-RemoteOneLiner {
    param([string] $Target, [string] $Line)
    # 勿用 bash -lc（OpenSSH 拆句）或 stdin 管道（PS 5.1 常混入 UTF-16/CRLF，出现 mkdir: not found、$'\r': command not found）
    $remotePath = '/tmp/cs6650-one-liner-' + [Guid]::NewGuid().ToString() + '.sh'
    try {
        Invoke-RemoteBashFromFile -Target $Target -ScriptBody $Line -RemotePath $remotePath
    }
    catch {
        throw "ssh failed ($Target): $Line — $($_.Exception.Message)"
    }
}

# 仅用于就绪探针：不抛异常，失败返回 $false（与 Invoke-RemoteBashFromFile 相同传输方式，避免编码问题）
function Invoke-RemoteBashProbe {
    param([string]$Target, [string]$ScriptBody)
    $remotePath = '/tmp/cs6650-probe-' + [Guid]::NewGuid().ToString() + '.sh'
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $body = "#!/bin/bash`n$(Normalize-BashText $ScriptBody)`n"
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('cs6650-probe-' + [Guid]::NewGuid().ToString() + '.sh')
    try {
        [System.IO.File]::WriteAllText($tmp, $body, $utf8)
        & scp @SshBase $tmp "${Target}:${remotePath}"
        if ($LASTEXITCODE -ne 0) { return $false }
        & ssh @SshBase $Target 'bash', $remotePath
        return ($LASTEXITCODE -eq 0)
    }
    catch {
        return $false
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Test-RemoteServerAppReady {
    param([string]$Target, [long]$ExpectedJarBytes)
    $t = @'
set -e
J=/home/ec2-user/app/server-v2.jar
test -f "$J" || exit 1
test "$(stat -c%s "$J")" = "__SIZE__" || exit 1
command -v curl >/dev/null 2>&1 || exit 1
curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:8080/health >/dev/null 2>&1 || exit 1
'@
    return Invoke-RemoteBashProbe -Target $Target -ScriptBody ($t.Replace('__SIZE__', $ExpectedJarBytes.ToString()))
}

function Test-RemoteConsumerAppReady {
    param([string]$Target, [long]$ExpectedJarBytes)
    $t = @'
set -e
J=/home/ec2-user/app/consumer-v3.jar
test -f "$J" || exit 1
test "$(stat -c%s "$J")" = "__SIZE__" || exit 1
command -v curl >/dev/null 2>&1 || exit 1
curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:8081/actuator/health >/dev/null 2>&1 || exit 1
'@
    return Invoke-RemoteBashProbe -Target $Target -ScriptBody ($t.Replace('__SIZE__', $ExpectedJarBytes.ToString()))
}

# user_data 未装 Java 或仅装在 /usr/lib/jvm/... 时，勿死写 /usr/bin/java
function Get-RemoteJavaStartBody {
    param([string]$JarFileName, [string]$LogFileName)
    $s = @'
export PATH=/usr/local/bin:/usr/bin:/bin
cd /home/ec2-user/app
resolve_java() {
  for j in /usr/bin/java /usr/lib/jvm/java-17-amazon-corretto/bin/java; do
    [ -x "$j" ] && echo "$j" && return 0
  done
  if command -v java >/dev/null 2>&1; then command -v java; return 0; fi
  shopt -s nullglob
  for j in /usr/lib/jvm/java-*-amazon-corretto/bin/java; do
    [ -x "$j" ] && echo "$j" && return 0
  done
  return 1
}
JAVA=$(resolve_java) || true
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  echo "Installing java-17-amazon-corretto-headless..."
  sudo dnf install -y java-17-amazon-corretto-headless
  JAVA=$(resolve_java) || JAVA=/usr/bin/java
fi
[ -x "$JAVA" ] || { echo "Cannot find java after install"; exit 1; }
nohup "$JAVA" -jar __JAR__ > /home/ec2-user/app/__LOG__ 2>&1 &
sleep 3
tail -n 15 /home/ec2-user/app/__LOG__
'@
    return $s.Replace('__JAR__', $JarFileName).Replace('__LOG__', $LogFileName)
}

function Find-BootJar {
    param(
        [string] $ModuleDir,
        [string] $FriendlyName,
        [string] $NameGlob
    )
    $dir = Join-Path $RepoRoot $ModuleDir
    if (-not (Test-Path -LiteralPath $dir)) { throw "Missing directory: $dir" }
    $jars = Get-ChildItem -Path $dir -Filter $NameGlob -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'original|sources|javadoc' }
    if (-not $jars) {
        $jars = Get-ChildItem -Path $dir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'original|sources|javadoc' }
    }
    if (-not $jars) { throw "No runnable JAR in $dir — 请先 mvn clean package 或去掉 -SkipBuild。" }
    $jar = $jars | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    Write-Host "Using ${FriendlyName}: $($jar.Name) ($('{0:N0}' -f $jar.Length) bytes, $($jar.LastWriteTime))" -ForegroundColor Gray
    return $jar.FullName
}

function Test-SpringBootFatJar {
    param([string] $JarPath, [string] $Label)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $names = $zip.Entries | ForEach-Object { $_.FullName }
        $boot = $names | Where-Object { $_ -like 'BOOT-INF/*' } | Select-Object -First 1
        if (-not $boot) {
            throw "${Label}: 不是 Spring Boot 可执行 JAR（缺少 BOOT-INF/）: $JarPath"
        }
    }
    finally { $zip.Dispose() }
}

function Assert-JarNotStaleVsSources {
    param([string] $ModuleRoot, [string] $JarPath, [string] $Label)
    $jar = Get-Item -LiteralPath $JarPath
    $pom = Join-Path $ModuleRoot 'pom.xml'
    if (-not (Test-Path -LiteralPath $pom)) { return }
    $srcMain = Join-Path $ModuleRoot 'src\main'
    $newest = (Get-Item -LiteralPath $pom).LastWriteTimeUtc
    if (Test-Path -LiteralPath $srcMain) {
        $f = Get-ChildItem -Path $srcMain -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -match '^\.(java|xml|properties|yml|yaml)$' } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($f -and $f.LastWriteTimeUtc -gt $newest) { $newest = $f.LastWriteTimeUtc }
    }
    if ($jar.LastWriteTimeUtc.AddSeconds(-3) -lt $newest) {
        throw "${Label}: 本地 JAR 早于 pom/源码，可能不是最新构建。请去掉 -SkipBuild 执行 mvn clean package。"
    }
}

function Invoke-MavenRebuild {
    param([string] $Root)
    Write-Host "`n=== mvn clean package（server-v2 + consumer-v3）===" -ForegroundColor Cyan
    Push-Location $Root
    try {
        $mvnArgs = @('-f', (Join-Path $Root 'server-v2\pom.xml'), 'clean', 'package', '-DskipTests', '-B')
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw 'mvn clean package（server-v2）失败，见上方日志。' }
        $mvnArgs = @('-f', (Join-Path $Root 'consumer-v3\pom.xml'), 'clean', 'package', '-DskipTests', '-B')
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw 'mvn clean package（consumer-v3）失败，见上方日志。' }
    }
    finally { Pop-Location }
}

# --- Terraform ---
$out = Get-TfOutput -Dir $TerraformDir
if (($Phase -eq 'All' -or $Phase -eq 'DataPlane') -and $out.RabbitMode -eq 'ec2' -and [string]::IsNullOrEmpty($out.RabbitPub)) {
    throw "rabbitmq_public_ip 为空（当前 RabbitMQ 为 EC2 模式）。请确认已 terraform apply。"
}
if (($Phase -eq 'All' -or $Phase -eq 'DataPlane') -and $out.PostgresMode -eq 'ec2' -and [string]::IsNullOrEmpty($out.PostgresPub)) {
    throw "postgres_public_ip 为空（当前 Postgres 为 EC2 模式）。请确认已 terraform apply。"
}
if (($Phase -eq 'All' -or $Phase -eq 'Apps') -and [string]::IsNullOrEmpty($out.PostgresAddr)) {
    throw "postgres_address 为空，无法下发应用数据库连接。请确认已 terraform apply。"
}
if (($Phase -eq 'All' -or $Phase -eq 'Apps') -and [string]::IsNullOrEmpty($out.RabbitHost)) {
    throw "rabbitmq_host 为空，无法下发应用 MQ 连接。请确认已 terraform apply。"
}

Write-Host "=== Terraform outputs ===" -ForegroundColor Cyan
Write-Host "  ALB: $($out.AlbDns)"
Write-Host "  Rabbit: mode=$($out.RabbitMode), host=$($out.RabbitHost), pub=$($out.RabbitPub), priv=$($out.RabbitPriv)"
Write-Host "  Postgres: mode=$($out.PostgresMode), addr=$($out.PostgresAddr), pub=$($out.PostgresPub), priv=$($out.PostgresPriv)"
if (-not [string]::IsNullOrEmpty($out.PostgresReadReplicaAddr)) {
    Write-Host "  Postgres read replica: $($out.PostgresReadReplicaAddr)"
}
Write-Host "  Servers: $($out.ServerPubs -join ', ')"
Write-Host "  Consumers: $($out.ConsumerPubs -join ', ')"
if (-not [string]::IsNullOrEmpty($out.RedisElasticacheHost)) {
    Write-Host "  Redis: ElastiCache $($out.RedisElasticacheHost):$(if ($null -ne $out.RedisElasticachePort) { $out.RedisElasticachePort } else { 6379 })"
}
else {
    Write-Host "  Redis: 本机 Docker（Postgres 或 Rabbit EC2，见数据面部署）"
}
if ($Force) {
    Write-Host "`n已指定 -Force：禁用就绪自动跳过（显式 -Skip* 仍会跳过对应步骤）。" -ForegroundColor Yellow
}

if ($Phase -eq 'All' -or $Phase -eq 'Apps') {
    if ($SkipBuild) {
        Write-Warning "已指定 -SkipBuild：不会执行 mvn。若 JAR 早于源码将中止上传。"
    }
    else {
        Invoke-MavenRebuild -Root $RepoRoot
    }
}

$serverJarPath = $null
$consumerJarPath = $null
if ($Phase -eq 'All' -or $Phase -eq 'Apps') {
    $serverJarPath = Find-BootJar -ModuleDir 'server-v2\target' -FriendlyName 'server-v2' -NameGlob 'chat-server*.jar'
    $consumerJarPath = Find-BootJar -ModuleDir 'consumer-v3\target' -FriendlyName 'consumer-v3' -NameGlob 'chat-consumer-v3*.jar'

    Test-SpringBootFatJar -JarPath $serverJarPath -Label 'server-v2'
    Test-SpringBootFatJar -JarPath $consumerJarPath -Label 'consumer-v3'

    if ($SkipBuild) {
        Assert-JarNotStaleVsSources -ModuleRoot (Join-Path $RepoRoot 'server-v2') -JarPath $serverJarPath -Label 'server-v2'
        Assert-JarNotStaleVsSources -ModuleRoot (Join-Path $RepoRoot 'consumer-v3') -JarPath $consumerJarPath -Label 'consumer-v3'
    }

    Write-Host "`n=== 上传前 JAR 校验通过 ===" -ForegroundColor Green
}

$dbUser = if ([string]::IsNullOrEmpty($out.PostgresUser)) { 'chat' } else { $out.PostgresUser }
$dbPass = if ([string]::IsNullOrEmpty($out.PostgresPass)) { 'chat' } else { $out.PostgresPass }
# 非 guest：guest 默认仅允许本机连接，server/consumer 从私网连 broker 会失败
$mqUser = if ([string]::IsNullOrEmpty($out.RabbitUser)) { 'chatmq' } else { $out.RabbitUser }
$mqPass = if ([string]::IsNullOrEmpty($out.RabbitPass)) { 'chatmq' } else { $out.RabbitPass }

if ($Phase -eq 'All' -or $Phase -eq 'DataPlane') {
    $pgTarget = if ($out.PostgresMode -eq 'ec2') { "ec2-user@$($out.PostgresPub)" } else { $null }
    $mqTarget = if ($out.RabbitMode -eq 'ec2') { "ec2-user@$($out.RabbitPub)" } else { $null }

    $probePg = @'
set -e
sudo docker info >/dev/null 2>&1 || exit 1
test "$(sudo docker inspect -f '{{.State.Running}}' chatdb-pg 2>/dev/null)" = "true" || exit 1
sudo docker exec chatdb-pg pg_isready -U chat -d chatdb >/dev/null 2>&1 || exit 1
'@

    $probeMqWithDockerRedis = @'
set -e
sudo docker info >/dev/null 2>&1 || exit 1
test "$(sudo docker inspect -f '{{.State.Running}}' chat-mq 2>/dev/null)" = "true" || exit 1
sudo docker exec chat-mq rabbitmq-diagnostics -q ping >/dev/null 2>&1 || exit 1
test "$(sudo docker inspect -f '{{.State.Running}}' chat-redis 2>/dev/null)" = "true" || exit 1
sudo docker exec chat-redis redis-cli ping 2>/dev/null | grep -q PONG || exit 1
'@

    $probeMqElastiCacheOnly = @'
set -e
sudo docker info >/dev/null 2>&1 || exit 1
test "$(sudo docker inspect -f '{{.State.Running}}' chat-mq 2>/dev/null)" = "true" || exit 1
sudo docker exec chat-mq rabbitmq-diagnostics -q ping >/dev/null 2>&1 || exit 1
'@

    $probeMq = if (-not [string]::IsNullOrEmpty($out.RedisElasticacheHost)) { $probeMqElastiCacheOnly } else { $probeMqWithDockerRedis }

    if ($SkipPostgres) {
        Write-Host "`n=== Postgres EC2: 跳过（-SkipPostgres）===" -ForegroundColor DarkGray
    }
    elseif ($out.PostgresMode -ne 'ec2') {
        Write-Host "`n=== Postgres EC2: 跳过（当前为 $($out.PostgresMode) 模式）===" -ForegroundColor DarkGray
    }
    elseif (-not $Force -and (Invoke-RemoteBashProbe -Target $pgTarget -ScriptBody $probePg)) {
        Write-Host "`n=== Postgres EC2: 探针通过，跳过部署（-Force 可强制重跑）===" -ForegroundColor DarkGray
    }
    else {
    Write-Host "`n=== Postgres EC2: Docker + SQL ===" -ForegroundColor Cyan
    $dbLocal = Join-Path $RepoRoot 'database'
    if (-not (Test-Path -LiteralPath $dbLocal)) { throw "Missing: $dbLocal" }

    & scp @SshBase '-r' $dbLocal ($pgTarget + ':/home/ec2-user/cs6650-database-src')
    if ($LASTEXITCODE -ne 0) { throw 'scp database folder failed' }

    $pgComposeServices = if (-not [string]::IsNullOrEmpty($out.RedisElasticacheHost)) {
        'postgres pgbouncer-write pgbouncer-read'
    }
    else {
        'postgres pgbouncer-write pgbouncer-read redis'
    }

    $pgScript = @'
set -euo pipefail
rm -rf /home/ec2-user/cs6650-database
mv /home/ec2-user/cs6650-database-src /home/ec2-user/cs6650-database
cd /home/ec2-user/cs6650-database
sudo systemctl is-active docker >/dev/null 2>&1 || sudo systemctl start docker
# 部分 AL2023/Yum 源无 docker-compose-plugin；用官方二进制（x86_64 / aarch64）
compose_up() {
  if sudo docker compose version >/dev/null 2>&1; then
    sudo docker compose up -d __PG_COMPOSE_SERVICES__
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    sudo docker-compose up -d __PG_COMPOSE_SERVICES__
    return
  fi
  ARCH=$(uname -m)
  case "$ARCH" in
    x86_64) CARCH=x86_64 ;;
    aarch64) CARCH=aarch64 ;;
    *) echo "Unsupported machine: $ARCH"; exit 1 ;;
  esac
  sudo curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-${CARCH}" -o /usr/local/bin/docker-compose
  sudo chmod +x /usr/local/bin/docker-compose
  sudo /usr/local/bin/docker-compose up -d __PG_COMPOSE_SERVICES__
}
compose_up
for i in $(seq 1 90); do
  sudo docker exec chatdb-pg pg_isready -U chat -d chatdb && break
  sleep 2
done
sudo docker exec chatdb-pg pg_isready -U chat -d chatdb
for f in 01_schema.sql 02_indexes.sql 03_views.sql 05_dlq_audit.sql; do
  if [ -f "$f" ]; then
    cat "$f" | sudo docker exec -i chatdb-pg psql -v ON_ERROR_STOP=1 -U chat -d chatdb
  fi
done
echo "Postgres stack OK"
'@.Replace('__PG_COMPOSE_SERVICES__', $pgComposeServices)

    Invoke-RemoteBashFromFile -Target $pgTarget -ScriptBody $pgScript -RemotePath '/tmp/cs6650-deploy-pg.sh'
    }

    if ($SkipRabbit) {
        Write-Host "`n=== RabbitMQ EC2: 跳过（-SkipRabbit）===" -ForegroundColor DarkGray
    }
    elseif ($out.RabbitMode -ne 'ec2') {
        Write-Host "`n=== RabbitMQ EC2: 跳过（当前为 $($out.RabbitMode) 模式）===" -ForegroundColor DarkGray
    }
    elseif (-not $Force -and (Invoke-RemoteBashProbe -Target $mqTarget -ScriptBody $probeMq)) {
        Write-Host "`n=== RabbitMQ EC2: 探针通过，跳过部署（-Force 可强制重跑）===" -ForegroundColor DarkGray
    }
    else {
    Write-Host "`n=== RabbitMQ EC2 ===" -ForegroundColor Cyan
    $mqScriptTemplateDockerRedis = @'
set -euo pipefail
sudo systemctl is-active docker >/dev/null 2>&1 || sudo systemctl start docker
sudo docker rm -f chat-mq 2>/dev/null || true
# 3.13 镜像将 RABBITMQ_VM_MEMORY_HIGH_WATERMARK 等旧环境变量视为致命错误；用 conf.d 片段设置内存水位。
MQ_MEM=/home/ec2-user/cs6650-rabbit-99-memory.conf
printf '%s\n' 'vm_memory_high_watermark.relative = 0.4' > "$MQ_MEM"
chmod 644 "$MQ_MEM"
sudo docker run -d --name chat-mq --hostname chat-mq \
  -p 5672:5672 -p 15672:15672 \
  -v "$MQ_MEM:/etc/rabbitmq/conf.d/99-memory.conf:ro" \
  -e RABBITMQ_DEFAULT_USER=__MQ_USER__ \
  -e RABBITMQ_DEFAULT_PASS=__MQ_PASS__ \
  rabbitmq:3.13-management
sudo docker rm -f chat-redis 2>/dev/null || true
sudo docker run -d --name chat-redis --hostname chat-redis -p 6379:6379 redis:7-alpine
sleep 20
for i in $(seq 1 90); do
  RUNNING=$(sudo docker inspect -f '{{.State.Running}}' chat-mq 2>/dev/null || echo false)
  if [ "$RUNNING" != "true" ]; then
    echo "RabbitMQ container exited. Recent logs:"
    sudo docker logs chat-mq 2>&1 | tail -120
    exit 1
  fi
  if sudo docker exec chat-mq rabbitmq-diagnostics -q ping 2>/dev/null; then
    echo "RabbitMQ OK"
    break
  fi
  sleep 2
done
if ! sudo docker exec chat-mq rabbitmq-diagnostics -q ping 2>/dev/null; then
  echo "RabbitMQ readiness timeout. Logs:"
  sudo docker logs chat-mq 2>&1 | tail -120
  exit 1
fi
for j in $(seq 1 60); do
  if sudo docker exec chat-redis redis-cli ping 2>/dev/null | grep -q PONG; then
    echo "Redis OK"
    exit 0
  fi
  sleep 1
done
echo "Redis readiness timeout. Logs:"
sudo docker logs chat-redis 2>&1 | tail -80
exit 1
'@

    $mqScriptTemplateElastiCache = @'
set -euo pipefail
sudo systemctl is-active docker >/dev/null 2>&1 || sudo systemctl start docker
sudo docker rm -f chat-mq 2>/dev/null || true
sudo docker rm -f chat-redis 2>/dev/null || true
MQ_MEM=/home/ec2-user/cs6650-rabbit-99-memory.conf
printf '%s\n' 'vm_memory_high_watermark.relative = 0.4' > "$MQ_MEM"
chmod 644 "$MQ_MEM"
sudo docker run -d --name chat-mq --hostname chat-mq \
  -p 5672:5672 -p 15672:15672 \
  -v "$MQ_MEM:/etc/rabbitmq/conf.d/99-memory.conf:ro" \
  -e RABBITMQ_DEFAULT_USER=__MQ_USER__ \
  -e RABBITMQ_DEFAULT_PASS=__MQ_PASS__ \
  rabbitmq:3.13-management
sleep 20
for i in $(seq 1 90); do
  RUNNING=$(sudo docker inspect -f '{{.State.Running}}' chat-mq 2>/dev/null || echo false)
  if [ "$RUNNING" != "true" ]; then
    echo "RabbitMQ container exited. Recent logs:"
    sudo docker logs chat-mq 2>&1 | tail -120
    exit 1
  fi
  if sudo docker exec chat-mq rabbitmq-diagnostics -q ping 2>/dev/null; then
    echo "RabbitMQ OK"
    break
  fi
  sleep 2
done
if ! sudo docker exec chat-mq rabbitmq-diagnostics -q ping 2>/dev/null; then
  echo "RabbitMQ readiness timeout. Logs:"
  sudo docker logs chat-mq 2>&1 | tail -120
  exit 1
fi
echo "RabbitMQ OK (app Redis: ElastiCache; no chat-redis on this host)"
exit 0
'@

    $mqScriptTemplate = if (-not [string]::IsNullOrEmpty($out.RedisElasticacheHost)) { $mqScriptTemplateElastiCache } else { $mqScriptTemplateDockerRedis }
    $mqScript = $mqScriptTemplate.Replace('__MQ_USER__', $mqUser).Replace('__MQ_PASS__', $mqPass)
    Invoke-RemoteBashFromFile -Target $mqTarget -ScriptBody $mqScript -RemotePath '/tmp/cs6650-deploy-mq.sh'
    }
}

if ($Phase -eq 'All' -or $Phase -eq 'Apps') {
    $mqHost = $out.RabbitHost
    $dbJdbcBase = if ($out.PostgresMode -eq 'ec2') {
        "jdbc:postgresql://$($out.PostgresPriv):6432/chatdb"
    }
    else {
        $out.PostgresJdbc
    }
    # 必须用 ${dbJdbcBase}：否则 PowerShell 会把 "$dbJdbcBase?rewrite..." 解析坏，consumerJdbc 变成 "=true"。
    $consumerJdbc = if ($dbJdbcBase -match '\?') {
        "${dbJdbcBase}&rewriteBatchedInserts=true"
    }
    else {
        "${dbJdbcBase}?rewriteBatchedInserts=true"
    }
    $enableReadReplica = ($out.PostgresMode -eq 'rds' -and -not [string]::IsNullOrEmpty($out.PostgresReadReplicaJdbc))
    # Redis: ElastiCache OR Docker on Postgres EC2 OR Docker on Rabbit EC2 (private IP)
    $redisHost = $null
    $redisPort = 6379
    if (-not [string]::IsNullOrEmpty($out.RedisElasticacheHost)) {
        $redisHost = $out.RedisElasticacheHost
        if ($null -ne $out.RedisElasticachePort) {
            $redisPort = [int]$out.RedisElasticachePort
        }
    }
    elseif ($out.PostgresMode -eq 'ec2' -and -not [string]::IsNullOrEmpty($out.PostgresPriv)) {
        $redisHost = $out.PostgresPriv
    }
    elseif ($out.RabbitMode -eq 'ec2' -and -not [string]::IsNullOrEmpty($out.RabbitPriv)) {
        $redisHost = $out.RabbitPriv
    }
    $redisEnabled = -not [string]::IsNullOrEmpty($redisHost)
    $appRedisEnabled = $redisEnabled
    if ($AppRedisDisabled) {
        $appRedisEnabled = $false
        Write-Host "App Redis: forced OFF (-AppRedisDisabled); SERVER_/CONSUMER_REDIS_ENABLED=false (ElastiCache unchanged)." -ForegroundColor Yellow
    }

    $serverEnvLines = @(
        "SPRING_RABBITMQ_HOST=$mqHost"
        "SPRING_RABBITMQ_USERNAME=$mqUser"
        "SPRING_RABBITMQ_PASSWORD=$mqPass"
        "SPRING_DATASOURCE_URL=$dbJdbcBase"
        "SPRING_DATASOURCE_USERNAME=$dbUser"
        "SPRING_DATASOURCE_PASSWORD=$dbPass"
        "SERVER_METRICS_ALLOW_HTTP_MV_REFRESH=true"
    )
    if ($enableReadReplica) {
        $serverEnvLines += @(
            "SERVER_METRICS_READ_REPLICA_ENABLED=true"
            "SERVER_METRICS_READ_REPLICA_JDBC_URL=$($out.PostgresReadReplicaJdbc)"
            "SERVER_METRICS_READ_REPLICA_USERNAME=$dbUser"
            "SERVER_METRICS_READ_REPLICA_PASSWORD=$dbPass"
        )
    }
    else {
        $serverEnvLines += "SERVER_METRICS_READ_REPLICA_ENABLED=false"
    }
    if ($appRedisEnabled) {
        $serverEnvLines += @(
            "SERVER_REDIS_ENABLED=true"
            "SPRING_DATA_REDIS_HOST=$redisHost"
            "SPRING_DATA_REDIS_PORT=$redisPort"
        )
    }
    else {
        $serverEnvLines += "SERVER_REDIS_ENABLED=false"
    }

    $consumerEnvLines = @(
        "SPRING_RABBITMQ_HOST=$mqHost"
        "SPRING_RABBITMQ_USERNAME=$mqUser"
        "SPRING_RABBITMQ_PASSWORD=$mqPass"
        "SPRING_DATASOURCE_URL=$consumerJdbc"
        "SPRING_DATASOURCE_USERNAME=$dbUser"
        "SPRING_DATASOURCE_PASSWORD=$dbPass"
    )
    if ($appRedisEnabled) {
        $consumerEnvLines += @(
            "CONSUMER_REDIS_ENABLED=true"
            "SPRING_DATA_REDIS_HOST=$redisHost"
            "SPRING_DATA_REDIS_PORT=$redisPort"
        )
    }
    else {
        $consumerEnvLines += "CONSUMER_REDIS_ENABLED=false"
    }

    $serverEnvFile = $serverEnvLines -join "`n"
    $consumerEnvFile = $consumerEnvLines -join "`n"

    $useAsgS3 = -not [string]::IsNullOrEmpty($out.AppArtifactsBucket)

    if ($SkipServer -and $SkipConsumer) {
        Write-Host "`n=== 应用层: 跳过 server 与 consumer（-SkipServer -SkipConsumer）===" -ForegroundColor DarkGray
    }
    elseif (-not $useAsgS3) {
        $serverEnv = ($serverEnvLines | ForEach-Object { "export $_" }) -join '; '
        $consumerEnv = ($consumerEnvLines | ForEach-Object { "export $_" }) -join '; '
        if (-not $SkipServer) {
            Write-Host "`n=== server-v2 instances (SSH; enable_asg_s3_app_tier=false) ===" -ForegroundColor Cyan
            $serverJarLen = (Get-Item -LiteralPath $serverJarPath).Length
            foreach ($pub in $out.ServerPubs) {
                $target = "ec2-user@$pub"
                if (-not $Force -and (Test-RemoteServerAppReady -Target $target -ExpectedJarBytes $serverJarLen)) {
                    Write-Host "  -> $pub （已就绪：JAR 字节一致且 /health 可用，跳过）" -ForegroundColor DarkGray
                    continue
                }
                Write-Host "  -> $pub"
                Invoke-RemoteOneLiner -Target $target -Line 'mkdir -p /home/ec2-user/app; pkill -f server-v2.jar || true'
                Copy-RemoteTextFile -Target $target -Text $serverEnvFile -RemotePath '/home/ec2-user/app/server.env'
                & scp @SshBase $serverJarPath "${target}:/home/ec2-user/app/server-v2.jar"
                if ($LASTEXITCODE -ne 0) { throw "scp server failed: $pub" }
                $start = "$serverEnv; " + (Get-RemoteJavaStartBody -JarFileName 'server-v2.jar' -LogFileName 'server.log')
                Invoke-RemoteOneLiner -Target $target -Line $start
            }
        }
        else {
            Write-Host "`n=== server-v2: 跳过（-SkipServer）===" -ForegroundColor DarkGray
        }
        if (-not $SkipConsumer) {
            Write-Host "`n=== consumer-v3 instances (SSH) ===" -ForegroundColor Cyan
            $consumerJarLen = (Get-Item -LiteralPath $consumerJarPath).Length
            foreach ($pub in $out.ConsumerPubs) {
                $ct = "ec2-user@$pub"
                if (-not $Force -and (Test-RemoteConsumerAppReady -Target $ct -ExpectedJarBytes $consumerJarLen)) {
                    Write-Host "  -> $pub （已就绪：JAR 字节一致且 actuator/health 可用，跳过）" -ForegroundColor DarkGray
                    continue
                }
                Write-Host "  -> $pub"
                Invoke-RemoteOneLiner -Target $ct -Line 'mkdir -p /home/ec2-user/app; pkill -f consumer-v3.jar || true'
                Copy-RemoteTextFile -Target $ct -Text $consumerEnvFile -RemotePath '/home/ec2-user/app/consumer.env'
                & scp @SshBase $consumerJarPath "${ct}:/home/ec2-user/app/consumer-v3.jar"
                if ($LASTEXITCODE -ne 0) { throw "scp consumer failed: $pub" }
                $startC = "$consumerEnv; " + (Get-RemoteJavaStartBody -JarFileName 'consumer-v3.jar' -LogFileName 'consumer.log')
                Invoke-RemoteOneLiner -Target $ct -Line $startC
            }
        }
        else {
            Write-Host "`n=== consumer-v3: 跳过（-SkipConsumer）===" -ForegroundColor DarkGray
        }
    }
    else {
        Assert-AwsCli
        $awsRegion = $out.AwsRegion
        $bucket = $out.AppArtifactsBucket
        $prefix = $out.AppArtifactsPrefix.TrimEnd('/')
        $s3base = "s3://$bucket/$prefix"

        if (-not $SkipServer) {
            Write-Host "`n=== server-v2 -> S3 ($s3base/) ===" -ForegroundColor Cyan
            $utf8 = New-Object System.Text.UTF8Encoding $false
            $tmpSrvEnv = Join-Path $env:TEMP ("cs6650-server-" + [Guid]::NewGuid().ToString() + ".env")
            try {
                [System.IO.File]::WriteAllText($tmpSrvEnv, (Normalize-BashText $serverEnvFile) + "`n", $utf8)
                Write-Host "  -> server-v2.jar"
                & aws s3 cp $serverJarPath "$s3base/server-v2.jar" --region $awsRegion
                if ($LASTEXITCODE -ne 0) { throw "aws s3 cp server-v2.jar 失败" }
                Write-Host "  -> server.env"
                & aws s3 cp $tmpSrvEnv "$s3base/server.env" --region $awsRegion
                if ($LASTEXITCODE -ne 0) { throw "aws s3 cp server.env 失败" }
            }
            finally {
                Remove-Item -LiteralPath $tmpSrvEnv -Force -ErrorAction SilentlyContinue
            }
        }
        else {
            Write-Host "`n=== server-v2: 跳过（-SkipServer）===" -ForegroundColor DarkGray
        }

        if (-not $SkipConsumer) {
            Write-Host "`n=== consumer-v3 -> S3 ($s3base/) ===" -ForegroundColor Cyan
            $utf8 = New-Object System.Text.UTF8Encoding $false
            $tmpConsEnv = Join-Path $env:TEMP ("cs6650-consumer-" + [Guid]::NewGuid().ToString() + ".env")
            try {
                [System.IO.File]::WriteAllText($tmpConsEnv, (Normalize-BashText $consumerEnvFile) + "`n", $utf8)
                Write-Host "  -> consumer-v3.jar"
                & aws s3 cp $consumerJarPath "$s3base/consumer-v3.jar" --region $awsRegion
                if ($LASTEXITCODE -ne 0) { throw "aws s3 cp consumer-v3.jar 失败" }
                Write-Host "  -> consumer.env"
                & aws s3 cp $tmpConsEnv "$s3base/consumer.env" --region $awsRegion
                if ($LASTEXITCODE -ne 0) { throw "aws s3 cp consumer.env 失败" }
            }
            finally {
                Remove-Item -LiteralPath $tmpConsEnv -Force -ErrorAction SilentlyContinue
            }
        }
        else {
            Write-Host "`n=== consumer-v3: 跳过（-SkipConsumer）===" -ForegroundColor DarkGray
        }

        if ($SkipInstanceRefresh) {
            Write-Host "`n已跳过 ASG instance-refresh（-SkipInstanceRefresh）。新实例 user_data 会从 S3 拉包；已运行实例需手动 refresh 或 SSH 重启。" -ForegroundColor Yellow
        }
        else {
            if (-not $SkipServer -and -not [string]::IsNullOrEmpty($out.ServerAsgName)) {
                Write-Host "`n=== server ASG instance refresh ===" -ForegroundColor Cyan
                Start-AsgInstanceRefresh -AsgName $out.ServerAsgName -Region $awsRegion
            }
            if (-not $SkipConsumer -and -not [string]::IsNullOrEmpty($out.ConsumerAsgName)) {
                Write-Host "`n=== consumer ASG instance refresh ===" -ForegroundColor Cyan
                Start-AsgInstanceRefresh -AsgName $out.ConsumerAsgName -Region $awsRegion
            }
        }
    }
}

Write-Host "`n=== Done ===" -ForegroundColor Green
Write-Host "curl http://$($out.AlbDns)/health"
Write-Host "日志（ASG 实例）: SSH 后 sudo tail -f /home/ec2-user/app/server.log 或 consumer.log（systemd: cs6650-server / cs6650-consumer）"
