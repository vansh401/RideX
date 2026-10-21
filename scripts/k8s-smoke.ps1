# Checks the RideX deployment in the local Kubernetes cluster, the way a browser would use it (through the ingress on port 8088), and looks
# inside the cluster at the things Kubernetes is responsible for.
#
#   .\scripts\k8s-smoke.ps1             read-only checks (plus one test ride that is created and cancelled)
#   .\scripts\k8s-smoke.ps1 -SkipRide   without the test ride
#   .\scripts\k8s-smoke.ps1 -Chaos      also breaks things on purpose and watches Kubernetes repair them:
#                                         1. delete a pod            -> it is replaced, requests keep working
#                                         2. rolling restart under load -> zero failed requests (needs 2 replicas, set by the script)
#                                         3. a broken release        -> the rollout stalls, the old version keeps serving, then rollback
#                                         4. delete the database pod -> it comes back with its data (StatefulSet + volume)

param([switch]$SkipRide, [switch]$Chaos)

$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$tools = Join-Path $root 'tools'
if (Test-Path (Join-Path $tools 'kind.exe')) { $env:PATH = "$tools;$env:PATH" }
$ns = 'ridex-dev'
$base = 'http://ridex.localhost:8088'
$resolve = @('--resolve', 'ridex.localhost:8088:127.0.0.1')
$script:failed = 0

function Check($name, [bool]$ok, $detail = '') {
    if ($ok) { Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green }
    else { Write-Host ("  FAIL  {0}  {1}" -f $name, $detail) -ForegroundColor Red; $script:failed++ }
}
function Info($text) { Write-Host ("  INFO  {0}" -f $text) -ForegroundColor Cyan }

function Http($method, $path, $token = $null, $body = $null) {
    $args = @('-s', '-m', '20') + $resolve + @('-X', $method, '-w', '\n%{http_code}', "$base$path")
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
    $code = 0
    if ($i -ge 0) { [int]::TryParse($out.Substring($i + 1).Trim(), [ref]$code) | Out-Null }
    [pscustomobject]@{ Status = $code; Body = $(if ($i -gt 0) { $out.Substring(0, $i) } else { '' }) }
}

function WaitRollout($deployment, $seconds = 240) {
    kubectl rollout status "deployment/$deployment" -n $ns --timeout="${seconds}s" 2>&1 | Out-Null
    return ($LASTEXITCODE -eq 0)
}

$fare = '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":13.0827,"longitude":77.5877}}'

Write-Host "`n1. The cluster" -ForegroundColor Cyan
$context = (kubectl config current-context).Trim()
Check "kubectl points at the kind cluster ($context)" ($context -eq 'kind-ridex')
$pods = kubectl get pods -n $ns -o json | ConvertFrom-Json
$apps = 'core', 'notification', 'payment', 'pricing', 'analytics', 'gateway', 'web', 'postgres', 'redis', 'kafka', 'mailpit'
foreach ($app in $apps) {
    $mine = @($pods.items | Where-Object { $_.metadata.labels.'app.kubernetes.io/name' -eq $app })
    $ready = @($mine | Where-Object { ($_.status.conditions | Where-Object { $_.type -eq 'Ready' }).status -eq 'True' })
    Check "$app has a ready pod ($($ready.Count)/$($mine.Count))" ($mine.Count -gt 0 -and $ready.Count -eq $mine.Count)
}
$restarts = ($pods.items | ForEach-Object { $_.status.containerStatuses } | ForEach-Object { $_.restartCount } | Measure-Object -Sum).Sum
Info "container restarts so far: $restarts (a few during the first start are normal: a service retried before its database was ready)"
$ingress = kubectl get ingress ridex -n $ns -o jsonpath='{.spec.rules[0].host}'
Check 'the Ingress exists for ridex.localhost' ($ingress -eq 'ridex.localhost')
$unbound = @((kubectl get pvc -n $ns -o jsonpath='{.items[*].status.phase}') -split ' ' | Where-Object { $_ -ne 'Bound' })
Check 'the data lives on PersistentVolumes (every claim is Bound)' ($unbound.Count -eq 0)

Write-Host "`n2. Traffic: browser -> ingress -> gateway/web -> services" -ForegroundColor Cyan
$index = Http GET '/'
Check 'GET / serves the app (web pods)' ($index.Status -eq 200 -and $index.Body -match '<div id="root"')
Check 'GET /api/health reaches the core through the gateway' ((Http GET '/api/health').Status -eq 200)
Check 'a protected path without a token is refused at the edge (401)' ((Http GET '/api/rides').Status -eq 401)
Check 'internal paths are not reachable from outside (404 or 405, never 200)' ((Http POST '/internal/quotes').Status -in 404, 405)
# The cluster has its OWN, new database, so the sample rider does not exist yet: register it and confirm the e-mail address with the code the
# app "sent" (caught by mailpit, reached here through a temporary port-forward). Does nothing if the rider can already log in.
function Ensure-TestRider {
    $loginBody = Get-Content -Raw (Join-Path $root 'docs\api-samples\login-ok.json')
    if ((Http POST '/api/auth/login' $null $loginBody).Status -eq 200) { return }
    Info 'creating the sample rider in the cluster database ...'
    $null = Http POST '/api/auth/register' $null (Get-Content -Raw (Join-Path $root 'docs\api-samples\register-rider.json'))
    $forward = Start-Process kubectl -ArgumentList @('port-forward', '-n', $ns, 'svc/mailpit', '18025:8025') -WindowStyle Hidden -PassThru
    try {
        $code = $null
        for ($i = 0; $i -lt 20 -and -not $code; $i++) {
            Start-Sleep 2
            try {
                $messages = Invoke-RestMethod 'http://localhost:18025/api/v1/messages' -TimeoutSec 5
                # newest first: the newest mail carries the valid code (a new one replaces older ones)
                foreach ($msg in $messages.messages) {
                    $to = @($msg.To | ForEach-Object { $_.Address })
                    if ($to -contains 'asha.rao@example.com') {
                        $text = (Invoke-RestMethod "http://localhost:18025/api/v1/message/$($msg.ID)" -TimeoutSec 5).Text
                        $m = [regex]::Match($text, '\b\d{6}\b')
                        if ($m.Success) { $code = $m.Value; break }
                    }
                }
            } catch { }
        }
        if ($code) { $null = Http POST '/api/auth/verify-otp' $null ('{"email":"asha.rao@example.com","code":"' + $code + '"}') }
        else { Info 'no verification mail arrived in the mail catcher' }
    }
    finally { Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue }
}
Ensure-TestRider
$login = Http POST '/api/auth/login' $null (Get-Content -Raw (Join-Path $root 'docs\api-samples\login-ok.json'))
Check 'sample rider can log in' ($login.Status -eq 200)
$token = $null
if ($login.Status -eq 200) { $token = ($login.Body | ConvertFrom-Json).accessToken }
if ($token) {
    Check 'GET /api/users/me (core)' ((Http GET '/api/users/me' $token).Status -eq 200)
    Check 'GET /api/notifications (notification pods)' ((Http GET '/api/notifications' $token).Status -eq 200)
    Check 'POST /api/fares/estimate (pricing pods)' ((Http POST '/api/fares/estimate' $token $fare).Status -eq 200)
    Check 'rider cannot read admin payments (403 from payment pods)' ((Http GET '/api/admin/payments' $token).Status -eq 403)
    Check 'rider cannot read admin events (403 from analytics pods)' ((Http GET '/api/admin/events/lag' $token).Status -eq 403)
    if (-not $SkipRide) {
        $ride = '{"category":"CAB","pickup":{"address":"K8s smoke pickup","location":{"latitude":12.9716,"longitude":77.5946}},"dropoff":{"address":"K8s smoke dropoff","location":{"latitude":13.0827,"longitude":77.5877}}}'
        $current = Http GET '/api/rides/current' $token
        if ($current.Status -eq 200 -and $current.Body.Trim() -ne '') {
            Write-Host '  SKIP  the sample rider already has a ride in progress' -ForegroundColor Yellow
        }
        else {
            $created = Http POST '/api/rides' $token $ride
            Check 'POST /api/rides: core -> pricing over the Service name, with the secret service token' ($created.Status -eq 201) "(status $($created.Status))"
            if ($created.Status -eq 201) {
                Check 'the test ride is cancelled again' ((Http POST "/api/rides/$(($created.Body | ConvertFrom-Json).id)/cancel" $token).Status -eq 200)
            }
        }
    }
}
$ws = (& curl.exe -s -i -m 5 -N @resolve -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' `
        -H 'Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==' -H "Origin: $base" "$base/ws") -join "`n"
Check 'the WebSocket handshake is upgraded through the ingress (101)' ($ws -match 'HTTP/1.1 101')

Write-Host "`n3. What Kubernetes is responsible for" -ForegroundColor Cyan
$core = (kubectl get pod -n $ns -l app.kubernetes.io/name=core -o jsonpath='{.items[0].metadata.name}')
Check 'the core runs as a normal user' ((kubectl exec -n $ns $core -- id -u).Trim() -ne '0')
kubectl exec -n $ns $core -- sh -c 'touch /app/should-fail 2>/dev/null' 2>$null
Check 'the core cannot write outside its volumes (read-only root filesystem)' ($LASTEXITCODE -ne 0)
kubectl exec -n $ns $core -- sh -c 'ls /var/run/secrets/kubernetes.io 2>/dev/null' 2>$null
Check 'no Kubernetes API credentials are mounted into the pod' ($LASTEXITCODE -ne 0)
Check 'the secret files are mounted' ((kubectl exec -n $ns $core -- ls /run/secrets) -join ',' -match 'DB_PASSWORD')
$specYaml = (kubectl get pod $core -n $ns -o yaml) -join "`n"
Check 'the password is not in the pod specification' ($specYaml -notmatch '(?i)DB_PASSWORD:\s*\S{8,}')
$redisPort = (kubectl exec -n $ns $core -- printenv REDIS_PORT 2>$null)
Check 'service links are off (REDIS_PORT is not the tcp:// address Kubernetes would inject)' ("$redisPort" -notmatch 'tcp://')
$probe = kubectl get deployment core -n $ns -o jsonpath='{.spec.template.spec.containers[0].readinessProbe.httpGet.path}'
Check 'readiness probe path is the application readiness endpoint' ($probe -eq '/actuator/health/readiness')
Check 'the database is a StatefulSet pod with a stable name (postgres-0)' ((kubectl get pod postgres-0 -n $ns -o name 2>$null) -match 'postgres-0')

# Are NetworkPolicies really enforced by this cluster's network plugin? Information, not a failure: it depends on the plugin.
$np = kubectl run np-test -n $ns --rm -i --restart=Never --image=busybox:1.37 --labels='app.kubernetes.io/name=intruder' -- sh -c 'nc -z -w 3 postgres 5432 && echo CONNECTED || echo BLOCKED' 2>$null
if ("$np" -match 'BLOCKED') { Check 'NetworkPolicy: a stranger pod CANNOT reach the database (policies are enforced)' $true }
elseif ("$np" -match 'CONNECTED') { Info 'NetworkPolicy: a stranger pod COULD reach the database. This cluster network plugin does not enforce NetworkPolicies, so the policies are only documentation here. A cluster with Calico or Cilium would block it.' }
else { Info "NetworkPolicy test did not run ($np)" }

if ($Chaos -and $token) {
    Write-Host "`n4. Chaos: break things, watch Kubernetes repair them" -ForegroundColor Cyan

    # --- a. self-healing ---
    $victim = (kubectl get pod -n $ns -l app.kubernetes.io/name=pricing -o jsonpath='{.items[0].metadata.name}')
    Info "deleting pod $victim"
    kubectl delete pod $victim -n $ns --wait=false | Out-Null
    $deadline = (Get-Date).AddSeconds(180)
    do { Start-Sleep 4; $now = (kubectl get pod -n $ns -l app.kubernetes.io/name=pricing -o jsonpath='{.items[*].metadata.name}') } while (($now -eq $victim -or -not $now) -and (Get-Date) -lt $deadline)
    Check 'the ReplicaSet created a replacement pod with a new name' ($now -and $now -ne $victim) "($now)"
    Check 'the replacement becomes ready' (WaitRollout 'pricing' 180)
    # With ONE replica there is a gap between the old pod going away and the new one being added to the Service; allow it a few seconds
    $served = $false
    for ($i = 0; $i -lt 15 -and -not $served; $i++) { $served = ((Http POST '/api/fares/estimate' $token $fare).Status -eq 200); if (-not $served) { Start-Sleep 2 } }
    Check 'fares work again (a single replica has a short gap; two replicas would not)' $served

    # --- b. zero-downtime rolling update ---
    kubectl scale deployment/pricing -n $ns --replicas=2 | Out-Null
    Check 'pricing scaled to 2 replicas and both are ready' (WaitRollout 'pricing' 180)
    Info 'sending 1 request every 200 ms while every pricing pod is replaced one by one ...'
    $job = Start-Job -ScriptBlock {
        param($base, $token, $fare)
        $ok = 0; $bad = 0; $codes = @{}
        $end = (Get-Date).AddSeconds(150)
        $tmp = [IO.Path]::GetTempFileName(); [IO.File]::WriteAllText($tmp, $fare)
        while ((Get-Date) -lt $end) {
            $code = & curl.exe -s -m 5 -o NUL -w '%{http_code}' --resolve ridex.localhost:8088:127.0.0.1 -X POST -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary "@$tmp" "$base/api/fares/estimate"
            if ($code -eq '200') { $ok++ } else { $bad++; $codes[$code] = 1 + [int]$codes[$code] }
            Start-Sleep -Milliseconds 200
        }
        Remove-Item $tmp
        [pscustomobject]@{ Ok = $ok; Bad = $bad; Codes = ($codes.GetEnumerator() | ForEach-Object { "$($_.Key)x$($_.Value)" }) -join ',' }
    } -ArgumentList $base, $token, $fare
    Start-Sleep 5
    kubectl rollout restart deployment/pricing -n $ns | Out-Null
    $rolled = WaitRollout 'pricing' 240
    $result = Receive-Job -Job $job -Wait; Remove-Job $job
    Check 'the rolling update finished' $rolled
    Check "zero failed requests during the update ($($result.Ok) ok, $($result.Bad) failed $($result.Codes))" ($result.Bad -eq 0 -and $result.Ok -gt 50)
    kubectl scale deployment/pricing -n $ns --replicas=1 | Out-Null

    # --- c. a broken release ---
    Info 'releasing a version that does not exist ...'
    kubectl set image deployment/pricing pricing=ridex/pricing:doesnotexist -n $ns | Out-Null
    Start-Sleep 25
    # (read as JSON: Windows PowerShell mangles quotes inside a jsonpath argument)
    $podList = kubectl get pods -n $ns -l app.kubernetes.io/name=pricing -o json | ConvertFrom-Json
    $bad = ($podList.items | ForEach-Object { $_.status.containerStatuses[0].state.waiting.reason }) -join ' '
    Check 'the new pod cannot start (ErrImagePull / ImagePullBackOff)' ("$bad" -match 'ErrImagePull|ImagePullBackOff|ErrImageNeverPull')
    Check 'the OLD pod keeps serving: users notice nothing' ((Http POST '/api/fares/estimate' $token $fare).Status -eq 200)
    kubectl rollout undo deployment/pricing -n $ns | Out-Null
    Check 'rollout undo brings back the working version' (WaitRollout 'pricing' 180)

    # --- d. the database ---
    Info 'deleting the database pod (postgres-0) ...'
    kubectl delete pod postgres-0 -n $ns --wait=false | Out-Null
    Start-Sleep 5
    kubectl wait --for=condition=ready pod/postgres-0 -n $ns --timeout=180s 2>&1 | Out-Null
    Check 'postgres-0 came back with the same name' ($LASTEXITCODE -eq 0)
    Start-Sleep 15   # the services reconnect with their own retry logic
    $again = Http POST '/api/auth/login' $null (Get-Content -Raw (Join-Path $root 'docs\api-samples\login-ok.json'))
    Check 'the user still exists after the database pod was replaced (data survived on the volume)' ($again.Status -eq 200) "(status $($again.Status))"
}

Write-Host ''
if ($script:failed -eq 0) { Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "$script:failed CHECK(S) FAILED" -ForegroundColor Red; exit 1 }
