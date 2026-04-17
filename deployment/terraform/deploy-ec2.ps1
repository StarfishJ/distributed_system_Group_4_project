#Requires -Version 5.1
<#
.SYNOPSIS
  从本机自动部署 EC2 路径：Postgres+PgBouncer+Redis、RabbitMQ（Docker）、server-v2×N、consumer-v3。

.DESCRIPTION
  需要：OpenSSH（ssh/scp）、terraform、Java/Maven（默认会 **mvn clean package**，除非 -SkipBuild）。
  上传前会校验 JAR 为 Spring Boot fat jar，且在使用 -SkipBuild 时若源码/pom 比 JAR 新会直接报错。
  在 terraform apply 之后，于本机 PowerShell 执行。
  **Windows**：若私钥因 ACL 过宽无法加载，脚本会**自动 icacls 收紧并重试**一次（也可用 **-RepairKeyAcl** 在首轮校验前就收紧）。
  默认会 **SSH 探针**检测 Postgres / Rabbit / 各 JAR 是否已就绪：通过则跳过该步（应用要求远端 JAR **字节大小与本地一致** 且 **本机 health 可访问**）。
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
    [switch] $Force,
    [switch] $RepairKeyAcl
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
        RabbitPub    = $obj.rabbitmq_public_ip.value
        RabbitPriv   = $obj.rabbitmq_private_ip.value
        PostgresPub  = $obj.postgres_public_ip.value
        PostgresPriv = $obj.postgres_private_ip.value
        ConsumerPub  = $obj.consumer_public_ip.value
        ServerPubs   = @($obj.server_public_ips.value)
        AlbDns       = $obj.alb_dns_name.value
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
if ([string]::IsNullOrEmpty($out.RabbitPub) -or [string]::IsNullOrEmpty($out.PostgresPub)) {
    throw "rabbitmq_public_ip / postgres_public_ip 为空。请确认 enable_eks=false 且已 terraform apply。"
}

Write-Host "=== Terraform outputs ===" -ForegroundColor Cyan
Write-Host "  ALB: $($out.AlbDns)"
Write-Host "  Rabbit: $($out.RabbitPub) (priv $($out.RabbitPriv))"
Write-Host "  Postgres: $($out.PostgresPub) (priv $($out.PostgresPriv))"
Write-Host "  Servers: $($out.ServerPubs -join ', ')"
Write-Host "  Consumer: $($out.ConsumerPub)"
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

$dbUser = 'chat'
$dbPass = 'chat'
# 非 guest：guest 默认仅允许本机连接，server/consumer 从私网连 broker 会失败
$mqUser = 'chatmq'
$mqPass = 'chatmq'

if ($Phase -eq 'All' -or $Phase -eq 'DataPlane') {
    $pgTarget = "ec2-user@$($out.PostgresPub)"
    $mqTarget = "ec2-user@$($out.RabbitPub)"

    $probePg = @'
set -e
sudo docker info >/dev/null 2>&1 || exit 1
test "$(sudo docker inspect -f '{{.State.Running}}' chatdb-pg 2>/dev/null)" = "true" || exit 1
sudo docker exec chatdb-pg pg_isready -U chat -d chatdb >/dev/null 2>&1 || exit 1
'@

    $probeMq = @'
set -e
sudo docker info >/dev/null 2>&1 || exit 1
test "$(sudo docker inspect -f '{{.State.Running}}' chat-mq 2>/dev/null)" = "true" || exit 1
sudo docker exec chat-mq rabbitmq-diagnostics -q ping >/dev/null 2>&1 || exit 1
'@

    if ($SkipPostgres) {
        Write-Host "`n=== Postgres EC2: 跳过（-SkipPostgres）===" -ForegroundColor DarkGray
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

    $pgScript = @'
set -euo pipefail
rm -rf /home/ec2-user/cs6650-database
mv /home/ec2-user/cs6650-database-src /home/ec2-user/cs6650-database
cd /home/ec2-user/cs6650-database
sudo systemctl is-active docker >/dev/null 2>&1 || sudo systemctl start docker
# 部分 AL2023/Yum 源无 docker-compose-plugin；用官方二进制（x86_64 / aarch64）
compose_up() {
  if sudo docker compose version >/dev/null 2>&1; then
    sudo docker compose up -d postgres pgbouncer-write pgbouncer-read redis
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    sudo docker-compose up -d postgres pgbouncer-write pgbouncer-read redis
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
  sudo /usr/local/bin/docker-compose up -d postgres pgbouncer-write pgbouncer-read redis
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
'@
    Invoke-RemoteBashFromFile -Target $pgTarget -ScriptBody $pgScript -RemotePath '/tmp/cs6650-deploy-pg.sh'
    }

    if ($SkipRabbit) {
        Write-Host "`n=== RabbitMQ EC2: 跳过（-SkipRabbit）===" -ForegroundColor DarkGray
    }
    elseif (-not $Force -and (Invoke-RemoteBashProbe -Target $mqTarget -ScriptBody $probeMq)) {
        Write-Host "`n=== RabbitMQ EC2: 探针通过，跳过部署（-Force 可强制重跑）===" -ForegroundColor DarkGray
    }
    else {
    Write-Host "`n=== RabbitMQ EC2 ===" -ForegroundColor Cyan
    $mqScriptTemplate = @'
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
    exit 0
  fi
  sleep 2
done
echo "RabbitMQ readiness timeout. Logs:"
sudo docker logs chat-mq 2>&1 | tail -120
exit 1
'@
    $mqScript = $mqScriptTemplate.Replace('__MQ_USER__', $mqUser).Replace('__MQ_PASS__', $mqPass)
    Invoke-RemoteBashFromFile -Target $mqTarget -ScriptBody $mqScript -RemotePath '/tmp/cs6650-deploy-mq.sh'
    }
}

if ($Phase -eq 'All' -or $Phase -eq 'Apps') {
    $pgPriv = $out.PostgresPriv
    $mqPriv = $out.RabbitPriv

    $serverEnv = @(
        "export SPRING_RABBITMQ_HOST=$mqPriv"
        "export SPRING_RABBITMQ_USERNAME=$mqUser"
        "export SPRING_RABBITMQ_PASSWORD=$mqPass"
        "export SPRING_DATASOURCE_URL=jdbc:postgresql://${pgPriv}:6432/chatdb"
        "export SPRING_DATASOURCE_USERNAME=$dbUser"
        "export SPRING_DATASOURCE_PASSWORD=$dbPass"
        "export SERVER_METRICS_READ_REPLICA_ENABLED=false"
        "export SPRING_DATA_REDIS_HOST=$pgPriv"
        "export SERVER_METRICS_ALLOW_HTTP_MV_REFRESH=true"
    ) -join '; '

    $consumerEnv = @(
        "export SPRING_RABBITMQ_HOST=$mqPriv"
        "export SPRING_RABBITMQ_USERNAME=$mqUser"
        "export SPRING_RABBITMQ_PASSWORD=$mqPass"
        "export SPRING_DATASOURCE_URL=jdbc:postgresql://${pgPriv}:6432/chatdb?rewriteBatchedInserts=true"
        "export SPRING_DATASOURCE_USERNAME=$dbUser"
        "export SPRING_DATASOURCE_PASSWORD=$dbPass"
        "export SPRING_DATA_REDIS_HOST=$pgPriv"
    ) -join '; '

    if ($SkipServer) {
        Write-Host "`n=== server-v2: 跳过（-SkipServer）===" -ForegroundColor DarkGray
    }
    else {
        Write-Host "`n=== server-v2 instances ===" -ForegroundColor Cyan
        $serverJarLen = (Get-Item -LiteralPath $serverJarPath).Length
        foreach ($pub in $out.ServerPubs) {
            $target = "ec2-user@$pub"
            if (-not $Force -and (Test-RemoteServerAppReady -Target $target -ExpectedJarBytes $serverJarLen)) {
                Write-Host "  -> $pub （已就绪：JAR 字节一致且 /health 可用，跳过）" -ForegroundColor DarkGray
                continue
            }
            Write-Host "  -> $pub"
            Invoke-RemoteOneLiner -Target $target -Line 'mkdir -p /home/ec2-user/app; pkill -f server-v2.jar || true'
            & scp @SshBase $serverJarPath "${target}:/home/ec2-user/app/server-v2.jar"
            if ($LASTEXITCODE -ne 0) { throw "scp server failed: $pub" }
            $start = "$serverEnv; " + (Get-RemoteJavaStartBody -JarFileName 'server-v2.jar' -LogFileName 'server.log')
            Invoke-RemoteOneLiner -Target $target -Line $start
        }
    }

    if ($SkipConsumer) {
        Write-Host "`n=== consumer-v3: 跳过（-SkipConsumer）===" -ForegroundColor DarkGray
    }
    else {
        $ct = "ec2-user@$($out.ConsumerPub)"
        $consumerJarLen = (Get-Item -LiteralPath $consumerJarPath).Length
        if (-not $Force -and (Test-RemoteConsumerAppReady -Target $ct -ExpectedJarBytes $consumerJarLen)) {
            Write-Host "`n=== consumer-v3: 已就绪（JAR 一致且 actuator/health 可用，跳过）===" -ForegroundColor DarkGray
        }
        else {
            Write-Host "`n=== consumer-v3 ===" -ForegroundColor Cyan
            Invoke-RemoteOneLiner -Target $ct -Line 'mkdir -p /home/ec2-user/app; pkill -f consumer-v3.jar || true'
            & scp @SshBase $consumerJarPath "${ct}:/home/ec2-user/app/consumer-v3.jar"
            if ($LASTEXITCODE -ne 0) { throw 'scp consumer failed' }
            $startC = "$consumerEnv; " + (Get-RemoteJavaStartBody -JarFileName 'consumer-v3.jar' -LogFileName 'consumer.log')
            Invoke-RemoteOneLiner -Target $ct -Line $startC
        }
    }
}

Write-Host "`n=== Done ===" -ForegroundColor Green
Write-Host "curl http://$($out.AlbDns)/health"
Write-Host "日志: SSH 后 tail -f /home/ec2-user/app/server.log 或 consumer.log"
