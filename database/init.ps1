# CS6650 Assignment 3: Initialize database via Docker (no local psql required)
# Run from database directory: .\init.ps1

$ErrorActionPreference = "Stop"

$container = "chatdb-pg"
$files = @("01_schema.sql", "02_indexes.sql", "03_views.sql")
$maxAttempts = 30

foreach ($f in $files) {
    if (-not (Test-Path $f)) {
        Write-Error "File not found: $f"
        exit 1
    }
}

Write-Host "Waiting for PostgreSQL container '$container' to become ready..."
$ready = $false
for ($i = 1; $i -le $maxAttempts; $i++) {
    docker exec -i $container pg_isready -U chat -d chatdb | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $ready = $true
        break
    }
    Start-Sleep -Seconds 2
}

if (-not $ready) {
    Write-Error "PostgreSQL is not ready after $maxAttempts attempts."
    exit 1
}

Write-Host "Initializing schema in $container..."
foreach ($f in $files) {
    Write-Host "Applying $f..."
    Get-Content $f -Raw | docker exec -i $container psql -v ON_ERROR_STOP=1 -U chat -d chatdb
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed while applying $f"
        exit 1
    }
}
Write-Host "Done."
