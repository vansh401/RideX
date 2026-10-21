# Stops RideX and the monitoring stack. Data (database, Grafana, Prometheus history) is kept.
#   .\scripts\obs-down.ps1            stop and remove the containers
#   .\scripts\obs-down.ps1 -Volumes   also delete the monitoring history (Prometheus, Grafana, Alertmanager)

param([switch]$Volumes)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location (Join-Path $root 'infrastructure\docker-compose')
try {
    $ErrorActionPreference = 'Continue'   # docker compose writes its progress to stderr
    $arguments = @('-f', 'docker-compose.yml', '-f', (Join-Path $root 'infrastructure\observability\docker-compose.observability.yml'), 'down')
    if ($Volumes) {
        # only the monitoring volumes: never the database
        docker compose @arguments
        foreach ($v in 'prometheus_data', 'alertmanager_data', 'grafana_data') { docker volume rm "docker-compose_$v" 2>$null | Out-Null }
    }
    else { docker compose @arguments }
}
finally { Pop-Location }
