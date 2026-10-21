# Shows which RideX services are answering, and what each one says about its own health.
#
#   .\scripts\status.ps1

$services = [ordered]@{ gateway = 8000; core = 8080; notification = 8081; payment = 8082; pricing = 8083; analytics = 8084 }

foreach ($name in $services.Keys) {
    $port = $services[$name]
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 3
        Write-Host ("{0,-13} :{1}  {2}" -f $name, $port, $health.status) -ForegroundColor Green
    }
    catch {
        # The core exposes its own health endpoint
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$port/api/health" -TimeoutSec 3
            Write-Host ("{0,-13} :{1}  up" -f $name, $port) -ForegroundColor Green
        }
        catch {
            Write-Host ("{0,-13} :{1}  not answering" -f $name, $port) -ForegroundColor Red
        }
    }
}
