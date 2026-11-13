$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common-local.ps1")

$services = @(
    @{ Name = "storefront";             Port = 3000 },
    @{ Name = "admin";                  Port = 3001 },
    @{ Name = "gateway";                Port = 8085 },
    @{ Name = "chatbot-service";        Port = 8002 },
    @{ Name = "search-service";         Port = 8090 },
    @{ Name = "recommendation-service"; Port = 8091 },
    @{ Name = "catalog-service";        Port = 8092 },
    @{ Name = "identity-service";       Port = 8093 },
    @{ Name = "sales-service";          Port = 8094 }
)

$rows = foreach ($service in $services) {
    $managed = Get-ManagedLocalProcess -Name $service.Name
    $listening = Test-LocalTcpPort -Port $service.Port
    $state = if ($listening -and $managed) { "RUNNING (managed)" }
        elseif ($listening) { "RUNNING (external)" }
        elseif ($managed) { "STARTING/UNHEALTHY" }
        else { "STOPPED" }

    [pscustomobject]@{
        Service = $service.Name
        Port = $service.Port
        State = $state
        PID = if ($managed) { $managed.Id } else { "-" }
    }
}

foreach ($workerName in @("search-worker", "recommendation-worker")) {
    $managed = Get-ManagedLocalProcess -Name $workerName
    $active = $managed -or (Test-LocalProcessSlot -Name $workerName)
    $rows += [pscustomobject]@{
        Service = $workerName
        Port = "-"
        State = if ($active) { if ($managed) { "RUNNING (managed)" } else { "RUNNING (external)" } } else { "STOPPED" }
        PID = if ($managed) { $managed.Id } else { "-" }
    }
}

foreach ($infra in @(@{ Name = "redis"; Port = 6379 }, @{ Name = "rabbitmq"; Port = 5672 })) {
    $rows += [pscustomobject]@{
        Service = $infra.Name
        Port = $infra.Port
        State = if (Test-LocalTcpPort -Port $infra.Port) { "RUNNING (Docker)" } else { "STOPPED" }
        PID = "-"
    }
}

$rows | Format-Table -AutoSize
Write-Host "managed = started by start-all-local.cmd; external = terminal/IDE/another process" -ForegroundColor DarkGray
