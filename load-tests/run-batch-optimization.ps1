# Assignment 3: Batch Size Optimization Script
# Run from project root: .\load-tests\run-batch-optimization.ps1
# Requires: Server, RabbitMQ, Database running. Consumer-v3 will be restarted manually.

$projectRoot = Split-Path -Parent $PSScriptRoot   # load-tests -> project root
if (-not (Test-Path "$projectRoot\client\client_part2\target\chat-client-part2-0.0.1-SNAPSHOT.jar")) {
    Write-Host "Client JAR not found. Run: mvn clean package -DskipTests -f client/client_part2/pom.xml"
    exit 1
}

$batchSizes = @(100, 500, 1000, 5000)
$flushIntervals = @(100, 500, 1000)
$runsPerCombo = 2   # 2 runs per combo = 24 total; enough to check stability, ~30-45 min
$messagesPerTest = 50000
$resultsDir = Join-Path $PSScriptRoot "results"
$csvPath = Join-Path $resultsDir "batch_optimization.csv"

if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir -Force | Out-Null }

if (-not (Test-Path $csvPath)) {
    "batch_size,flush_interval_ms,run,throughput_msg_per_sec,p95_latency_ms,success_rate,runtime_sec" | Out-File $csvPath -Encoding utf8
}

$clientJar = Join-Path $projectRoot "client\client_part2\target\chat-client-part2-0.0.1-SNAPSHOT.jar"
$serverUrl = "http://localhost:8080"

foreach ($batch in $batchSizes) {
    foreach ($interval in $flushIntervals) {
        for ($r = 1; $r -le $runsPerCombo; $r++) {
            Write-Host ""
            Write-Host "========================================" -ForegroundColor Cyan
            Write-Host "Combo: batch=$batch, flush=$interval ms, run $r/$runsPerCombo" -ForegroundColor Cyan
            Write-Host "========================================" -ForegroundColor Cyan
            Write-Host "1. Stop consumer-v3 if running (Ctrl+C)"
            Write-Host "2. Start consumer-v3 with:"
            Write-Host "   mvn spring-boot:run -f consumer-v3/pom.xml -Dconsumer.batch-size=$batch -Dconsumer.flush-interval-ms=$interval" -ForegroundColor Yellow
            Write-Host "3. Wait for 'Started ConsumerApplication'"
            Write-Host "4. Press Enter to run load test..." -ForegroundColor Green
            Read-Host

            $output = & java -jar $clientJar $serverUrl $messagesPerTest --no-warmup 2>&1 | Out-String

            $throughput = 0
            if ($output -match "Throughput\s+:\s+([\d.]+)\s+msg/s") { $throughput = [double]$Matches[1] }
            $p95 = 0
            if ($output -match "P95 Latency\s+:\s+(\d+)\s+ms") { $p95 = [int]$Matches[1] }
            $runtime = 0
            if ($output -match "Total Runtime\s+:\s+([\d.]+)\s+sec") { $runtime = [double]$Matches[1] }
            $successRate = 0
            if ($output -match "Success Rate\s+:\s+([\d.]+)%") { $successRate = [double]$Matches[1] }

            $line = "$batch,$interval,$r,$throughput,$p95,$successRate,$runtime"
            Add-Content -Path $csvPath -Value $line
            Write-Host "Recorded: throughput=$throughput msg/s, p95=$p95 ms" -ForegroundColor Green
        }
    }
}

Write-Host ""
Write-Host "Done! Results in $csvPath"
