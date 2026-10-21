# Builds the shared platform library and every service once, without running the tests.
# Run this after pulling changes, and before start-services.ps1.
#
#   .\scripts\build-all.ps1
#
# Why install and not just package: the services depend on the platform library (ridex-platform). "install" puts it in your
# local Maven repository, where the other modules find it.

$ErrorActionPreference = 'Stop'
$backend = Join-Path $PSScriptRoot '..\backend'
Push-Location $backend
try {
    & .\mvnw.cmd -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)" }
    Write-Host "`nBuilt. Next: .\scripts\start-services.ps1" -ForegroundColor Green
}
finally {
    Pop-Location
}
