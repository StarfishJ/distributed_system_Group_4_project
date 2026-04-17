Param(
    [string]$ComposeProject = "chat",
    [string]$ServerUrl = "http://localhost:8080",
    [string]$HealthPath = "/actuator/health",
    [string]$MetricsPath = "/metrics"
)

function Get-DockerExe {
    $preferred = @(
        "$env:ProgramFiles\Docker\Docker\resources\bin\docker.exe",
        "${env:ProgramFiles(x86)}\Docker\Docker\resources\bin\docker.exe"
    )
    foreach ($p in $preferred) {
        if ($p -and (Test-Path -LiteralPath $p)) {
            return (Resolve-Path -LiteralPath $p).Path
        }
    }

    $cmd = Get-Command docker.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -notmatch '(?i)\\Windows\\System32\\docker\.exe$') {
        return $cmd.Source
    }

    throw @"
Could not find a real Docker CLI (docker.exe).

PowerShell was resolving 'docker' to C:\Windows\System32\docker (Store stub), which breaks pipelines.
Install Docker Desktop, or ensure docker.exe is on PATH from:
  $env:ProgramFiles\Docker\Docker\resources\bin\
"@
}

$DockerExe = Get-DockerExe
Write-Host "Using Docker CLI: $DockerExe`n"

# Matches docker-compose.yml: chatdb-pg, chat-mq, chat-redis, chat-pgbouncer-*
$StackNamePattern = 'chatdb-pg|chat-mq|chat-redis|chat-pgbouncer|postgres|rabbitmq|redis|pgbouncer'

Write-Host "=== 1. Docker container status ==="
try {
    $table = & $DockerExe ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ($table | Out-String)
    } else {
        $lines = @($table -split "`r?`n" | Where-Object { $_ -ne '' })
        if ($lines.Count -gt 0) {
            $lines[0]
            $lines | Select-Object -Skip 1 | Select-String -Pattern $StackNamePattern -CaseSensitive:$false
        }
    }
} catch {
    Write-Warning "docker ps failed: $($_.Exception.Message)"
}

Write-Host "`n=== 2. Port conflict check (5432/5672/6379/8080/8081) ==="
$ports = 5432, 5672, 6379, 8080, 8081
foreach ($p in $ports) {
    Write-Host "`n-- Port $p --"
    netstat -ano | Select-String ":$p\s" | Select-Object -First 5
}

Write-Host "`n=== 3. Postgres readiness check (pg_isready) ==="
try {
    $psLines = & $DockerExe ps --format "{{.ID}} {{.Names}}" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ($psLines | Out-String)
        $pg = $null
    } else {
        $pgPattern = 'chatdb-pg|\bpostgres\b'
        $pg = @($psLines | Select-String -Pattern $pgPattern -CaseSensitive:$false | ForEach-Object {
            ($_.Line -split '\s+', 2)[0]
        } | Select-Object -First 1)[0]
    }
    if ($pg) {
        & $DockerExe exec $pg pg_isready -U chat -d chatdb
    } else {
        Write-Warning "Postgres container not found (expected name: chatdb-pg)."
        Write-Host "Fallback: PgBouncer write pool TCP 127.0.0.1:6432"
        $tcp = Test-NetConnection -ComputerName 127.0.0.1 -Port 6432 -WarningAction SilentlyContinue
        if ($tcp.TcpTestSucceeded) {
            Write-Host "6432 is open (PgBouncer responding). If Postgres were down, PgBouncer would usually not stay healthy."
        } else {
            Write-Warning "6432 not reachable — start stack: docker compose up -d (in database folder)."
        }
    }
} catch {
    Write-Warning "Postgres check failed: $($_.Exception.Message)"
}

Write-Host "`n=== 4. Redis connectivity & key overview ==="
try {
    $psLines = & $DockerExe ps --format "{{.ID}} {{.Names}}" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning ($psLines | Out-String)
        $redis = $null
    } else {
        $redis = @($psLines | Select-String -Pattern 'redis' -CaseSensitive:$false | ForEach-Object {
            ($_.Line -split '\s+', 2)[0]
        } | Select-Object -First 1)[0]
    }
    if ($redis) {
        & $DockerExe exec $redis redis-cli PING
        & $DockerExe exec $redis redis-cli DBSIZE
        Write-Host "Sample keys chat:consumer:* (first 10):"
        @(& $DockerExe exec $redis redis-cli --scan --pattern "chat:consumer:*" 2>&1) | Select-Object -First 10
        Write-Host "Sample keys presence:room:* (first 10):"
        @(& $DockerExe exec $redis redis-cli --scan --pattern "presence:room:*" 2>&1) | Select-Object -First 10
    } else {
        Write-Warning "Redis container not found."
    }
} catch {
    Write-Warning "Redis check failed: $($_.Exception.Message)"
}

Write-Host "`n=== 5. RabbitMQ management port (http://localhost:15672) ==="
Write-Host "Please open http://localhost:15672 (user/pass: guest/guest) to check the queues."

Write-Host "`n=== 6. Application health check ==="
$healthUrl = "$ServerUrl$HealthPath"
$metricsUrl = "$ServerUrl$MetricsPath"

Write-Host "GET $healthUrl"
try {
    Invoke-WebRequest $healthUrl -UseBasicParsing -TimeoutSec 5 | Select-Object -ExpandProperty StatusCode
} catch {
    Write-Warning "Health check failed: $($_.Exception.Message)"
}

Write-Host "`nGET $metricsUrl (only get length)"
try {
    $res = Invoke-WebRequest $metricsUrl -UseBasicParsing -TimeoutSec 10
    Write-Host "Length = $($res.Content.Length)"
} catch {
    Write-Warning "Metrics check failed: $($_.Exception.Message)"
}

Write-Host "`n=== 7. Brief conclusion (manual check) ==="
Write-Host "1) Are there any port conflicts in docker / netstat?"
Write-Host "2) Is Postgres pg_isready OK?"
Write-Host "3) Is Redis PING/DBSIZE OK?"
Write-Host "4) Is the /actuator/health status code 200?"
Write-Host "5) Can /metrics return JSON?"
