$ErrorActionPreference = "Stop"

$frontendDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$nginxDir = Join-Path $frontendDir "nginx-1.30.0"
$nginxExe = Join-Path $nginxDir "nginx.exe"

if (!(Test-Path $nginxExe)) {
    throw "nginx.exe not found: $nginxExe"
}

& $nginxExe -p "$nginxDir\" -c "conf\nginx.conf" -s quit
Write-Host "Nginx stopped."
