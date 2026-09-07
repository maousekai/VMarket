# VMarket - khoi tao file ha tang (Dockerfile + CI + .env) cho MOT service moi
# tu cac template dung chung trong docs/templates/.
#
# Cach dung:
#   scripts\new-service.cmd -Name shop-service -Port 8083
#   scripts\new-service.cmd -Name product-service -Port 8084 -Store mongo
#   scripts\new-service.cmd -Name cart-service -Port 8085 -Store redis
#   scripts\new-service.cmd -Name api-gateway -Port 8080 -Store none -Force
#
# Script nay KHONG sinh code Java. Tao skeleton Spring Boot bang Spring
# Initializr truoc (hoac copy tu service co san), roi chay script nay de gan
# Dockerfile / workflow CI / file .env theo dung chuan chung cua repo.
param(
  [Parameter(Mandatory = $true)][string]$Name,
  [Parameter(Mandatory = $true)][int]$Port,
  [ValidateSet("pg", "mongo", "redis", "none")][string]$Store = "pg",
  [string]$DbName = "",
  # Ghi de file da ton tai (mac dinh: bo qua de khong lam mat sua tay)
  [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$templates = Join-Path $root "docs\templates"
$serviceDir = Join-Path $root "services\$Name"

if ($Name -notmatch '^[a-z0-9]+(-[a-z0-9]+)*$') {
  throw "Ten service phai la chu thuong, ngan cach bang '-' (vd: order-service). Nhan duoc: $Name"
}
if (-not (Test-Path $serviceDir)) {
  throw "Chua co thu muc services\$Name. Tao skeleton Spring Boot truoc (Spring Initializr), roi chay lai script nay."
}
if ($DbName -eq "") { $DbName = "vmarket_" + ($Name -replace '-service$', '' -replace '-', '_') }

# Ghi file, ton trong -Force (chi in thong bao, khong tra gia tri ra pipeline)
function Write-Generated([string]$Path, [string]$Content) {
  if ((Test-Path $Path) -and (-not $Force)) {
    Write-Output "   BO QUA (da ton tai, them -Force de ghi de): $($Path.Replace($root + '\', ''))"
    return
  }
  # UTF8 khong BOM: Docker/YAML doc BOM se loi
  [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding $false))
  Write-Output "   Da tao: $($Path.Replace($root + '\', ''))"
}

# Bo cac khoi ha tang khong dung ra khoi file .env (moi khoi cach nhau 1 dong trong)
function Remove-UnusedStoreBlocks([string]$Text) {
  $keep = @{ pg = "# ---- PostgreSQL"; mongo = "# ---- MongoDB"; redis = "# ---- Redis" }
  $blocks = $Text -split "(\r?\n){2,}" | Where-Object { $_ -notmatch '^(\r?\n)+$' -and $_ -ne "" }
  $result = @()
  foreach ($b in $blocks) {
    $drop = $false
    foreach ($k in $keep.Keys) {
      if ($k -ne $Store -and $b.TrimStart().StartsWith($keep[$k])) { $drop = $true }
    }
    if (-not $drop) { $result += $b.TrimEnd() }
  }
  return ($result -join "`n`n") + "`n"
}

Write-Output ">> Khoi tao ha tang cho $Name (port $Port, store=$Store, db=$DbName)"

# 1. Dockerfile - sinh tu template, chi thay SERVICE_NAME + SERVICE_PORT
$df = Get-Content (Join-Path $templates "Dockerfile.springboot") -Raw
$header = @"
# syntax=docker/dockerfile:1.7
# =============================================================================
# VMarket - Dockerfile cho $Name (port $Port)
# =============================================================================
# File nay sinh ra tu template dung chung: docs/templates/Dockerfile.springboot
# Than file GIONG HET moi service, chi khac 2 dong ARG SERVICE_NAME/SERVICE_PORT.
# => Sua logic build thi sua TEMPLATE truoc roi dong bo lai cho cac service.
"@
$body = $df.Substring($df.IndexOf("# BUILD (context"))
$df = ($header + "`n" + $body).Replace("__SERVICE_NAME__", $Name).Replace("__SERVICE_PORT__", "$Port")
Write-Generated (Join-Path $serviceDir "Dockerfile") $df

# 2. File .env dev + prod
foreach ($pair in @(@("service.env.example", ".env.example"), @("service.env.prod.example", ".env.prod.example"))) {
  $txt = Get-Content (Join-Path $templates $pair[0]) -Raw
  $txt = $txt.Replace("__SERVICE__", $Name).Replace("__PORT__", "$Port").Replace("__DB_NAME__", $DbName)
  Write-Generated (Join-Path $serviceDir $pair[1]) (Remove-UnusedStoreBlocks $txt)
}

# 3. Workflow CI (trigger theo duong dan cua rieng service nay)
$ci = Get-Content (Join-Path $templates "ci-service.yml") -Raw
$ciHeader = @"
# =============================================================================
# VMarket - CI cho $Name
# =============================================================================
# Sinh tu template dung chung: docs/templates/ci-service.yml
# Toan bo logic test + build image nam o .github/workflows/service-ci.yml,
# file nay chi khai bao "khi nao chay" va "chay cho service nao".
#
# TRIGGER THEO DUONG DAN (monorepo): chi chay khi co thay doi trong
# services/$Name/ (hoac parent POM / chinh file CI) -> sua service nay
# KHONG lam chay CI cua cac service con lai.
# =============================================================================
"@
$ci = $ciHeader + "`n" + $ci.Substring($ci.IndexOf("name: __SERVICE__"))
$ci = $ci.Replace("__SERVICE__", $Name)
$wfDir = Join-Path $root ".github\workflows"
if (-not (Test-Path $wfDir)) { New-Item -ItemType Directory -Path $wfDir | Out-Null }
Write-Generated (Join-Path $wfDir "$Name.yml") $ci

Write-Output ""
Write-Output ">> Con lai phai lam BANG TAY (script khong tu sua de tranh hong file chung):"
Write-Output "   1. services\pom.xml        : them <module>$Name</module>"
Write-Output "   2. docker-compose.yml      : them block service (co mau san trong file)"
Write-Output "   3. .env.example (goc repo) : them $(($Name -replace '-', '_').ToUpper())_PORT=$Port neu can publish ra host"
Write-Output "   4. services\$Name\src\main\resources\ : them application-dev.yml / application-prod.yml"
Write-Output ""
Write-Output ">> Kiem tra ngay:"
Write-Output "   cd services; .\mvnw.cmd -pl $Name -am test"
Write-Output "   docker build -f services/$Name/Dockerfile -t vmarket-$Name ."
