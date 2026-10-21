# Step 11.1: how is traffic spread over several copies? Measures it, at two levels, with different kinds of clients.
#
#   .\scripts\k8s-scale-out.ps1     first: 2 gateway pods and 3 pricing pods
#   .\scripts\k8s-lb-demo.ps1
#
# Every response says which pods answered: X-Gateway-Instance (the gateway pod) and X-Instance (the pricing pod). The load generator counts them.
#
#   LEVEL 1  ingress controller -> gateway pods      (the ingress picks a pod for EACH REQUEST)
#   LEVEL 2  gateway pod        -> pricing pods      (the gateway talks to the Service; kube-proxy picks a pod for each CONNECTION)

param([switch]$ConnectionPerRequest)   # also measure with the gateway opening a NEW connection for every request (see the end)

. "$PSScriptRoot\k8s-common.ps1"
Assert-Cluster
$token = Get-TestToken
$out = Join-Path ([IO.Path]::GetTempPath()) 'ridex-lb'
New-Item -ItemType Directory -Force $out | Out-Null

Title 'The copies running now'
foreach ($app in 'gateway', 'pricing') { Info ("$app : " + ((Get-Pods $app | ForEach-Object { $_.metadata.name }) -join ', ')) }

function Run($title, $concurrency, $seconds) {
    Title $title
    $file = Join-Path $out "lb-$concurrency.txt"
    $p = Start-LoadTest -Path '/api/fares/estimate' -Token $token -OutFile $file -Method POST -Body $script:fareBody -Concurrency $concurrency -Duration $seconds
    $p | Wait-Process
    Show-Summary $file
}

Run 'A. ONE client, one request at a time (a single reused connection)' 1 15
Run 'B. FORTY clients at once (many connections)' 40 20

if ($ConnectionPerRequest) {
    # The gateway's HTTP client keeps a pool of connections open. Telling it not to pool (a new connection for every request) makes kube-proxy
    # choose a pod for EVERY request. Spring's relaxed binding turns the environment variable into the property
    # spring.cloud.gateway.server.webflux.httpclient.pool.type=DISABLED, so no rebuild is needed.
    $variable = 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_TYPE'
    Title 'Changing the gateway: a new connection for every request (rolling restart) ...'
    kubectl set env deployment/gateway "$variable=DISABLED" -n $script:ns | Out-Null
    Wait-Rollout 'gateway' 300 | Out-Null
    Start-Sleep 5
    Run 'A2. ONE client, one request at a time, NO connection reuse' 1 15
    Run 'B2. FORTY clients at once, NO connection reuse' 40 20
    Title 'Putting the gateway back (connection pool on) ...'
    kubectl set env deployment/gateway "$variable-" -n $script:ns | Out-Null
    Wait-Rollout 'gateway' 300 | Out-Null
}

Write-Host @"

How to read this
  Gateway pods (level 1): the ingress controller chooses a gateway pod for EACH REQUEST, so even a single client is split 50/50.
  Pricing pods (level 2): the gateway keeps pooled connections to the Service. kube-proxy picks a pod only when a NEW connection is opened,
  so every pooled connection is pinned to one pod for its whole life. The result is uneven (not a third each) and depends on how many
  connections each gateway pod happened to open. This is "connection-level" (layer 4) balancing.
  With -ConnectionPerRequest the pool is switched off and the spread becomes even, but every request pays for a new TCP connection
  (latency and CPU). Real fixes: a client-side balancer (Spring Cloud LoadBalancer with a headless Service), a service mesh, or limiting
  the lifetime of pooled connections.
"@
