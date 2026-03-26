# CS6650 Assignment 3: Initialize database via Docker (no psql required)
# Run from database directory: .\init.ps1

$container = "chatdb-pg"
$files = @("01_schema.sql", "02_indexes.sql", "03_views.sql")

foreach ($f in $files) {
    if (-not (Test-Path $f)) {
        Write-Error "File not found: $f"
        exit 1
    }
}

Write-Host "Initializing schema in chatdb-pg..."
foreach ($f in $files) {
    Get-Content $f -Raw | docker exec -i $container psql -U chat -d chatdb
}
Write-Host "Done."
