# Runs several copies of the stateless parts in the local cluster (the "scale" overlay): 2 gateways, 3 pricing pods, 2 core pods.
#
#   .\scripts\k8s-scale-out.ps1             apply the scale overlay
#   .\scripts\k8s-scale-in.ps1              go back to one copy of everything (the dev overlay)
#
# Why the core needs a Secret first: a single core keeps its token signing key on a disk. Two cores must share ONE key, so the key of the
# running core is copied into the Secret "ridex-jwt-key" (users stay logged in) and mounted into every copy. The key is never printed or
# written anywhere except that Secret (a temporary file is deleted at once).
#
# Needs: the cluster from .\scripts\k8s-up.ps1, with the images rebuilt after Phase 11 (they must contain the X-Instance headers).

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\k8s-common.ps1"
$ErrorActionPreference = 'Stop'

$context = (kubectl config current-context).Trim()
if ($context -ne 'kind-ridex') { throw 'kubectl does not point at the kind cluster. Start it with .\scripts\k8s-up.ps1' }

# --- 1. the shared signing key ---
cmd /c "kubectl get secret ridex-jwt-key -n $script:ns >nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    $core = Get-Pods 'core' | Where-Object { ($_.status.conditions | Where-Object { $_.type -eq 'Ready' }).status -eq 'True' } | Select-Object -First 1
    if (-not $core) { throw 'No ready core pod to take the signing key from. Run .\scripts\k8s-up.ps1 first.' }
    Info "copying the token signing key of $($core.metadata.name) into the Secret ridex-jwt-key"
    $tmp = [IO.Path]::GetTempFileName()
    try {
        $pem = (kubectl exec -n $script:ns $core.metadata.name -- cat /app/data/keys/jwt-signing-key.pem) -join "`n"
        if ($pem -notmatch 'BEGIN PRIVATE KEY') { throw 'the core pod did not return a signing key' }
        [IO.File]::WriteAllText($tmp, $pem + "`n", (New-Object System.Text.UTF8Encoding($false)))
        kubectl create secret generic ridex-jwt-key -n $script:ns --from-file="jwt-signing-key.pem=$tmp" | Out-Null
    }
    finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
}
else { Info 'the Secret ridex-jwt-key already exists' }

# --- 2. apply ---
Write-Host 'Applying the scale overlay ...' -ForegroundColor Cyan
for ($attempt = 1; $attempt -le 5; $attempt++) {
    kubectl apply -k (Join-Path $script:root 'infrastructure\k8s\overlays\scale')
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "apply failed (attempt $attempt of 5), trying again in 10 seconds ..." -ForegroundColor Yellow
    Start-Sleep 10
}

Write-Host 'Waiting for the new copies ...' -ForegroundColor Cyan
foreach ($dep in 'core', 'gateway', 'pricing') {
    if (Wait-Rollout $dep 420) { Write-Host "  $dep is rolled out" -ForegroundColor Green } else { Write-Host "  $dep did not finish rolling out in time" -ForegroundColor Yellow }
}
Write-Host ''
kubectl get pods -n $script:ns -l 'app.kubernetes.io/name in (core,gateway,pricing)' -o wide
Write-Host "`nTry:  .\scripts\k8s-lb-demo.ps1   .\scripts\k8s-autoscale-demo.ps1   .\scripts\k8s-failure-experiments.ps1   .\scripts\k8s-websocket-demo.ps1" -ForegroundColor Green
