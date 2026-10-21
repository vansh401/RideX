# Deletes the local kind cluster and EVERYTHING in it (pods, volumes, the database of the cluster). Your Docker Compose data is not touched.
#
#   .\scripts\k8s-down.ps1

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$tools = Join-Path $root 'tools'
if (Test-Path (Join-Path $tools 'kind.exe')) { $env:PATH = "$tools;$env:PATH" }

$existing = (cmd /c 'kind get clusters 2>&1') -join ' '
if ($existing -match '\bridex\b') {
    kind delete cluster --name ridex
    Write-Host 'The cluster is gone.' -ForegroundColor Green
}
else {
    Write-Host 'There is no cluster called ridex.'
}
