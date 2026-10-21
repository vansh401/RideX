# Checks that the whole dockerised system works, by talking to it the way a browser does: through nginx on port 3000.
#
#   .\scripts\docker-smoke.ps1                 read-only checks, plus one test ride that is created and cancelled
#   .\scripts\docker-smoke.ps1 -SkipRide       read-only checks only
#   .\scripts\docker-smoke.ps1 -Resilience     also stops the pricing container for a few seconds, to see the system cope
#
# Needs: the stack running (docker compose up -d --build) and the sample rider from docs\api-samples (localhost data only).

param([switch]$SkipRide, [switch]$Resilience)

$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$base = 'http://localhost:3000'
$script:failed = 0

function Check($name, [bool]$ok, $detail = '') {
    if ($ok) { Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green }
    else { Write-Host ("  FAIL  {0}  {1}" -f $name, $detail) -ForegroundColor Red; $script:failed++ }
}
function Http($method, $path, $token = $null, $body = $null) {
    # curl.exe prints the status code last, after a newline; the body is everything before it.
    # A request body goes through a temporary file: Windows PowerShell mangles the quotes of JSON passed as an argument.
    $args = @('-s', '-m', '20', '-X', $method, '-w', '\n%{http_code}', "$base$path")
    if ($token) { $args += @('-H', "Authorization: Bearer $token") }
    $tmp = $null
    if ($body) {
        $tmp = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
        $args += @('-H', 'Content-Type: application/json', '--data-binary', "@$tmp")
    }
    $out = (& curl.exe @args) -join "`n"
    if ($tmp) { Remove-Item $tmp -ErrorAction SilentlyContinue }
    $i = $out.LastIndexOf("`n")
    [pscustomobject]@{ Status = [int]$out.Substring($i + 1).Trim(); Body = $(if ($i -gt 0) { $out.Substring(0, $i) } else { '' }) }
}

Write-Host "`n1. Containers" -ForegroundColor Cyan
$expected = 'ridex-postgres', 'ridex-redis', 'ridex-kafka', 'ridex-mailpit', 'ridex-core', 'ridex-notification', 'ridex-payment',
            'ridex-pricing', 'ridex-analytics', 'ridex-gateway', 'ridex-web'
foreach ($name in $expected) {
    $state = docker inspect $name --format '{{.State.Health.Status}}' 2>$null
    Check "$name is healthy" ($state -eq 'healthy') "(state: $state)"
}

Write-Host "`n2. The website (nginx)" -ForegroundColor Cyan
$index = Http GET '/'
Check 'GET / serves the app' ($index.Status -eq 200 -and $index.Body -match '<div id="root"')
$deep = Http GET '/rides/some-deep-link'
Check 'a deep link falls back to index.html (single-page app)' ($deep.Status -eq 200 -and $deep.Body -match '<div id="root"')
$headers = (& curl.exe -s -I "$base/") -join "`n"
Check 'index.html is never cached' ($headers -match '(?i)cache-control:\s*no-cache')
Check 'security headers are set' ($headers -match '(?i)x-content-type-options:\s*nosniff' -and $headers -match '(?i)x-frame-options:\s*DENY')
Check 'the nginx version is hidden' ($headers -notmatch '(?i)server:\s*nginx/\d')
$asset = [regex]::Match($index.Body, '/assets/[^"]+\.js').Value
if ($asset) {
    $assetHeaders = (& curl.exe -s -I "$base$asset") -join "`n"
    Check 'hashed assets are cached for a year' ($assetHeaders -match '(?i)immutable')
}

Write-Host "`n3. The API through nginx -> gateway -> services" -ForegroundColor Cyan
Check 'GET /api/health (core)' ((Http GET '/api/health').Status -eq 200)
Check 'a protected path without a token is refused at the edge (401)' ((Http GET '/api/rides').Status -eq 401)
Check 'internal paths are not reachable from outside (404 or 405, never 200)' ((Http POST '/internal/quotes').Status -in 404, 405)
$login = Http POST '/api/auth/login' $null (Get-Content -Raw (Join-Path $root 'docs\api-samples\login-ok.json'))
Check 'sample rider can log in' ($login.Status -eq 200)
$token = $null
if ($login.Status -eq 200) { $token = ($login.Body | ConvertFrom-Json).accessToken }
if ($token) {
    Check 'GET /api/users/me (core)' ((Http GET '/api/users/me' $token).Status -eq 200)
    Check 'GET /api/notifications (notification service)' ((Http GET '/api/notifications' $token).Status -eq 200)
    $fare = '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":13.0827,"longitude":77.5877}}'
    Check 'POST /api/fares/estimate (pricing service)' ((Http POST '/api/fares/estimate' $token $fare).Status -eq 200)
    Check 'a rider cannot read admin payments (403 from the payment service)' ((Http GET '/api/admin/payments' $token).Status -eq 403)
    Check 'a rider cannot read admin events (403 from the analytics service)' ((Http GET '/api/admin/events/lag' $token).Status -eq 403)

    if (-not $SkipRide) {
        # Booking makes the core ask the pricing service for a quote with the shared SERVICE_TOKEN: proves the secret reached both containers
        $ride = '{"category":"CAB","pickup":{"address":"Smoke test pickup","location":{"latitude":12.9716,"longitude":77.5946}},"dropoff":{"address":"Smoke test dropoff","location":{"latitude":13.0827,"longitude":77.5877}}}'
        $current = Http GET '/api/rides/current' $token
        if ($current.Status -eq 200 -and $current.Body.Trim() -ne '') {
            Write-Host '  SKIP  the sample rider already has a ride in progress; not creating another' -ForegroundColor Yellow
        }
        else {
            $created = Http POST '/api/rides' $token $ride
            Check 'POST /api/rides: core -> pricing with the service token from the secret' ($created.Status -eq 201) "(status $($created.Status): $($created.Body))"
            if ($created.Status -eq 201) {
                $id = ($created.Body | ConvertFrom-Json).id
                Check 'the test ride is cancelled again' ((Http POST "/api/rides/$id/cancel" $token).Status -eq 200)
            }
        }
    }
}

Write-Host "`n4. WebSocket upgrade through nginx and the gateway" -ForegroundColor Cyan
$ws = (& curl.exe -s -i -m 5 -N -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' `
        -H 'Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==' -H 'Origin: http://localhost:3000' "$base/ws") -join "`n"
Check 'the WebSocket handshake answers 101 Switching Protocols' ($ws -match 'HTTP/1.1 101')

Write-Host "`n5. Hardening" -ForegroundColor Cyan
foreach ($c in 'ridex-core', 'ridex-gateway', 'ridex-pricing') {
    $uid = (docker exec $c id -u).Trim()
    Check "$c runs as a normal user (uid $uid), not root" ($uid -ne '0')
}
$webUid = (docker exec ridex-web id -u).Trim()
Check "ridex-web runs as a normal user (uid $webUid)" ($webUid -ne '0')
docker exec ridex-core sh -c 'touch /app/should-fail 2>/dev/null'
Check 'the core cannot write outside its data volume (read-only filesystem)' ($LASTEXITCODE -ne 0)
$env = docker inspect ridex-core --format '{{json .Config.Env}}'
Check 'no password or token appears in the core container environment' ($env -notmatch '(?i)password=[^"]{3,}' -and $env -notmatch 'SERVICE_TOKEN=')
Check 'the secrets are mounted as files' ((docker exec ridex-core ls /run/secrets) -join ',' -match 'DB_PASSWORD')
foreach ($port in 8080, 8081, 8082, 8083, 8084) {
    $open = (Test-NetConnection -ComputerName 127.0.0.1 -Port $port -WarningAction SilentlyContinue -InformationLevel Quiet)
    Check "port $port is NOT reachable from the host (services are internal)" (-not $open)
}

if ($Resilience -and $token) {
    Write-Host "`n6. Resilience: pricing goes away and comes back" -ForegroundColor Cyan
    docker stop ridex-pricing | Out-Null
    $down = Http POST '/api/fares/estimate' $token '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":13.0827,"longitude":77.5877}}'
    Check 'with pricing stopped the answer is a clean gateway error (503 or 504), not a hang' ($down.Status -in 503, 504) "(status $($down.Status))"
    Check 'the rest of the system keeps working' ((Http GET '/api/users/me' $token).Status -eq 200)
    docker start ridex-pricing | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    do { Start-Sleep 3; $state = (docker inspect ridex-pricing --format '{{.State.Health.Status}}').Trim() } while ($state -ne 'healthy' -and (Get-Date) -lt $deadline)
    Check 'pricing is healthy again' ($state -eq 'healthy')
    Check 'fares work again, nothing restarted' ((Http POST '/api/fares/estimate' $token '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":13.0827,"longitude":77.5877}}').Status -eq 200)
}

Write-Host ''
if ($script:failed -eq 0) { Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "$script:failed CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
