# VMarket - tao bo khung CI/CD cho mot microservice Spring Boot moi
# Cach dung: scripts\new-service.ps1 <ten-service> <port> [ten-database]
#   vi du:   scripts\new-service.ps1 order-service 8086
#
# Sinh san tu build\templates\:
#   services\<ten>\env\.env.example
#   services\<ten>\env\.env.dev
#   services\<ten>\env\.env.prod.example
#   .github\workflows\<ten>.yml   (kem path-based trigger)
#
# KHONG ghi de file da ton tai. KHONG sinh code Java (huong dan in o cuoi).
# Chi tiet day du: docs\CICD-TEMPLATE.md

param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidatePattern('^[a-z][a-z0-9-]*-service$')]
  [string]$Name,

  [Parameter(Mandatory = $true, Position = 1)]
  [ValidateRange(8080, 8199)]
  [int]$Port,

  [Parameter(Position = 2)]
  [ValidatePattern('^[a-z][a-z0-9_]*$')]
  [string]$DbName
)

$ErrorActionPreference = 'Stop'

# Thu muc goc repo = thu muc cha cua scripts\
$root   = Split-Path $PSScriptRoot -Parent
$tplDir = Join-Path $root 'templates'

if (-not $DbName) {
  # order-service -> vmarket_order
  $short  = $Name -replace '-service$', ''
  $DbName = 'vmarket_' + ($short -replace '-', '_')
}

$envDir      = Join-Path $root "services\$Name\env"
$workflowOut = Join-Path $root ".github\workflows\$Name.yml"

Write-Host ""
Write-Host "=== Tao khung CI/CD cho service moi ===" -ForegroundColor Cyan
Write-Host "  Service : $Name"
Write-Host "  Port    : $Port"
Write-Host "  Database: $DbName"
Write-Host ""

# Thay placeholder trong template roi ghi ra dich (bo qua neu file da co).
function Copy-Template([string]$From, [string]$To) {
  if (Test-Path $To) {
    Write-Host "  [BO QUA] da ton tai: $To" -ForegroundColor Yellow
    return
  }
  if (-not (Test-Path $From)) {
    throw "Khong tim thay file template: $From"
  }

  # -Encoding UTF8 bat buoc: Windows PowerShell 5.1 mac dinh doc file theo
  # code page ANSI, se lam hong tieng Viet co dau trong template.
  $content = (Get-Content $From -Raw -Encoding UTF8) `
    -replace '__SERVICE_NAME__', $Name `
    -replace '__SERVICE_PORT__', $Port `
    -replace '__DB_NAME__', $DbName

  # UTF-8 khong BOM: Docker/GitHub Actions doc dung, git diff sach
  [System.IO.File]::WriteAllText($To, $content, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "  [TAO] $To" -ForegroundColor Green
}

New-Item -ItemType Directory -Force -Path $envDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root '.github\workflows') | Out-Null

Copy-Template (Join-Path $tplDir 'env\.env.example.tpl')      (Join-Path $envDir '.env.example')
Copy-Template (Join-Path $tplDir 'env\.env.dev.tpl')          (Join-Path $envDir '.env.dev')
Copy-Template (Join-Path $tplDir 'env\.env.prod.example.tpl') (Join-Path $envDir '.env.prod.example')
Copy-Template (Join-Path $tplDir 'caller-workflow.yml.tpl')   $workflowOut

$pkg = $Name -replace '-service$', ''
$initializr = "https://start.spring.io/starter.zip?type=maven-project&language=java&javaVersion=17" +
              "&groupId=com.vmarket&artifactId=$Name&name=$Name&packageName=com.vmarket.$pkg" +
              "&dependencies=web,actuator,validation,lombok"

Write-Host ""
Write-Host "=== CON 4 BUOC LAM TAY ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "1) Sinh code Spring Boot vao services\$Name (neu chua co):"
Write-Host ""
Write-Host "     curl -o $Name.zip `"$initializr`"" -ForegroundColor Gray
Write-Host ""
Write-Host "   Sau khi giai nen: sua services\$Name\pom.xml de the parent tro ve"
Write-Host "   com.vmarket:vmarket-services (relativePath ../pom.xml) - copy y het"
Write-Host "   services\auth-service\pom.xml."
Write-Host ""
Write-Host "2) Khai bao module trong services\pom.xml:"
Write-Host ""
Write-Host "     <module>$Name</module>" -ForegroundColor Gray
Write-Host ""
Write-Host "3) Them block vao docker-compose.yml (copy mau comment 'MAU BLOCK"
Write-Host "   BUSINESS SERVICE' trong file do) va docker-compose.prod.yml."
Write-Host ""
Write-Host "4) Kiem tra tai cho truoc khi push:"
Write-Host ""
Write-Host "     cd services; .\mvnw.cmd -pl $Name -am verify" -ForegroundColor Gray
Write-Host "     docker build -f templates/Dockerfile.springboot --build-arg SERVICE_NAME=$Name --build-arg SERVICE_PORT=$Port -t vmarket-$Name`:local ." -ForegroundColor Gray
Write-Host ""
Write-Host "Chi tiet day du: docs\CICD-TEMPLATE.md"
Write-Host ""
