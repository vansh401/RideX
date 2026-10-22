# Creates the secret files that docker compose mounts into the Java containers. Run it once (it is safe to run again).
#
#   .\scripts\docker-init.ps1            creates what is missing, keeps what exists
#   .\scripts\docker-init.ps1 -Force     replaces the service token with a new random one
#
# Created in infrastructure\docker-compose\secrets (git-ignored: this folder must never be committed):
#   DB_PASSWORD     the PostgreSQL password, copied from POSTGRES_PASSWORD in infrastructure\docker-compose\.env
#   SERVICE_TOKEN   random; the secret the core and the pricing service share for internal calls
#
# The files have NO trailing newline on purpose: Spring would otherwise read the newline as part of the password.

param([switch]$Force)

$ErrorActionPreference = 'Stop'
$compose = Resolve-Path (Join-Path $PSScriptRoot '..\infrastructure\docker-compose')
$secrets = Join-Path $compose 'secrets'
New-Item -ItemType Directory -Force $secrets | Out-Null

# --- the .env file (database name, user, password for the PostgreSQL container) ---
$envFile = Join-Path $compose '.env'
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $compose '.env.example') $envFile
    Write-Host "Created $envFile from .env.example. Open it and choose your own password if you like (before the first start of PostgreSQL)." -ForegroundColor Yellow
}
$values = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') { $values[$Matches[1]] = $Matches[2] }
}
if (-not $values['POSTGRES_PASSWORD']) { throw "POSTGRES_PASSWORD is not set in $envFile" }

function Write-Secret($name, $value) {
    # [IO.File]::WriteAllText writes exactly these characters: no BOM, no newline
    [IO.File]::WriteAllText((Join-Path $secrets $name), $value, (New-Object System.Text.UTF8Encoding($false)))
}

# --- DB_PASSWORD: always follows .env, so the two can never disagree ---
Write-Secret 'DB_PASSWORD' $values['POSTGRES_PASSWORD']
Write-Host 'DB_PASSWORD   written (copied from .env)' -ForegroundColor Green

# --- SERVICE_TOKEN: random, kept unless -Force ---
$tokenFile = Join-Path $secrets 'SERVICE_TOKEN'
if ((-not (Test-Path $tokenFile)) -or $Force) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    Write-Secret 'SERVICE_TOKEN' ([Convert]::ToBase64String($bytes))
    Write-Host 'SERVICE_TOKEN written (new random value)' -ForegroundColor Green
}
else {
    Write-Host 'SERVICE_TOKEN kept (use -Force to replace it)'
}

# --- Phase 15: optional secrets. Empty = not configured (the test checkout and Mailpit are used). Never overwritten if they exist. ---
foreach ($name in 'MAIL_PASSWORD', 'RAZORPAY_KEY_SECRET', 'RAZORPAY_WEBHOOK_SECRET') {
    $file = Join-Path $secrets $name
    if (-not (Test-Path $file)) {
        Write-Secret $name ''
        Write-Host "$name created EMPTY (fill it in only if you use real email / Razorpay: docs\payments-and-email-setup.md)"
    }
}

Write-Host "`nNext:  cd infrastructure\docker-compose ; docker compose up -d --build" -ForegroundColor Cyan
