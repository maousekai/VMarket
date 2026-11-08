# Start the Java backend locally for mobile development (infra must already be up: docker compose up -d postgres mongo mongo-init-replica redis rabbitmq).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\start-backend-local.ps1 [-Only order-service,cart-service] [-Wait]
#   -Only  restart just these services (comma separated); an old process holding the port is stopped first
#   -Wait  block until every started service listens on its port, then report UP/TIMEOUT
param([string[]]$Only, [switch]$Wait)
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

while ((docker inspect -f '{{.State.Health.Status}}' VMarket-postgres) -ne 'healthy') { Start-Sleep 2 }

foreach ($db in 'vmarket_auth','vmarket_user','vmarket_shop','vmarket_order','vmarket_payment','vmarket_delivery','vmarket_review','vmarket_recommendation') {
  $exists = docker exec VMarket-postgres psql -U vmarket -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$db'"
  if (-not $exists) { docker exec VMarket-postgres psql -U vmarket -d postgres -c "CREATE DATABASE $db" | Out-Null; Write-Output "created $db" }
}

$env:SPRING_PROFILES_ACTIVE = "dev"
$env:AUTH_JWT_SECRET        = "dev-only-insecure-secret-change-me-please-0123456789"
$env:INTERNAL_API_KEY       = "dev-only-internal-api-key-change-me-0123456789"
$env:DB_HOST = "localhost";   $env:DB_PORT = "5433"
$env:DB_USERNAME = "vmarket"; $env:DB_PASSWORD = "vmarket"
$env:MONGO_HOST = "localhost"; $env:MONGO_PORT = "27018"
$env:REDIS_HOST = "localhost"; $env:REDIS_PORT = "6379"
$env:RABBITMQ_HOST = "localhost"; $env:RABBITMQ_PORT = "5672"
$env:RABBITMQ_USERNAME = "guest"; $env:RABBITMQ_PASSWORD = "guest"
$env:AUTH_EMAIL_PROVIDER = "log"
$env:APP_RATE_LIMIT_CAPACITY = "5000"   # dev only: seeding and several clients share one IP
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
# auth-service default port 8081 is often taken (Metro, other dev stacks) -> run it on 18081 and tell the others.
$authPort = if ($env:VMARKET_AUTH_PORT) { $env:VMARKET_AUTH_PORT } else { "18081" }
$env:AUTH_SERVICE_URL = "http://localhost:$authPort"
$env:DEV_ADMIN_PASSWORD = "Admin@12345"
$env:CORS_ALLOWED_ORIGINS = "http://localhost:5173,http://localhost:5174,http://localhost:3000,http://localhost:8081,http://localhost:19006"
$env:APP_CORS_ALLOWED_ORIGINS = "http://localhost:5173,http://localhost:5174,http://localhost:3000,http://localhost:8081,http://localhost:19006"   # api-gateway reads app.cors.allowed-origins

if (-not (Test-Path "$root\.logs")) { New-Item -ItemType Directory "$root\.logs" | Out-Null }
function Start-Svc($name) {
  $pre = if ($name -eq 'auth-service') { "set SERVER_PORT=$authPort&& " } else { "" }
  Start-Process cmd -WindowStyle Minimized -ArgumentList "/c", "${pre}cd /d $root\services\$name && ..\mvnw.cmd spring-boot:run > $root\.logs\$name.log 2>&1"
}
$svcs = 'auth-service','user-service','shop-service','product-service','cart-service','order-service','payment-service','delivery-service','notification-service','review-service','api-gateway'
$ports = @{ 'auth-service'=$authPort; 'user-service'=8082; 'shop-service'=8083; 'product-service'=8084; 'cart-service'=8085; 'order-service'=8086; 'payment-service'=8087; 'delivery-service'=8088; 'review-service'=8089; 'notification-service'=8090; 'api-gateway'=8080 }
if ($Only) {
  # accepts "-Only a,b", "-Only a b" and the single comma string that `powershell -File` produces
  $Only = $Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ }
  $unknown = $Only | Where-Object { $svcs -notcontains $_ }
  if ($unknown) { Write-Error "Unknown service(s): $($unknown -join ', '). Valid: $($svcs -join ', ')"; exit 1 }
  $svcs = $svcs | Where-Object { $Only -contains $_ }
}
function Stop-Svc($name, $port) {
  # 1) the cmd wrapper (+ its Maven/JVM tree) started by an earlier run of this script. Killing only the JVM leaves
  #    the wrapper alive holding .logs\<name>.log open, and the new start then fails to open its log and dies silently.
  Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*\services\$name &&*" } |
    ForEach-Object { & taskkill.exe /PID $_.ProcessId /T /F 2>&1 | Out-Null }
  # 2) anything else still holding the port (a JVM started by hand)
  Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
  # 3) wait until the log file can be opened for writing again (max 15 s)
  $log = "$root\.logs\$name.log"
  for ($i = 0; $i -lt 30 -and (Test-Path $log); $i++) {
    try { $fs = [IO.File]::Open($log, 'Open', 'ReadWrite', 'None'); $fs.Close(); break } catch { Start-Sleep -Milliseconds 500 }
  }
}
foreach ($s in $svcs) {
  Stop-Svc $s $ports[$s]
  Start-Svc $s; Start-Sleep 2
}
Write-Output "started: $($svcs -join ', ')  (logs in .logs\)"

if ($Wait) {
  # -Wait: block until every started service listens on its port (max 3 minutes each) and report the result
  $failed = @()
  foreach ($s in $svcs) {
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline -and -not (Get-NetTCPConnection -State Listen -LocalPort $ports[$s] -ErrorAction SilentlyContinue)) { Start-Sleep 3 }
    if (Get-NetTCPConnection -State Listen -LocalPort $ports[$s] -ErrorAction SilentlyContinue) { Write-Output "  UP       $s :$($ports[$s])" }
    else { Write-Output "  TIMEOUT  $s :$($ports[$s])  (see .logs\$s.log)"; $failed += $s }
  }
  if ($failed) { exit 2 }
}
