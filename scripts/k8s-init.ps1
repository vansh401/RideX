# Creates the secret files for the DEV Kubernetes overlay. Run once (safe to run again).
#
#   .\scripts\k8s-init.ps1            creates what is missing, keeps what exists
#   .\scripts\k8s-init.ps1 -Force     replaces both with new random values (an existing cluster keeps its OLD database password: delete the
#                                     cluster volumes first, or the services cannot log in)
#
# Created in infrastructure\k8s\overlays\dev\secrets (git-ignored: never commit this folder):
#   DB_PASSWORD     random; the PostgreSQL password of the NEW cluster database
#   SERVICE_TOKEN   random; the secret the core and the pricing service share for internal calls
#
# kustomize turns these files into the Kubernetes Secret "ridex-secrets". Staging and production secrets are NOT made here.

param([switch]$Force)

$ErrorActionPreference = 'Stop'
$dir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')) 'infrastructure\k8s\overlays\dev\secrets'
New-Item -ItemType Directory -Force $dir | Out-Null

function New-RandomSecret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    # letters and digits only: safe in a URL, a shell and a connection string
    ([Convert]::ToBase64String($buffer) -replace '[^A-Za-z0-9]', '').Substring(0, [Math]::Min(32, $bytes * 2))
}

foreach ($name in 'DB_PASSWORD', 'SERVICE_TOKEN') {
    $file = Join-Path $dir $name
    if ((Test-Path $file) -and -not $Force) {
        Write-Host "$name kept (use -Force to replace it)"
        continue
    }
    # no trailing newline: Spring would read it as part of the value
    [IO.File]::WriteAllText($file, (New-RandomSecret 32), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "$name written (new random value)" -ForegroundColor Green
}
Write-Host "`nNext:  .\scripts\k8s-up.ps1" -ForegroundColor Cyan
