# Starts RideX backend services as background processes, each with its own log file in .\logs.
#
#   .\scripts\start-services.ps1                       core + four services + the API gateway (about 1.8 GB of memory)
#   .\scripts\start-services.ps1 -Only core,pricing,gateway    only what you need (the frontend needs the gateway; a ride cannot be booked without pricing)
#
# Needs: Docker Desktop running with  docker compose up -d  (PostgreSQL, Redis, Kafka), the DB_PASSWORD environment variable
# (the password of the "ridex" database user), and a finished  .\scripts\build-all.ps1.
#
# Optional environment variables for the first admin: ADMIN_EMAIL and ADMIN_PASSWORD. They are read by the core only; this script
# never writes them anywhere.

param(
    [ValidateSet('core', 'notification', 'payment', 'pricing', 'analytics', 'gateway')]
    [string[]]$Only = @('core', 'pricing', 'notification', 'payment', 'analytics', 'gateway')
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null

if (-not $env:DB_PASSWORD) {
    throw 'Set the DB_PASSWORD environment variable first (the password of the "ridex" database user).'
}

# The secret the core and the pricing service share for internal calls (X-Service-Token). Generated once, kept in the git-ignored
# data folder, never in a tracked file.
$dataDir = Join-Path $root 'data'
New-Item -ItemType Directory -Force $dataDir | Out-Null
$tokenFile = Join-Path $dataDir 'service-token.txt'
if (-not (Test-Path $tokenFile)) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes) | Set-Content -NoNewline -Encoding ascii $tokenFile
}
$env:SERVICE_TOKEN = (Get-Content -Raw $tokenFile).Trim()

# Memory: every service is a separate JVM. The extra flags make each one lighter (simple garbage collector, quick compiler only,
# small thread stacks); on a machine with little RAM six JVMs plus Docker can still run out, which shows up as a service that
# silently vanishes (look for hs_err_pid files). Start fewer with -Only if that happens.

# name -> (module folder, port). The core runs from its own folder because its token key and uploads live in .\data there.
$services = [ordered]@{
    core         = @{ dir = 'backend\ridex-app';            port = 8080; heap = '-Xmx450m' }
    pricing      = @{ dir = 'backend\pricing-service';      port = 8083; heap = '-Xmx200m' }
    notification = @{ dir = 'backend\notification-service'; port = 8081; heap = '-Xmx250m' }
    payment      = @{ dir = 'backend\payment-service';      port = 8082; heap = '-Xmx250m' }
    analytics    = @{ dir = 'backend\analytics-service';    port = 8084; heap = '-Xmx250m' }
    gateway      = @{ dir = 'backend\api-gateway';          port = 8000; heap = '-Xmx250m' }
}

$pidFile = Join-Path $logs 'pids.txt'
$started = @()
foreach ($name in $services.Keys) {
    if ($Only -notcontains $name) { continue }
    $svc = $services[$name]
    $workDir = Join-Path $root $svc.dir
    $jar = Get-ChildItem (Join-Path $workDir 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*original*' -and $_.Name -notlike '*sources*' } | Select-Object -First 1
    if (-not $jar) { throw "No jar for $name. Run .\scripts\build-all.ps1 first." }

    $listening = Get-NetTCPConnection -LocalPort $svc.port -State Listen -ErrorAction SilentlyContinue
    if ($listening) {
        Write-Host "$name : port $($svc.port) is already in use, skipping" -ForegroundColor Yellow
        continue
    }

    $log = Join-Path $logs "$name.log"
    $process = Start-Process -FilePath 'java' `
        -ArgumentList @($svc.heap, '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-Xss512k', '-XX:MaxMetaspaceSize=160m', '-Duser.timezone=UTC', '-jar', $jar.FullName) `
        -WorkingDirectory $workDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError (Join-Path $logs "$name.err.log")
    $started += "$name=$($process.Id)"
    Write-Host ("{0,-13} http://localhost:{1}   pid {2}   log: logs\{0}.log" -f $name, $svc.port, $process.Id) -ForegroundColor Green
}

if ($started.Count -gt 0) {
    Add-Content -Path $pidFile -Value $started
    Write-Host "`nStarted. Each service takes 20 to 40 seconds to be ready. Check:  .\scripts\status.ps1" -ForegroundColor Cyan
    Write-Host 'Stop them all:  .\scripts\stop-services.ps1'
}
