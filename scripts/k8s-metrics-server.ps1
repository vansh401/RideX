# Installs metrics-server into the local kind cluster. The autoscaler (HPA) needs it: it is the thing that reports each pod's CPU use.
#
#   .\scripts\k8s-metrics-server.ps1            install
#   .\scripts\k8s-metrics-server.ps1 -Remove    uninstall
#
# Downloads (with the user's permission): components.yaml (about 4 KB) from the metrics-server releases on GitHub, saved in tools\ (git-ignored),
# and the image registry.k8s.io/metrics-server/metrics-server (about 65 MB), which the cluster pulls itself.
# --kubelet-insecure-tls: kind's kubelets have certificates metrics-server cannot verify. Fine for a laptop, never for a real cluster.

param([switch]$Remove)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\k8s-common.ps1"
$ErrorActionPreference = 'Stop'

if ((kubectl config current-context).Trim() -ne 'kind-ridex') { throw 'kubectl does not point at the kind cluster. Start it with .\scripts\k8s-up.ps1' }
$file = Join-Path $script:root 'tools\metrics-server-components.yaml'

if ($Remove) {
    if (Test-Path $file) { kubectl delete -f $file --ignore-not-found } else { Info 'nothing downloaded, nothing to remove' }
    return
}

if (-not (Test-Path $file)) {
    New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
    Info 'downloading components.yaml from github.com/kubernetes-sigs/metrics-server (latest release) ...'
    Invoke-WebRequest 'https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml' -OutFile $file -UseBasicParsing
}
kubectl apply -f $file
# add the kind-only flag once
$args = (kubectl get deployment metrics-server -n kube-system -o jsonpath='{.spec.template.spec.containers[0].args}')
if ($args -notmatch 'kubelet-insecure-tls') {
    $patch = Join-Path ([IO.Path]::GetTempPath()) 'ms-patch.json'
    [IO.File]::WriteAllText($patch, '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]', (New-Object System.Text.UTF8Encoding($false)))
    kubectl patch deployment metrics-server -n kube-system --type=json --patch-file $patch
    Remove-Item $patch
}
kubectl rollout status deployment/metrics-server -n kube-system --timeout=240s
Info 'waiting for the first measurements (about a minute) ...'
for ($i = 0; $i -lt 24; $i++) {
    cmd /c "kubectl top nodes >nul 2>&1"
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep 5
}
kubectl top pods -n $script:ns
