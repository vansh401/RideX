# Helpers shared by the Phase 11 experiment scripts. Not run on its own: the other scripts load it with  . "$PSScriptRoot\k8s-common.ps1"

$ErrorActionPreference = 'Continue'
$script:root = Resolve-Path (Join-Path $PSScriptRoot '..')
$tools = Join-Path $script:root 'tools'
if (Test-Path (Join-Path $tools 'kind.exe')) { $env:PATH = "$tools;$env:PATH" }
$script:ns = 'ridex-dev'
$script:ingress = 'http://127.0.0.1:8088'      # the port the kind cluster publishes; the Host header selects the Ingress rule
$script:hostName = 'ridex.localhost'
$script:failed = 0

function Check($name, [bool]$ok, $detail = '') {
    if ($ok) { Write-Host ("  PASS  {0}" -f $name) -ForegroundColor Green }
    else { Write-Host ("  FAIL  {0}  {1}" -f $name, $detail) -ForegroundColor Red; $script:failed++ }
}
function Info($text) { Write-Host ("  INFO  {0}" -f $text) -ForegroundColor Cyan }
function Title($text) { Write-Host "`n$text" -ForegroundColor Cyan }

function Http($method, $path, $token = $null, $body = $null) {
    $args = @('-s', '-m', '20', '-H', "Host: $script:hostName", '-X', $method, '-w', '\n%{http_code}', "$script:ingress$path")
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

function Assert-Cluster {
    $context = (kubectl config current-context 2>$null)
    if ("$context".Trim() -ne 'kind-ridex') { throw 'kubectl does not point at the kind cluster. Start it with .\scripts\k8s-up.ps1' }
    $ready = (Http GET '/api/health').Status
    if ($ready -ne 200) { throw "RideX does not answer at $script:ingress (status $ready). Is the cluster up and the stack deployed? (.\scripts\k8s-up.ps1)" }
}

# Logs in the sample rider (creating and e-mail-verifying it through the mail catcher if the cluster database is new) and returns a token.
function Get-TestToken {
    $loginBody = Get-Content -Raw (Join-Path $script:root 'docs\api-samples\login-ok.json')
    $login = Http POST '/api/auth/login' $null $loginBody
    if ($login.Status -ne 200) {
        Info 'creating the sample rider in the cluster database ...'
        $null = Http POST '/api/auth/register' $null (Get-Content -Raw (Join-Path $script:root 'docs\api-samples\register-rider.json'))
        $forward = Start-Process kubectl -ArgumentList @('port-forward', '-n', $script:ns, 'svc/mailpit', '18025:8025') -WindowStyle Hidden -PassThru
        try {
            $code = $null
            for ($i = 0; $i -lt 20 -and -not $code; $i++) {
                Start-Sleep 2
                try {
                    $messages = Invoke-RestMethod 'http://localhost:18025/api/v1/messages' -TimeoutSec 5
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
        }
        finally { Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue }
        $login = Http POST '/api/auth/login' $null $loginBody
    }
    if ($login.Status -ne 200) { throw 'could not log in the sample rider' }
    return ($login.Body | ConvertFrom-Json).accessToken
}

$script:fareBody = '{"pickup":{"latitude":12.9716,"longitude":77.5946},"dropoff":{"latitude":13.0827,"longitude":77.5877}}'

# Starts the load generator (scripts\loadtest\LoadTest.java) as a background process that writes its per-second output to a file.
function Start-LoadTest([string]$Path, [string]$Token, [string]$OutFile, [string]$Method = 'GET', [string]$Body = $null,
                        [int]$Concurrency = 20, [int]$Duration = 30) {
    $arguments = @('-Djdk.httpclient.allowRestrictedHeaders=host', (Join-Path $script:root 'scripts\loadtest\LoadTest.java'),
        "url=$script:ingress$Path", "host=$script:hostName", "token=$Token", "method=$Method", "concurrency=$Concurrency", "duration=$Duration")
    if ($Body) {
        $bodyFile = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($bodyFile, $Body, (New-Object System.Text.UTF8Encoding($false)))
        $arguments += "body=@$bodyFile"
    }
    Start-Process java -ArgumentList $arguments -RedirectStandardOutput $OutFile -RedirectStandardError "$OutFile.err" -WindowStyle Hidden -PassThru
}

function Wait-Rollout($deployment, $seconds = 300) {
    kubectl rollout status "deployment/$deployment" -n $script:ns --timeout="${seconds}s" 2>&1 | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Get-Pods($app) {
    $list = kubectl get pods -n $script:ns -l "app.kubernetes.io/name=$app" -o json | ConvertFrom-Json
    @($list.items | Where-Object { -not $_.metadata.deletionTimestamp })
}

function Show-Summary($file) {
    # the summary starts at the blank line before "requests:"
    $text = Get-Content $file -Raw
    $i = $text.IndexOf('requests:')
    if ($i -ge 0) { Write-Host $text.Substring($i) }
    else { Write-Host '(no summary: the load generator did not finish. See ' $file '.err)' }
}
