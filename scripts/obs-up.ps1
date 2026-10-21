# Starts RideX with observability: the Docker Compose system plus Prometheus, Alertmanager, Grafana and Jaeger.
#
#   .\scripts\obs-up.ps1             start (builds nothing; run `docker compose build` first if the Java code changed)
#   .\scripts\obs-up.ps1 -Build      rebuild the images first
#   .\scripts\obs-down.ps1           stop (keeps the data)
#
# Needs about 3.5 GB in Docker's VM. Stop the kind cluster first:  docker stop ridex-control-plane
# On a small machine you can start only part of the system:  .\scripts\obs-up.ps1 -Only core,gateway,pricing,payment

param([switch]$Build, [string[]]$Only = @())

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$compose = Join-Path $root 'infrastructure\docker-compose'
$overlay = Join-Path $root 'infrastructure\observability\docker-compose.observability.yml'

& (Join-Path $PSScriptRoot 'docker-init.ps1') | Out-Null

# The Grafana admin password: random, created once, git-ignored (the folder is ignored as a whole). No newline at the end.
$secret = Join-Path $compose 'secrets\GRAFANA_ADMIN_PASSWORD'
if (-not (Test-Path $secret)) {
    $bytes = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [IO.File]::WriteAllText($secret, [Convert]::ToBase64String($bytes).Replace('/', '_').Replace('+', '-'), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'Created the Grafana admin password in infrastructure\docker-compose\secrets\GRAFANA_ADMIN_PASSWORD' -ForegroundColor Green
}

$services = @()
if ($Only.Count -gt 0) {
    # the monitoring containers plus the chosen part of the system (and what it needs, which compose adds by itself)
    $services = @('prometheus', 'alertmanager', 'grafana', 'jaeger', 'mailpit') + $Only
}

Push-Location $compose
try {
    $arguments = @('-f', 'docker-compose.yml', '-f', $overlay, 'up', '-d')
    if ($Build) { $arguments += '--build' }
    $ErrorActionPreference = 'Continue'   # docker compose writes its progress to stderr
    docker compose @arguments @services
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
}
finally { Pop-Location }

Write-Host @"

Grafana       http://localhost:3001     (dashboard "RideX overview"; admin password: infrastructure\docker-compose\secrets\GRAFANA_ADMIN_PASSWORD)
Prometheus    http://localhost:9090     (Status > Targets, Alerts)
Alertmanager  http://localhost:9093
Jaeger        http://localhost:16686
Mailpit       http://localhost:8025     (alert emails arrive here)
Website       http://localhost:3000

Try it:  .\scripts\obs-smoke.ps1
"@ -ForegroundColor Cyan
