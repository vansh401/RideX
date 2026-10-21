# Proves that the observability stack sees the system: metrics, dashboards, JSON logs, traces across Kafka, and (with -Alerts) an alert email.
#
#   .\scripts\obs-smoke.ps1             metrics, dashboard queries, logs, traces
#   .\scripts\obs-smoke.ps1 -Alerts     also stops the pricing service to make an alert fire and arrive in Mailpit (about 4 minutes)
#
# Needs: .\scripts\obs-up.ps1 running, and the sample rider (docs\api-samples, localhost data only).

param([switch]$Alerts)

$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$web = 'http://localhost:3000'
$prom = 'http://localhost:9090'
$jaeger = 'http://localhost:16686'
$mailpit = 'http://localhost:8025'
$script:failed = 0
$script:warned = 0

function Check($name, [bool]$ok, $detail = '') {
    if ($ok) { Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green }
    else { Write-Host ("  FAIL  {0}  {1}" -f $name, $detail) -ForegroundColor Red; $script:failed++ }
}
function Warn($name, $detail = '') { Write-Host ("  WARN  {0}  {1}" -f $name, $detail) -ForegroundColor Yellow; $script:warned++ }

function Http($method, $path, $token = $null, $body = $null) {
    $args = @('-s', '-m', '20', '-X', $method, '-w', '\n%{http_code}', "$web$path")
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
function Json($url) { try { (& curl.exe -s -m 20 $url) -join '' | ConvertFrom-Json } catch { $null } }
function PromQuery($expr) {
    # Invoke-RestMethod, not curl.exe: Windows PowerShell 5.1 drops the double quotes of an argument passed to a native program
    try { Invoke-RestMethod -Uri ("$prom/api/v1/query?query=" + [uri]::EscapeDataString($expr)) -TimeoutSec 20 }
    catch { [pscustomobject]@{ status = 'error'; error = $_.Exception.Message; data = $null } }
}

# ---------------------------------------------------------------------------------------------------------------------------
Write-Host "`n1. Prometheus scrapes every service" -ForegroundColor Cyan
$all = @{ 'ridex-app' = 'ridex-core'; 'notification-service' = 'ridex-notification'; 'payment-service' = 'ridex-payment'; 'pricing-service' = 'ridex-pricing'; 'analytics-service' = 'ridex-analytics'; 'api-gateway' = 'ridex-gateway' }
# a service that is not running (parked to save memory) is not expected to be scraped
$running = @(docker ps --format '{{.Names}}')
$expected = @($all.Keys | Where-Object { $running -contains $all[$_] })
$parked = @($all.Keys | Where-Object { $running -notcontains $all[$_] })
foreach ($p in $parked) { Warn "$p is not running, so it is not checked" }
$deadline = (Get-Date).AddSeconds(120)
do {
    $up = PromQuery 'up{job="ridex"}'
    $upApps = @($up.data.result | Where-Object { $_.value[1] -eq '1' } | ForEach-Object { $_.metric.application })
    if (@($expected | Where-Object { $upApps -notcontains $_ }).Count -eq 0) { break }
    Start-Sleep 5
} while ((Get-Date) -lt $deadline)
foreach ($app in $expected) { Check "$app is scraped (up = 1)" ($upApps -contains $app) }

# ---------------------------------------------------------------------------------------------------------------------------
Write-Host "`n2. Traffic: login, fare estimates, a ride that is requested and cancelled" -ForegroundColor Cyan
$login = Http POST '/api/auth/login' $null (Get-Content -Raw (Join-Path $root 'docs\api-samples\login-ok.json'))
Check 'the sample rider can log in' ($login.Status -eq 200) "(status $($login.Status); register the sample rider first, see docs/api-samples)"
if ($login.Status -ne 200) { exit 1 }
$token = ($login.Body | ConvertFrom-Json).accessToken
$fare = '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":13.0827,"longitude":77.5877}}'
1..40 | ForEach-Object { $null = Http POST '/api/fares/estimate' $token $fare }
$null = Http GET '/api/rides' $token
$null = Http GET '/api/users/me' $token
$null = Http GET '/api/rides/does-not-exist' $token       # a 4xx, so the error panels have something to compare with
$rideBody = '{"category":"CAB","pickup":{"address":"Obs smoke pickup","location":{"latitude":12.9716,"longitude":77.5946}},"dropoff":{"address":"Obs smoke dropoff","location":{"latitude":13.0827,"longitude":77.5877}}}'
$rideId = $null
$current = Http GET '/api/rides/current' $token
if ($current.Status -eq 200 -and $current.Body.Trim() -ne '') {
    Warn 'the sample rider already has a ride in progress; no new ride, so the trace and log checks use older data'
}
else {
    $created = Http POST '/api/rides' $token $rideBody
    Check 'a ride is requested (gateway -> core -> pricing)' ($created.Status -eq 201) "(status $($created.Status))"
    if ($created.Status -eq 201) {
        $rideId = ($created.Body | ConvertFrom-Json).id
        Start-Sleep 2
        $null = Http POST "/api/rides/$rideId/cancel" $token
    }
}
Write-Host '  waiting for the metrics to be scraped and the events to travel through Kafka ...'
Start-Sleep 35

# ---------------------------------------------------------------------------------------------------------------------------
Write-Host "`n3. Every query of the Grafana dashboard returns something" -ForegroundColor Cyan
$dashboard = Get-Content -Raw (Join-Path $root 'infrastructure\observability\grafana\dashboards\ridex-overview.json') | ConvertFrom-Json
foreach ($panel in $dashboard.panels) {
    foreach ($t in $panel.targets) {
        $expr = $t.expr.Replace('$application', '.*').Replace('$__rate_interval', '2m')
        $r = PromQuery $expr
        if ($r.status -ne 'success') { Check "panel '$($panel.title)'" $false "(query error: $($r.error))" }
        elseif (@($r.data.result).Count -eq 0) { Warn "panel '$($panel.title)' has no data yet" }
        else { Check "panel '$($panel.title)'" $true }
    }
}
$series = PromQuery 'ridex_rides'
Check 'the business gauge ridex_rides exists, one series per ride status' (@($series.data.result).Count -eq 8)
$changes = PromQuery 'sum(ridex_ride_status_changes_total)'
Check 'ride status changes were counted' (@($changes.data.result).Count -gt 0 -and [double]$changes.data.result[0].value[1] -gt 0)
$lag = PromQuery 'kafka_consumer_fetch_manager_records_lag_max'
Check 'Kafka consumer lag is exported by the consuming services' (@($lag.data.result).Count -gt 0)
$hikari = PromQuery 'hikaricp_connections_max'
Check 'database pool metrics are exported' (@($hikari.data.result).Count -gt 0)

# ---------------------------------------------------------------------------------------------------------------------------
Write-Host "`n4. Logs are JSON, with request id, user id and ride id" -ForegroundColor Cyan
$lines = @(docker logs ridex-core --since 3m 2>&1 | Where-Object { $_ -like '{*' })
Check 'the core writes JSON log lines' ($lines.Count -gt 0)
$parsed = @($lines | ForEach-Object { try { $_ | ConvertFrom-Json } catch { $null } } | Where-Object { $_ })
Check 'every JSON line parses and has a message and a timestamp' ($parsed.Count -eq $lines.Count -and @($parsed | Where-Object { -not $_.message -or -not $_.'@timestamp' }).Count -eq 0)
if ($rideId) {
    $forRide = @($parsed | Where-Object { $_.rideId -eq $rideId })
    Check "log lines carry the rideId of the test ride ($($forRide.Count) found)" ($forRide.Count -gt 0)
    $story = @($forRide | Where-Object { $_.message -like 'Ride status changed*' })
    Check 'the ride story is in the logs (status changes)' ($story.Count -ge 2)
    Check 'those lines also carry the traceId' (@($story | Where-Object { $_.traceId }).Count -gt 0)
    foreach ($l in $story) { Write-Host ("        {0}  {1}  trace={2}" -f $l.'@timestamp', $l.message, $l.traceId) -ForegroundColor DarkGray }
    $payLines = @(docker logs ridex-payment --since 3m 2>&1 | Where-Object { $_ -like '{*' } | ForEach-Object { try { $_ | ConvertFrom-Json } catch { $null } } | Where-Object { $_ -and $_.rideId -eq $rideId })
    Check "the payment service logs the same ride id when it handles the event ($($payLines.Count) lines)" ($payLines.Count -gt 0)
}
$withUser = @($parsed | Where-Object { $_.userId })
Check 'requests of a logged-in user carry the userId' ($withUser.Count -gt 0)
$leak = @($lines | Where-Object { $_ -match '(?i)asha\.rao@|"password"|eyJ[A-Za-z0-9_-]{20,}\.' })
Check 'no email address, password or token appears in the core log' ($leak.Count -eq 0) "($($leak.Count) lines)"

# ---------------------------------------------------------------------------------------------------------------------------
Write-Host "`n5. One trace follows the request across services and through Kafka" -ForegroundColor Cyan
# Jaeger 2 answers in OTLP-JSON under /api/v3: resourceSpans (one per service and batch) -> scopeSpans -> spans
$services = (Json "$jaeger/api/v3/services").services
Check 'Jaeger knows the services' (@($services | Where-Object { $_ -ne 'jaeger' }).Count -ge 4) "(services: $($services -join ', '))"
$from = (Get-Date).ToUniversalTime().AddMinutes(-6).ToString('yyyy-MM-ddTHH:mm:ssZ')
$to = (Get-Date).ToUniversalTime().AddMinutes(1).ToString('yyyy-MM-ddTHH:mm:ssZ')
$url = "$jaeger/api/v3/traces?query.service_name=ridex-app&query.operation_name=" + [uri]::EscapeDataString('http post /api/rides') +
    "&query.start_time_min=$from&query.start_time_max=$to&query.search_depth=20"
$answer = try { Invoke-RestMethod -Uri $url -TimeoutSec 30 } catch { $null }
$byTrace = @{}
foreach ($rs in @($answer.result.resourceSpans)) {
    $svc = ($rs.resource.attributes | Where-Object { $_.key -eq 'service.name' }).value.stringValue
    foreach ($ss in $rs.scopeSpans) {
        foreach ($sp in $ss.spans) {
            if (-not $byTrace.ContainsKey($sp.traceId)) { $byTrace[$sp.traceId] = @() }
            $byTrace[$sp.traceId] += [pscustomobject]@{ Service = $svc; Name = $sp.name }
        }
    }
}
$best = $null; $bestCount = 0
foreach ($key in $byTrace.Keys) {
    $n = @($byTrace[$key] | ForEach-Object { $_.Service } | Sort-Object -Unique).Count
    if ($n -gt $bestCount) { $best = $key; $bestCount = $n }
}
if ($best) {
    $spans = $byTrace[$best]
    $svcs = @($spans | ForEach-Object { $_.Service } | Sort-Object -Unique)
    Write-Host ("  the widest ride-booking trace ({0}) has {1} spans in: {2}" -f $best, @($spans).Count, ($svcs -join ', ')) -ForegroundColor DarkGray
    Check 'one trace spans the gateway and the core' (($svcs -contains 'api-gateway') -and ($svcs -contains 'ridex-app'))
    Check 'the same trace reaches the pricing service (HTTP propagation from the core)' ($svcs -contains 'pricing-service')
    Check 'it contains the "outbox publish" span (the trace survived the outbox)' (@($spans | Where-Object { $_.Name -like 'outbox publish*' }).Count -gt 0)
    $consumers = @($svcs | Where-Object { $_ -in 'payment-service', 'notification-service', 'analytics-service' })
    Check 'and continues in services that consumed the event from Kafka (propagation through Kafka headers)' ($consumers.Count -gt 0) "(services: $($svcs -join ', '))"
    Write-Host ("  operations: {0}" -f ((@($spans | ForEach-Object { "$($_.Service):$($_.Name)" } | Sort-Object -Unique)) -join ' | ')) -ForegroundColor DarkGray
    Write-Host "  open it:  $jaeger/trace/$best" -ForegroundColor DarkGray
}
else { Check 'a ride-booking trace was found' $false }
$noise = $null
try { $noise = Invoke-RestMethod -Uri "$jaeger/api/v3/traces?query.service_name=api-gateway&query.operation_name=$([uri]::EscapeDataString('http get /actuator/health'))&query.start_time_min=$from&query.start_time_max=$to&query.search_depth=5" -TimeoutSec 30 } catch { }   # 404 = "no such trace" = what we want
Check 'health checks are not traced (no /actuator spans)' (@(@($noise.result.resourceSpans) | Where-Object { $_ }).Count -eq 0)

# ---------------------------------------------------------------------------------------------------------------------------
Write-Host "`n6. Alert rules are loaded" -ForegroundColor Cyan
$rules = Json "$prom/api/v1/rules"
$names = @($rules.data.groups | ForEach-Object { $_.rules } | ForEach-Object { $_.name })
Check 'Prometheus loaded the 11 alert rules' ($names.Count -eq 11) "(found $($names.Count))"
$am = Json 'http://localhost:9093/api/v2/status'
Check 'Alertmanager is running' ($null -ne $am)

if ($Alerts) {
    Write-Host "`n7. Break something: stop the pricing service and wait for ServiceDown" -ForegroundColor Cyan
    $knownMail = @((Json "$mailpit/api/v1/messages").messages | ForEach-Object { $_.ID })
    docker stop ridex-pricing | Out-Null
    $fired = $false
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline -and -not $fired) {
        Start-Sleep 10
        $firing = Json "$prom/api/v1/alerts"
        $fired = @($firing.data.alerts | Where-Object { $_.labels.alertname -eq 'ServiceDown' -and $_.labels.application -eq 'pricing-service' -and $_.state -eq 'firing' }).Count -gt 0
    }
    Check 'ServiceDown is firing in Prometheus' $fired
    $mail = $null
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline -and -not $mail) {
        Start-Sleep 5
        $msgs = (Json "$mailpit/api/v1/messages").messages
        $mail = @($msgs | Where-Object { $knownMail -notcontains $_.ID -and $_.Subject -like '[[]FIRING*ServiceDown*pricing-service*' } | Select-Object -First 1)[0]
    }
    Check 'the alert email arrived in Mailpit' ($null -ne $mail)
    if ($mail) { Write-Host ("        subject: {0}" -f $mail.Subject) -ForegroundColor DarkGray }
    docker start ridex-pricing | Out-Null
    $resolved = $false
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline -and -not $resolved) {
        Start-Sleep 10
        $firing = Json "$prom/api/v1/alerts"
        $resolved = @($firing.data.alerts | Where-Object { $_.labels.alertname -eq 'ServiceDown' -and $_.labels.application -eq 'pricing-service' }).Count -eq 0
    }
    Check 'after the service is back the alert resolves' $resolved
    $ok = $null
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline -and -not $ok) {
        $msgs = (Json "$mailpit/api/v1/messages").messages
        $ok = @($msgs | Where-Object { $knownMail -notcontains $_.ID -and $_.Subject -like '[[]RESOLVED*ServiceDown*pricing-service*' })[0]
        if (-not $ok) { Start-Sleep 5 }
    }
    Check 'a "resolved" email follows' ($null -ne $ok)
}

Write-Host ''
if ($script:warned -gt 0) { Write-Host "$script:warned warning(s)" -ForegroundColor Yellow }
if ($script:failed -eq 0) { Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "$script:failed CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
