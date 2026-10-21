# Step 11.2: watch the Horizontal Pod Autoscaler add pods under load, then remove them when the load stops.
#
#   .\scripts\k8s-scale-out.ps1               first (creates the autoscaler, 3 to 5 pricing pods)
#   .\scripts\k8s-metrics-server.ps1          once: the add-on that measures CPU
#   .\scripts\k8s-autoscale-demo.ps1          about 7 minutes
#
# Pricing is the busy service here (every fare estimate is a request to it). The load generator sends fare estimates for -LoadSeconds with
# -Concurrency clients; every 15 seconds this script prints what the autoscaler sees and decides. Then the load stops, and you watch the pods
# go back to the minimum after the "stabilization window" of the scale-down policy (60 s here).
#
# Locally the limit is 5 pods (4 GB of memory for the whole cluster). The production overlay allows 10: same mechanism, bigger numbers.

param([int]$LoadSeconds = 180, [int]$Concurrency = 60, [int]$CooldownSeconds = 240)

. "$PSScriptRoot\k8s-common.ps1"
Assert-Cluster
cmd /c "kubectl top pods -n $script:ns >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'No CPU metrics. Install the add-on first:  .\scripts\k8s-metrics-server.ps1' }
cmd /c "kubectl get hpa pricing -n $script:ns >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'No autoscaler. Create it with:  .\scripts\k8s-scale-out.ps1' }

$token = Get-TestToken
$file = Join-Path ([IO.Path]::GetTempPath()) 'ridex-autoscale.txt'

function Snapshot($t) {
    $h = kubectl get hpa pricing -n $script:ns -o json | ConvertFrom-Json
    $cpu = $h.status.currentMetrics | Where-Object { $_.type -eq 'Resource' } | ForEach-Object { $_.resource.current.averageUtilization }
    $ready = @(Get-Pods 'pricing' | Where-Object { ($_.status.conditions | Where-Object { $_.type -eq 'Ready' }).status -eq 'True' }).Count
    $desired = $h.status.desiredReplicas
    Write-Host ('{0,5}s   cpu {1,4}% of request (target 70%)   pods: current {2}  ready {3}  wanted {4}' -f $t, $cpu, $h.status.currentReplicas, $ready, $desired)
    return $h.status.currentReplicas
}

Title 'Before the load'
$null = Snapshot 0
Title "Load: $Concurrency clients for $LoadSeconds seconds (fare estimates)"
$load = Start-LoadTest -Path '/api/fares/estimate' -Token $token -OutFile $file -Method POST -Body $script:fareBody -Concurrency $Concurrency -Duration $LoadSeconds
$peak = 0
$t = 0
while (-not $load.HasExited) {
    Start-Sleep 15; $t += 15
    $n = Snapshot $t
    if ($n -gt $peak) { $peak = $n }
}
Show-Summary $file

Title 'Load stopped. Waiting for the autoscaler to shrink again (scale-down waits for a quiet minute) ...'
$end = $t + $CooldownSeconds
while ($t -lt $end) {
    Start-Sleep 15; $t += 15
    $n = Snapshot $t
    if ($n -le 3) { break }
}
Write-Host "`nThe most pods at once: $peak (limit locally: 5, production overlay: 10)." -ForegroundColor Green
Write-Host @"

How to read this
  The autoscaler measures CPU against each pod's CPU REQUEST (not the machine): with a request of 50m, 100% means 50 millicores.
  Scale-up is quick (no waiting, at most 2 pods per 15 s); scale-down is slow on purpose, so a short pause in traffic does not throw away
  pods that would be needed again a minute later. New pods need time to start (a JVM), so the autoscaler REACTS to load; it does not prevent
  the first seconds of it. That is why the minimum (3 here) is not 1.
"@
