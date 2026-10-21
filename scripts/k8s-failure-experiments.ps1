# Step 11.3: kill things while traffic is flowing, and measure what users would have seen.
#
#   .\scripts\k8s-scale-out.ps1            first: several copies of gateway, pricing and core
#   .\scripts\k8s-failure-experiments.ps1                      all experiments (about 12 minutes)
#   .\scripts\k8s-failure-experiments.ps1 -Only pricing,redis  just those
#
# Each experiment: a load generator sends requests for ~50 seconds; 12 seconds in, ONE pod is deleted. The per-second timeline shows the
# moment of the failure and the recovery. The summary at the end compares them.
#
# Experiments:  pricing  gateway  core  redis  kafka  postgres

param([string[]]$Only = @('pricing', 'gateway', 'core', 'redis', 'kafka', 'postgres'))

. "$PSScriptRoot\k8s-common.ps1"
Assert-Cluster
$token = Get-TestToken
$out = Join-Path ([IO.Path]::GetTempPath()) 'ridex-failures'
New-Item -ItemType Directory -Force $out | Out-Null

$experiments = @(
    @{ Name = 'pricing';  App = 'pricing';  Kind = 'deployment';  Path = '/api/fares/estimate'; Method = 'POST'; Body = $script:fareBody; Seconds = 50
       Expect = '3 copies: Kubernetes takes the pod out of the Service, the others carry the load. Expect (almost) no errors.' },
    @{ Name = 'gateway';  App = 'gateway';  Kind = 'deployment';  Path = '/api/fares/estimate'; Method = 'POST'; Body = $script:fareBody; Seconds = 50
       Expect = '2 copies: the ingress controller stops sending to the dead pod. Expect a few failed requests at most (connections that were open to it).' },
    @{ Name = 'core';     App = 'core';     Kind = 'deployment';  Path = '/api/users/me';       Method = 'GET';  Body = $null; Seconds = 50
       Expect = '2 copies, but the surviving core has to take double load while the new one starts (about a minute).' },
    @{ Name = 'redis';    App = 'redis';    Kind = 'statefulset'; Path = '/api/users/me';       Method = 'GET';  Body = $null; Seconds = 60
       Expect = 'ONE copy of Redis: the cache, token store and rate limits disappear for the time it takes to restart. Phase 4 chose what each feature does without Redis.' },
    @{ Name = 'kafka';    App = 'kafka';    Kind = 'statefulset'; Path = '/api/users/me';       Method = 'GET';  Body = $null; Seconds = 60
       Expect = 'ONE copy of Kafka, but nothing on this request path uses it: events wait in the outbox. Expect no errors.' },
    @{ Name = 'postgres'; App = 'postgres'; Kind = 'statefulset'; Path = '/api/rides'  ;       Method = 'GET';  Body = $null; Seconds = 90
       Expect = 'ONE copy of the database: the single point of failure. Requests that need it fail until it is back. Here that is a few seconds (a restart on a local disk); a real failure of a node or disk is far longer.' }
)

$results = @()
foreach ($e in $experiments) {
    if ($Only -notcontains $e.Name) { continue }
    Title ("Experiment: kill a $($e.App) pod")
    Info $e.Expect
    $pods = Get-Pods $e.App
    $victim = $pods[0].metadata.name
    Info ("pods before: " + (($pods | ForEach-Object { $_.metadata.name }) -join ', '))
    $file = Join-Path $out "$($e.Name).txt"
    $load = Start-LoadTest -Path $e.Path -Token $token -OutFile $file -Method $e.Method -Body $e.Body -Concurrency 10 -Duration $e.Seconds
    Start-Sleep 12
    Info "t=12s  deleting $victim"
    kubectl delete pod $victim -n $script:ns --wait=false | Out-Null
    $load | Wait-Process

    # the timeline: one line per second
    $lines = @(Get-Content $file | Where-Object { $_ -match '^\s*\d+\s+\d+\s+\d+\s+\d+\s' })
    $timeline = $lines | ForEach-Object { $f = ($_.Trim() -split '\s+'); [pscustomobject]@{ T = [int]$f[0]; Total = [int]$f[1]; Ok = [int]$f[2]; Failed = [int]$f[3] } }
    $bad = @($timeline | Where-Object { $_.Failed -gt 0 -or $_.Total -eq 0 })
    # show the seconds around the failure
    Write-Host ('{0,5} {1,7} {2,6} {3,6}' -f 't(s)', 'req/s', 'ok', 'failed')
    foreach ($row in $timeline) {
        if ($row.T -ge 10 -and $row.T -le [Math]::Min($e.Seconds - 1, 45) -and ($row.Failed -gt 0 -or $row.Total -eq 0 -or $row.T -le 14)) {
            $mark = if ($row.Failed -gt 0 -or $row.Total -eq 0) { ' <-- users affected' } else { '' }
            Write-Host ('{0,5} {1,7} {2,6} {3,6}{4}' -f $row.T, $row.Total, $row.Ok, $row.Failed, $mark) -ForegroundColor $(if ($mark) { 'Yellow' } else { 'Gray' })
        }
    }
    Show-Summary $file
    # the totals come from the generator's own summary line ("requests: N in S s ... failed: F"), not from re-adding the rows
    $m = [regex]::Match((Get-Content $file -Raw), 'requests:\s+(\d+).*?failed:\s+(\d+)')
    $total = if ($m.Success) { [int]$m.Groups[1].Value } else { 0 }
    $failed = if ($m.Success) { [int]$m.Groups[2].Value } else { 0 }
    $results += [pscustomobject]@{ Experiment = $e.Name; Requests = $total; Failed = $failed
        Success = if ($total) { '{0:N2}%' -f (100.0 * ($total - $failed) / $total) } else { 'n/a' }; AffectedSeconds = $bad.Count }

    # let the system settle before the next experiment
    Info 'waiting for the replacement to become ready ...'
    if ($e.Kind -eq 'deployment') { Wait-Rollout $e.App 300 | Out-Null } else { kubectl rollout status "statefulset/$($e.App)" -n $script:ns --timeout=300s 2>&1 | Out-Null }
    foreach ($dep in 'core', 'gateway', 'pricing') { Wait-Rollout $dep 300 | Out-Null }
    Start-Sleep 20
}

Title 'Summary'
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host @"
How to read this
  Copies hide failures. With several pods behind a Service, losing one costs (almost) nothing: that is what replicas are FOR.
  A component with ONE copy (Redis, PostgreSQL here) is an outage while it restarts, however well the rest is built. A deleted pod comes
  back in seconds, so the damage here is small (some timeouts and 500s); a lost node or disk would not be. That is the argument for
  replicated data stores (database primary + replicas, Redis Sentinel/Cluster, three Kafka brokers) in production.
  Kafka shows no errors because nothing on this request path waits for it: events queue in the outbox (Phase 6) and are sent later.
"@
