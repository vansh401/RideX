# Stops the services started by start-services.ps1.
#
#   .\scripts\stop-services.ps1

$pidFile = Join-Path $PSScriptRoot '..\logs\pids.txt'
if (-not (Test-Path $pidFile)) {
    Write-Host 'Nothing to stop (no logs\pids.txt).'
    return
}
foreach ($entry in Get-Content $pidFile) {
    $name, $id = $entry -split '='
    $process = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'java') {
        # /T kills the whole tree: on Windows the java command can be a small launcher that starts the real JVM as a child
        taskkill /PID $id /T /F | Out-Null
        Write-Host "stopped $name (pid $id)"
    }
}
Remove-Item $pidFile
