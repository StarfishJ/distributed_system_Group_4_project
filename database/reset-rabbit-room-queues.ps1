# Delete room.1 .. room.20 so Spring can redeclare them with DLX/TTL settings.
# Run after RabbitMQ is up. Requires rabbitmqctl on PATH (local install) OR set $RabbitContainer.
#
# Local:   .\reset-rabbit-room-queues.ps1
# Docker (compose 默认容器名 chat-mq):  $env:RabbitContainer = "chat-mq"; .\reset-rabbit-room-queues.ps1

$ErrorActionPreference = "Continue"
$vhost = "/"

function Invoke-RabbitCtl {
    param([string[]]$Args)
    if ($env:RabbitContainer) {
        docker exec $env:RabbitContainer rabbitmqctl @Args
    } else {
        & rabbitmqctl @Args
    }
}

Write-Host "Deleting room.1 .. room.20 on vhost '$vhost' ..."
for ($i = 1; $i -le 20; $i++) {
    $q = "room.$i"
    Invoke-RabbitCtl @("delete_queue", "-p", $vhost, $q) 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  deleted $q"
    }
}
Write-Host "Done. Restart server-v2 and consumer-v3 so queues are recreated with DLX."
