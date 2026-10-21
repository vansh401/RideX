# Goes back from the "scale" overlay to the plain dev overlay: one copy of everything, no autoscaler.
#
#   .\scripts\k8s-scale-in.ps1
#
# The core goes back to its single-copy design (token key and uploads on its PersistentVolume, which was never deleted). The Secret
# ridex-jwt-key stays; delete it with:  kubectl delete secret ridex-jwt-key -n ridex-dev

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\k8s-common.ps1"
$ErrorActionPreference = 'Stop'

cmd /c "kubectl delete hpa pricing -n $script:ns >nul 2>&1"
Write-Host 'Applying the dev overlay ...' -ForegroundColor Cyan
for ($attempt = 1; $attempt -le 5; $attempt++) {
    kubectl apply -k (Join-Path $script:root 'infrastructure\k8s\overlays\dev')
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep 10
}
# the apply sets replicas back to 1 for everything that the scale overlay raised
foreach ($dep in 'core', 'gateway', 'pricing') { Wait-Rollout $dep 300 | Out-Null }
kubectl get pods -n $script:ns
