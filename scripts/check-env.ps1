# VMarket - doi chieu cac file .env*.example de DRIFT KHONG IM LANG
#
# Repo co Y giu 2 he file env, moi he phuc vu MOT cach chay:
#   - .env O GOC          -> `docker compose up`  (compose doc file nay)
#   - services/<svc>/.env -> chay rieng le mot service (docker run --env-file,
#                            hoac nap bien trong IDE). Compose KHONG doc.
# Vi vay mot so gia tri BUOC PHAI xuat hien o ca hai he. Sua mot ben ma quen
# ben kia thi khong co gi bao loi - script nay chinh la cai bao loi do.
#
# Cach dung:
#   scripts\check-env.cmd            # bao cao + exit code
#   scripts\check-env.cmd -Quiet     # chi in loi (dung trong CI)
#
# Exit code: 0 = khop het, 1 = co lech (CI se do do)

param([switch]$Quiet)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$problems = @()

function Write-Info([string]$Text) {
  if (-not $Quiet) { Write-Output $Text }
}

# Doc file .env kieu KEY=VALUE -> hashtable (bo qua comment va dong trong)
function Read-EnvFile([string]$Path) {
  $map = @{}
  foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
    $t = $line.Trim()
    if ($t -eq "" -or $t.StartsWith("#")) { continue }
    $i = $t.IndexOf("=")
    if ($i -lt 1) { continue }
    $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
  }
  return $map
}

# Duong dan de doc, khong in ca duong dan tuyet doi
function Rel([string]$Path) { return $Path.Replace($root + [IO.Path]::DirectorySeparatorChar, "") }

$rootEnvPath = Join-Path $root ".env.example"
$rootEnv = Read-EnvFile $rootEnvPath

# ---------------------------------------------------------------------------
# Nhom bien PHAI TRUNG GIA TRI giua .env goc va .env cua service.
# Ten bien hai ben co the khac nhau (compose map POSTGRES_* -> DB_*), nen moi
# nhom ghi ro: ten o .env goc + cac ten co the gap trong file cua service.
# CO Y KHONG kiem tra host/port ha tang: dev tro localhost:<port publish>,
# compose tro <ten-service>:<port noi bo> - khac nhau la DUNG.
# ---------------------------------------------------------------------------
$Groups = @(
  @{ Name = "Postgres user";     Root = "POSTGRES_USER";        Service = @("DB_USERNAME") },
  @{ Name = "Postgres password"; Root = "POSTGRES_PASSWORD";    Service = @("DB_PASSWORD") },
  @{ Name = "RabbitMQ user";     Root = "RABBITMQ_USERNAME";    Service = @("RABBITMQ_USERNAME") },
  @{ Name = "RabbitMQ password"; Root = "RABBITMQ_PASSWORD";    Service = @("RABBITMQ_PASSWORD") },
  @{ Name = "CORS origins";      Root = "CORS_ALLOWED_ORIGINS"; Service = @("CORS_ALLOWED_ORIGINS", "APP_CORS_ALLOWED_ORIGINS") },
  @{ Name = "JWT secret";        Root = "AUTH_JWT_SECRET";      Service = @("AUTH_JWT_SECRET") },
  @{ Name = "JWT access TTL";    Root = "AUTH_JWT_ACCESS_TTL";  Service = @("AUTH_JWT_ACCESS_TTL") },
  @{ Name = "JWT refresh TTL";   Root = "AUTH_JWT_REFRESH_TTL"; Service = @("AUTH_JWT_REFRESH_TTL") }
)

Write-Info ">> [1/3] .env goc  <->  services\<svc>\.env.example"

$devFiles = Get-ChildItem -Path (Join-Path $root "services") -Filter ".env.example" -Recurse -Force -File |
            Sort-Object FullName
foreach ($f in $devFiles) {
  $svcEnv = Read-EnvFile $f.FullName
  foreach ($g in $Groups) {
    foreach ($key in $g.Service) {
      if (-not $svcEnv.ContainsKey($key)) { continue }
      if (-not $rootEnv.ContainsKey($g.Root)) {
        $problems += "THIEU o .env.example goc: $($g.Root) (con $(Rel $f.FullName) van khai bao $key)"
        continue
      }
      if ($svcEnv[$key] -cne $rootEnv[$g.Root]) {
        $problems += @(
          "LECH [$($g.Name)] giua .env.example goc va $(Rel $f.FullName):"
          "    .env.example goc  $($g.Root) = $($rootEnv[$g.Root])"
          "    file service      $key = $($svcEnv[$key])"
        ) -join "`n"
      }
    }
  }
}
Write-Info "   Da doi chieu $($devFiles.Count) file .env.example cua service."

# ---------------------------------------------------------------------------
# Gia tri mac dinh viet thang trong docker-compose.yml: ${VAR:-mac-dinh}.
# Day la NOI THU BA de gia tri lech - may chua copy .env se chay bang cac
# mac dinh nay, nen chung phai trung .env.example goc.
# ---------------------------------------------------------------------------
Write-Info ">> [2/3] docker-compose.yml (gia tri mac dinh)  <->  .env.example goc"

$composePath = Join-Path $root "docker-compose.yml"
$compose = Get-Content -LiteralPath $composePath -Raw -Encoding UTF8
$checked = 0
foreach ($m in ([regex]'\$\{([A-Za-z_][A-Za-z0-9_]*):-([^}]*)\}').Matches($compose)) {
  $name = $m.Groups[1].Value
  $def = $m.Groups[2].Value
  if (-not $rootEnv.ContainsKey($name)) { continue }
  $checked++
  if ($rootEnv[$name] -cne $def) {
    $problems += @(
      "LECH giua docker-compose.yml va .env.example goc: $name"
      "    docker-compose.yml mac dinh = $def"
      "    .env.example goc            = $($rootEnv[$name])"
    ) -join "`n"
  }
}
Write-Info "   Da doi chieu $checked gia tri mac dinh trong docker-compose.yml."

# ---------------------------------------------------------------------------
# File .env.prod.example: gia tri nhay cam PHAI de trong. Day la cot loi cua
# triet ly tach dev/prod - lo dien mat khau that vao file .example roi commit
# la mat secret (file .example duoc phep commit, xem .gitignore).
# ---------------------------------------------------------------------------
Write-Info ">> [3/3] services\<svc>\.env.prod.example (secret phai de trong)"

$MustBeEmpty = @(
  "DB_HOST", "DB_USERNAME", "DB_PASSWORD",
  "MONGO_HOST", "REDIS_HOST",
  "RABBITMQ_HOST", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
  "AUTH_JWT_SECRET",
  "CORS_ALLOWED_ORIGINS", "APP_CORS_ALLOWED_ORIGINS"
)

$prodFiles = Get-ChildItem -Path (Join-Path $root "services") -Filter ".env.prod.example" -Recurse -Force -File |
             Sort-Object FullName
foreach ($f in $prodFiles) {
  $prodEnv = Read-EnvFile $f.FullName
  if ($prodEnv["SPRING_PROFILES_ACTIVE"] -ne "prod") {
    $problems += "$(Rel $f.FullName): SPRING_PROFILES_ACTIVE phai la 'prod' (dang la '$($prodEnv['SPRING_PROFILES_ACTIVE'])')"
  }
  foreach ($key in $MustBeEmpty) {
    if (-not $prodEnv.ContainsKey($key)) { continue }
    if ($prodEnv[$key] -ne "") {
      $problems += "$(Rel $f.FullName): $key PHAI de trong trong file mau prod (dang co gia tri)"
    }
  }
}
Write-Info "   Da kiem tra $($prodFiles.Count) file .env.prod.example."

# ---------------------------------------------------------------------------
Write-Info ""
if ($problems.Count -eq 0) {
  Write-Info ">> OK - cac he file env dang khop nhau."
  exit 0
}

Write-Output ">> PHAT HIEN $($problems.Count) VAN DE:"
foreach ($p in $problems) {
  Write-Output ""
  Write-Output "  - $p"
}
Write-Output ""
Write-Output ">> Cach xu ly: sua cho gia tri khop lai o CA HAI he file (xem khoi"
Write-Output "   'NGUON SU THAT' o dau moi file .env.example)."
exit 1
