# Checks the monitoring configuration without starting anything but one short-lived container:
#   - prometheus.yml and alerts.yml are valid (promtool check config)
#   - the alert rules behave as intended on fake data (promtool test rules, see alerts.test.yml)
#   - alertmanager.yml is valid (amtool check-config)
#
#   .\scripts\obs-test-rules.ps1

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
# Join-Path (not "\" in a string) so the script also runs on Linux: the CI pipeline runs it there
$prom = Join-Path (Join-Path (Join-Path $root 'infrastructure') 'observability') 'prometheus'
$am = Join-Path (Join-Path (Join-Path $root 'infrastructure') 'observability') 'alertmanager'
$promConfig = Join-Path $prom 'prometheus.yml'
$promRules = Join-Path $prom 'alerts.yml'
$amConfig = Join-Path $am 'alertmanager.yml'

# Prometheus checks that the rule files named in prometheus.yml exist at /etc/prometheus, so mount them there.
Write-Host '1. prometheus.yml + alerts.yml' -ForegroundColor Cyan
docker run --rm --entrypoint promtool -v "${promConfig}:/etc/prometheus/prometheus.yml:ro" -v "${promRules}:/etc/prometheus/alerts.yml:ro" `
    prom/prometheus:v3.15.1 check config /etc/prometheus/prometheus.yml
if ($LASTEXITCODE -ne 0) { throw 'promtool check config failed' }

Write-Host '2. alert rule unit tests' -ForegroundColor Cyan
docker run --rm --entrypoint promtool -v "${prom}:/w:ro" prom/prometheus:v3.15.1 test rules /w/alerts.test.yml
if ($LASTEXITCODE -ne 0) { throw 'promtool test rules failed' }

Write-Host '3. alertmanager.yml' -ForegroundColor Cyan
docker run --rm --entrypoint amtool -v "${amConfig}:/etc/alertmanager/alertmanager.yml:ro" prom/alertmanager:v0.34.1 check-config /etc/alertmanager/alertmanager.yml
if ($LASTEXITCODE -ne 0) { throw 'amtool check-config failed' }

Write-Host "`nAll monitoring configuration checks passed." -ForegroundColor Green
