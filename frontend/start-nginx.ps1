$ErrorActionPreference = "Stop"

$frontendDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$nginxDir = Join-Path $frontendDir "nginx-1.30.0"
$nginxExe = Join-Path $nginxDir "nginx.exe"

if (!(Test-Path $nginxExe)) {
    throw "nginx.exe not found: $nginxExe"
}

Copy-Item -Path (Join-Path $frontendDir "nginx-windows.conf") -Destination (Join-Path $nginxDir "conf\nginx.conf") -Force

& $nginxExe -t -p "$nginxDir\" -c "conf\nginx.conf"
Start-Process -FilePath $nginxExe -ArgumentList "-p", "$nginxDir\", "-c", "conf\nginx.conf" -WorkingDirectory $nginxDir -WindowStyle Hidden

Write-Host "Nginx started: http://localhost:8080"
