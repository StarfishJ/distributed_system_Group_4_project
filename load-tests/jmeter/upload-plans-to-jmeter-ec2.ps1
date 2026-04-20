#Requires -Version 5.1
<#
.SYNOPSIS
  在 terraform apply 且 enable_jmeter_ec2=true 之后，把本目录下的 JMeter 计划与属性文件 scp 到 JMeter 负载机 EC2。

.EXAMPLE
  cd "<repo>\load-tests\jmeter"
  .\upload-plans-to-jmeter-ec2.ps1 -KeyPath "C:\Users\you\.ssh\cs6650-aws"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $KeyPath,

    [string] $TerraformDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..\deployment\terraform')).Path
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $KeyPath)) { throw "Key not found: $KeyPath" }
$KeyPath = (Resolve-Path -LiteralPath $KeyPath).Path

if (-not (Test-Path -LiteralPath $TerraformDir)) {
    throw "Terraform 目录不存在: $TerraformDir （请在仓库根目录下使用本脚本）"
}
$jmeterTf = Join-Path $TerraformDir 'jmeter.tf'
if (-not (Test-Path -LiteralPath $jmeterTf)) {
    throw @"
未找到 $jmeterTf。请 git pull 更新本仓库（需包含 deployment/terraform/jmeter.tf 与 outputs 中的 jmeter_public_ip），再重试。
"@
}

Push-Location $TerraformDir
try {
    $outJson = & terraform output -json 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "terraform output 失败（请先在此目录执行 terraform init）: $outJson"
    }
    $all = $outJson | ConvertFrom-Json
    if (-not $all.PSObject.Properties.Name -contains 'jmeter_public_ip') {
        throw @"
当前 Terraform 配置里没有名为 jmeter_public_ip 的 output。请确认已保存并包含最新 deployment/terraform/outputs.tf（含 jmeter_public_ip / jmeter_ssh_example），然后在本目录执行:
  terraform init
  terraform validate
若仍没有该 output，说明本地代码不是最新，请 git pull。
"@
    }
    $ip = [string]$all.jmeter_public_ip.value
    if ([string]::IsNullOrWhiteSpace($ip) -or $ip -eq 'null') {
        throw @"
jmeter_public_ip 为空。请在 terraform.tfvars 中设置 enable_jmeter_ec2 = true（且 enable_eks = false、enable_alb = true），然后执行 terraform apply 创建 JMeter EC2。
"@
    }
    $alb = ''
    if ($all.PSObject.Properties.Name -contains 'alb_dns_name') {
        $alb = [string]$all.alb_dns_name.value
    }
}
finally { Pop-Location }

$remote = "ec2-user@${ip}:~/jmeter-plan/"
$here = $PSScriptRoot
$files = @(
    'assignment-baseline-100k-5min.jmx',
    'assignment-baseline-30min-duration.jmx',
    'assignment-stress-30min.jmx',
    'health-baseline.jmx',
    'jmeter-run.properties'
) | ForEach-Object { Join-Path $here $_ } | Where-Object { Test-Path -LiteralPath $_ }

if ($files.Count -eq 0) { throw "No JMX/properties found under $here" }

Write-Host "JMeter EC2: $ip" -ForegroundColor Cyan
Write-Host "scp -> $remote" -ForegroundColor Gray
& scp.exe -i $KeyPath -o StrictHostKeyChecking=accept-new @files $remote
if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)" }

Write-Host @"

已上传。SSH 示例:
  ssh -i $KeyPath ec2-user@$ip

在 EC2 上执行（把 ALB 换成你的域名）:
  source /etc/profile.d/jmeter.sh
  cd ~/jmeter-plan
  /opt/apache-jmeter/bin/jmeter -n -t assignment-stress-30min.jmx -q jmeter-run.properties -Jhost="$alb" -Jport=80 -l ~/jmeter-results/stress.jtl -e -o ~/jmeter-results/stress-report

terraform 输出的 ALB DNS: $alb
"@ -ForegroundColor Green
