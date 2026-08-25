# VMarket - script quan ly du an cho Windows
# Cach dung: scripts\vmarket.cmd <lenh>
#   infra          : bat ha tang nhe (PostgreSQL + MongoDB + Redis + RabbitMQ, ~1.5GB RAM)
#   infra-search   : bat them Elasticsearch (profile search, +~1GB - chi khi lam AI Search)
#   infra-down     : tat ha tang
#   build          : build toan bo backend (Maven multi-module, bo qua test)
#   test           : chay test toan bo backend
#   core           : chay API Gateway + Auth + User (cua so moi cho tung service)
#   service <ten>  : chay 1 service bat ky (vd: service order-service)
#   fe             : chay frontend (npm run dev)
#   stop           : tat toan bo process Java dang chay
param([Parameter(Position = 0)][string]$Cmd = "help", [Parameter(Position = 1)][string]$Arg1)

$root = Split-Path $PSScriptRoot -Parent
$compose = "docker compose -f `"$root\docker-compose.yml`""

function Start-Service($name) {
  if (-not (Test-Path "$root\services\$name")) { Write-Output "Khong ton tai service: $name"; return }
  Write-Output ">> Dang chay $name (cua so moi)..."
  Start-Process cmd -ArgumentList "/k", "title vmarket-$name && cd /d $root\services\$name && ..\mvnw.cmd spring-boot:run" -WindowStyle Minimized
}

switch ($Cmd) {
  "infra" {
    Write-Output ">> Bat ha tang nhe (postgres, mongo, redis, rabbitmq)..."
    docker compose -f "$root\docker-compose.yml" up -d
    docker compose -f "$root\docker-compose.yml" ps --format "table {{.Name}}\t{{.Status}}"
  }
  "infra-search" {
    Write-Output ">> Bat ha tang + Elasticsearch..."
    docker compose -f "$root\docker-compose.yml" --profile search up -d
  }
  "infra-down" {
    docker compose -f "$root\docker-compose.yml" down
  }
  "build" {
    Write-Output ">> Build toan bo backend..."
    & "$root\services\mvnw.cmd" -f "$root\services\pom.xml" clean package -DskipTests
  }
  "test" {
    Write-Output ">> Test toan bo backend..."
    & "$root\services\mvnw.cmd" -f "$root\services\pom.xml" test
  }
  "core" {
    Write-Output ">> Chay ha tang truoc neu chua bat (neu loi thi chay: vmarket infra)..."
    Start-Service "api-gateway"
    Start-Service "auth-service"
    Start-Service "user-service"
  }
  "service" {
    Start-Service $Arg1
  }
  "fe" {
    Write-Output ">> Chay frontend..."
    Start-Process cmd -ArgumentList "/k", "title vmarket-frontend && cd /d $root\frontend && npm run dev" -WindowStyle Minimized
  }
  "stop" {
    Write-Output ">> Tat cac process Java (service backend)..."
    taskkill /IM java.exe /F 2>&1 | Out-Null
    Write-Output "Done. (Ha tang Docker giu nguyen - tat bang: vmarket infra-down)"
  }
  default {
    Write-Output "VMarket - cac lenh:"
    Write-Output "  vmarket infra            bat ha tang nhe (PG+Mongo+Redis+RabbitMQ)"
    Write-Output "  vmarket infra-search     bat them Elasticsearch"
    Write-Output "  vmarket infra-down       tat ha tang"
    Write-Output "  vmarket build            build toan bo backend"
    Write-Output "  vmarket test             test toan bo backend"
    Write-Output "  vmarket core             chay gateway + auth + user"
    Write-Output "  vmarket service <ten>    chay 1 service bat ky"
    Write-Output "  vmarket fe               chay frontend"
    Write-Output "  vmarket stop             tat cac process Java"
  }
}
