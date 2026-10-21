# Creates a local Kubernetes cluster with kind and deploys RideX (the DEV overlay) into it.
#
#   .\scripts\k8s-init.ps1          once: creates the secret files
#   .\scripts\k8s-up.ps1            cluster + images + ingress controller + RideX
#   .\scripts\k8s-up.ps1 -SkipLoad  do not copy the images into the cluster again (they are already there)
#
# Needs: Docker Desktop, kubectl (comes with Docker Desktop), kind (found in .\tools\kind.exe or on the PATH), the images ridex/*:dev
# (cd infrastructure\docker-compose ; docker compose build) and ENOUGH MEMORY: the cluster plus RideX needs about 3 GB inside Docker's
# VM. Stop the Docker Compose stack first (docker compose down) and close other heavy programs.

param([switch]$SkipLoad)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$k8s = Join-Path $root 'infrastructure\k8s'
$cluster = 'ridex'
$namespace = 'ridex-dev'
# Pinned version of the ingress controller manifest made for kind
$ingressManifest = 'https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.14.0/deploy/static/provider/kind/deploy.yaml'
$images = 'core', 'notification', 'payment', 'pricing', 'analytics', 'gateway', 'web'

# kind downloaded into the repository's git-ignored tools folder is used first
$tools = Join-Path $root 'tools'
if (Test-Path (Join-Path $tools 'kind.exe')) { $env:PATH = "$tools;$env:PATH" }

function Need($command, $hint) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "$command is not installed. $hint" }
}
Need docker 'Install Docker Desktop.'
Need kubectl 'It comes with Docker Desktop.'
Need kind 'Download kind-windows-amd64 from https://github.com/kubernetes-sigs/kind/releases and save it as tools\kind.exe'

if (-not (Test-Path (Join-Path $k8s 'overlays\dev\secrets\DB_PASSWORD'))) { throw 'Run .\scripts\k8s-init.ps1 first.' }

# --- memory warning: a cluster that cannot get memory fails in confusing ways ---
$freeMb = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
if ($freeMb -lt 1000) {
    Write-Host "Only $freeMb MB of RAM is free on this machine. Close other programs, and make sure 'docker compose' is stopped." -ForegroundColor Yellow
}
$running = @(docker ps --filter 'name=ridex-' --format '{{.Names}}')
if ($running.Count -gt 0) {
    Write-Host "The Docker Compose stack is running ($($running.Count) containers). Stop it first (cd infrastructure\docker-compose ; docker compose down), or the cluster will not fit in memory." -ForegroundColor Yellow
}

# --- 1. the cluster ---
$existing = (cmd /c 'kind get clusters 2>&1') -join ' '   # kind prints a message on stderr when there is none
if ($existing -match '\bridex\b') {
    Write-Host "Cluster '$cluster' already exists."
}
else {
    Write-Host 'Creating the cluster (downloads the kindest/node image the first time, about 1 GB) ...' -ForegroundColor Cyan
    kind create cluster --config (Join-Path $k8s 'kind\kind-config.yaml')
    if ($LASTEXITCODE -ne 0) { throw 'kind could not create the cluster' }
}
kubectl config use-context "kind-$cluster" | Out-Null

# --- 2. the images: a kind node is its own little Docker, so it cannot see the images on your machine until they are copied in ---
if (-not $SkipLoad) {
    foreach ($name in $images) {
        cmd /c "docker image inspect ridex/${name}:dev >nul 2>&1"   # cmd: PowerShell 5.1 treats a native command's stderr as an error
        if ($LASTEXITCODE -ne 0) { throw "Image ridex/${name}:dev does not exist. Build it: cd infrastructure\docker-compose ; docker compose build" }
        Write-Host "loading ridex/${name}:dev into the cluster ..."
        kind load docker-image "ridex/${name}:dev" --name $cluster
    }
    # The public images (postgres, redis, kafka, mailpit) are NOT loaded: the cluster pulls them itself from the registries on first use
    # (the first start is slower). Loading them with "kind load" fails on Docker's multi-platform images ("content digest not found").
}

# --- 3. the ingress controller (once per cluster) ---
cmd /c "kubectl get namespace ingress-nginx >nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Installing the ingress controller ...' -ForegroundColor Cyan
    kubectl apply -f $ingressManifest
}
kubectl wait --namespace ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout=240s

# --- 4. RideX ---
Write-Host 'Applying the dev overlay ...' -ForegroundColor Cyan
# The ingress controller checks every new Ingress through an admission webhook, which needs a few more seconds to be reachable after the
# controller pod is ready. A rejected apply is simply tried again.
for ($attempt = 1; $attempt -le 5; $attempt++) {
    kubectl apply -k (Join-Path $k8s 'overlays\dev')
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "apply failed (attempt $attempt of 5), trying again in 10 seconds ..." -ForegroundColor Yellow
    Start-Sleep 10
}

Write-Host 'Waiting for everything to become ready (the first start takes a few minutes) ...' -ForegroundColor Cyan
foreach ($sts in 'postgres', 'redis', 'kafka') { kubectl rollout status "statefulset/$sts" -n $namespace --timeout=300s }
foreach ($dep in 'mailpit', 'core', 'notification', 'payment', 'pricing', 'analytics', 'gateway', 'web') {
    kubectl rollout status "deployment/$dep" -n $namespace --timeout=420s
}

Write-Host "`nRideX is running in the cluster:  http://ridex.localhost:8088" -ForegroundColor Green
Write-Host 'Look at it:    kubectl get pods -n ridex-dev'
Write-Host 'Check it:      .\scripts\k8s-smoke.ps1'
Write-Host 'Remove it all: .\scripts\k8s-down.ps1'
