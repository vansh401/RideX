# Step 11.4: WebSockets across replicas. Do live updates reach a browser whose connection is held by a DIFFERENT core pod than the one
# that handled the REST call? And what happens to the connections when their pod dies?
#
#   .\scripts\k8s-scale-out.ps1         first: two core pods
#   .\scripts\k8s-websocket-demo.ps1
#
# Needs Node.js (any recent version). The client is scripts\loadtest\ws-cross-pod.mjs; this script starts it, counts how many connections
# each core pod holds, deletes the pod that holds most, and prints the result.
#
# How the connections per pod are counted: the gateway does not pass the core pod's X-Instance header on a WebSocket upgrade, so the script
# reads /proc/net/tcp inside each core pod and counts ESTABLISHED connections on port 8080 (before the client starts and after it connected).

. "$PSScriptRoot\k8s-common.ps1"
Assert-Cluster
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is not installed (https://nodejs.org).' }
$cores = @(Get-Pods 'core' | Where-Object { ($_.status.conditions | Where-Object { $_.type -eq 'Ready' }).status -eq 'True' } | ForEach-Object { $_.metadata.name })
if ($cores.Count -lt 2) { throw "Only $($cores.Count) core pod is ready. Run .\scripts\k8s-scale-out.ps1 first." }

function Count-Connections($pod) {
    # state 01 = ESTABLISHED, 1F90 = port 8080
    $lines = kubectl exec -n $script:ns $pod -- sh -c 'cat /proc/net/tcp /proc/net/tcp6' 2>$null
    @($lines | Where-Object { $_ -match '^\s*\d+:\s+\S+:1F90\s+\S+\s+01\s' }).Count
}

$token = Get-TestToken
$out = Join-Path ([IO.Path]::GetTempPath()) 'ridex-ws.txt'
Remove-Item $out -ErrorAction SilentlyContinue
$before = @{}
foreach ($c in $cores) { $before[$c] = Count-Connections $c }

Title 'Opening 12 connections, then changing a ride through REST, then killing the pod that holds most connections'
$client = Start-Process node -ArgumentList @((Join-Path $script:root 'scripts\loadtest\ws-cross-pod.mjs'), '--token', $token, '--sockets', '12', '--kill-phase') `
    -RedirectStandardOutput $out -RedirectStandardError "$out.err" -WindowStyle Hidden -PassThru

$killed = $false
$measured = $false
while (-not $client.HasExited) {
    Start-Sleep 1
    if (-not (Test-Path $out)) { continue }
    $text = Get-Content $out -Raw
    if (-not $measured -and $text -match 'SOCKETS_OPEN') {
        $measured = $true
        $delta = @{}
        Write-Host 'Connections held by each core pod (ESTABLISHED on port 8080, increase since before the client started):' -ForegroundColor Cyan
        foreach ($c in $cores) { $delta[$c] = (Count-Connections $c) - $before[$c]; Write-Host ('  {0,-28} +{1}' -f $c, $delta[$c]) }
    }
    if ($measured -and -not $killed -and $text -match 'READY_FOR_KILL') {
        $victim = ($delta.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1).Key
        Info "deleting core pod $victim (it holds the most connections)"
        kubectl delete pod $victim -n $script:ns --wait=false | Out-Null
        $killed = $true
    }
}
Get-Content $out
if ((Test-Path "$out.err") -and (Get-Item "$out.err").Length -gt 0) { Write-Host '--- errors ---' -ForegroundColor Yellow; Get-Content "$out.err" }
Wait-Rollout 'core' 300 | Out-Null

Write-Host @"

How to read this
  A WebSocket is ONE long TCP connection. The ingress and gateway pick a core pod when it is opened and every message afterwards goes to that
  pod, so connections spread over the pods by connection, not by message (the counts above).
  The live updates still reach every connection because of the Redis bridge (Phase 5): the pod that handles a REST call publishes the
  event to Redis, and EVERY core pod delivers it to the connections it holds. Without it, only the connections on the same pod would be told.
  When a pod dies its connections are cut. The client has to reconnect (the demo client does, after a second) and may land on another pod.
  Updates published while a connection was down are NOT replayed: after reconnecting, a client should fetch the current state through REST.
"@
