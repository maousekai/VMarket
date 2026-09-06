# Template Docker + CI/CD dùng chung cho microservice

Tài liệu này mô tả bộ khung để **mỗi microservice Spring Boot của VMarket được build, test, đóng gói và triển khai độc lập** với các service khác — đúng ràng buộc kiến trúc trong SRS §2.5 và NFR-SCA-01.

Đối tượng đọc: mọi thành viên backend, đặc biệt là người sắp khởi tạo một service mới từ Sprint 2.

---

## 1. Bộ template gồm những gì

| File | Vai trò |
| --- | --- |
| `templates/Dockerfile.springboot` | **Dockerfile multi-stage duy nhất** cho mọi service Spring Boot (Maven). Đổi service = đổi 2 build-arg. |
| `templates/Dockerfile.springboot-gradle` | Biến thể Gradle, dùng khi có service chọn Gradle. |
| `templates/caller-workflow.yml.tpl` | Khuôn workflow riêng cho từng service (kèm path-based trigger). |
| `templates/env/*.tpl` | Khuôn 3 file `.env` (example / dev / prod) cho service mới. |
| `.github/workflows/_reusable-springboot-service.yml` | **Pipeline CI/CD dùng chung**: build → test → coverage → image → smoke test → deploy. |
| `.github/workflows/auth-service.yml` | Ví dụ thật đang chạy: pipeline của auth-service. |
| `.dockerignore` | Giới hạn build context ở thư mục gốc. |
| `docker-compose.yml` | Môi trường **dev**: build tại chỗ, hạ tầng publish port để debug. |
| `docker-compose.prod.yml` | Môi trường **prod**: kéo image từ ghcr.io, không build, không publish port hạ tầng. |
| `scripts/new-service.ps1` / `.sh` | Sinh sẵn env + workflow cho service mới. |

---

## 2. Tạo một service mới — 6 bước

### Bước 1 — Sinh khung cấu hình

```powershell
# Windows
.\scripts\new-service.ps1 -Name order-service -Port 8086
```

```bash
# macOS / Linux
./scripts/new-service.sh order-service 8086
```

Script tạo ra (không ghi đè file đã có):

```
services/order-service/env/.env.example
services/order-service/env/.env.dev
services/order-service/env/.env.prod.example
.github/workflows/order-service.yml
```

Bảng port & database chuẩn (theo README và SRS §7.1):

| Service | Port | Database |
| --- | :---: | --- |
| api-gateway | 8080 | — |
| auth-service | 8081 | PostgreSQL `vmarket_auth` |
| user-service | 8082 | PostgreSQL `vmarket_user` |
| shop-service | 8083 | PostgreSQL `vmarket_shop` |
| product-service | 8084 | MongoDB |
| cart-service | 8085 | Redis |
| order-service | 8086 | PostgreSQL `vmarket_order` |
| payment-service | 8087 | PostgreSQL `vmarket_payment` |
| delivery-service | 8088 | PostgreSQL `vmarket_delivery` + Redis |
| review-service | 8089 | PostgreSQL `vmarket_review` |
| notification-service | 8090 | MongoDB |

### Bước 2 — Sinh code Spring Boot

Script in sẵn lệnh `curl` tới Spring Initializr. Sau khi giải nén vào `services/<tên>/`, sửa `pom.xml` để thẻ `<parent>` trỏ về parent POM chung — copy y hệt `services/auth-service/pom.xml`:

```xml
<parent>
    <groupId>com.vmarket</groupId>
    <artifactId>vmarket-services</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
</parent>
```

### Bước 3 — Khai báo module

Thêm vào `services/pom.xml`:

```xml
<module>order-service</module>
```

### Bước 4 — Cấu hình đọc từ biến môi trường

`application.yml` **không được hard-code** host/port/mật khẩu. Dùng cú pháp `${BIẾN:giá-trị-mặc-định}` như auth-service:

```yaml
spring:
  application:
    name: order-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:vmarket_order}
    username: ${DB_USERNAME:vmarket}
    password: ${DB_PASSWORD:vmarket}
server:
  port: ${SERVER_PORT:8086}
management:
  endpoints:
    web:
      exposure:
        include: health,info      # BẮT BUỘC: pipeline dựa vào /actuator/health
```

Kèm `application-prod.yml` với `ddl-auto: validate`, `show-sql: false`, log `INFO` — copy từ auth-service.

> **Bắt buộc:** service phải có dependency `spring-boot-starter-actuator` và trả `/actuator/health`. Bước smoke test của pipeline dựa hoàn toàn vào endpoint này.

### Bước 5 — Thêm vào Docker Compose

Copy khối mẫu `MAU BLOCK BUSINESS SERVICE` trong `docker-compose.yml`, và khối mẫu tương ứng trong `docker-compose.prod.yml`.

### Bước 6 — Kiểm tra tại chỗ rồi mới push

```bash
cd services && ./mvnw -pl order-service -am verify

docker build -f templates/Dockerfile.springboot \
  --build-arg SERVICE_NAME=order-service \
  --build-arg SERVICE_PORT=8086 \
  -t vmarket-order-service:local .

docker run --rm --network vmarket-network -p 8086:8086 \
  --env-file services/order-service/env/.env.dev \
  vmarket-order-service:local
```

Push nhánh → mở PR → pipeline `order-service` tự chạy.

---

## 3. Dockerfile mẫu hoạt động thế nào

Một file `templates/Dockerfile.springboot` dùng cho tất cả:

```bash
docker build -f templates/Dockerfile.springboot \
  --build-arg SERVICE_NAME=auth-service \
  --build-arg SERVICE_PORT=8081 \
  -t vmarket-auth-service:local .
```

> Build context là **thư mục gốc repo** (không phải thư mục service), vì stage build cần cả parent POM `services/pom.xml`.

### Bốn quyết định thiết kế đáng chú ý

**1. Tách 2 pha: dependency trước, source sau.**
Copy `pom.xml` → `mvn dependency:go-offline` → mới copy `src` → `mvn package`. Nhờ vậy layer chứa ~95 thư viện chỉ dựng lại khi POM đổi. Số đo thực tế trên auth-service (06/09/2026):

| | Build đầu | **Build lại sau khi sửa 1 dòng code** |
| --- | :---: | :---: |
| Dockerfile cũ (mỗi service một file) | 1 phút 06 | **1 phút 06** |
| Template mới | 1 phút 58 | **9,8 giây** |

Build đầu chậm hơn (chạy 2 pha resolve dependency), nhưng việc xảy ra ở **mỗi commit** là build lại — nhanh hơn **6,7 lần**.

**2. Layered jar.** `java -Djarmode=tools -jar app.jar extract --layers` tách fat jar thành layer thư viện (ít đổi) và layer ứng dụng (đổi mỗi commit). Đo trực tiếp bằng `docker history` trên auth-service:

| | Layer thay đổi ở **mỗi commit** |
| --- | ---: |
| Dockerfile cũ — copy nguyên fat jar | **60,1 MB** |
| Template mới — chỉ layer `application` | **20,5 kB** |

Mỗi lần deploy chỉ đẩy/kéo lại 20,5 kB thay vì 60 MB.

> `jarmode=tools` là cơ chế của Spring Boot 3.3+ / 4.x. Cơ chế cũ `jarmode=layertools` đã bị bỏ — đã kiểm chứng trực tiếp trên Spring Boot 4.1.1 mà dự án đang dùng.
> Tuỳ chọn `--application-filename app.jar` chuẩn hoá tên jar để một `ENTRYPOINT` duy nhất dùng được cho mọi service.

**3. JRE slim + non-root.** Image cuối là `eclipse-temurin:17-jre-alpine`, không chứa Maven/JDK/source. Chạy bằng user `vmarket` (uid 100), không phải root — giảm thiệt hại nếu ứng dụng bị khai thác (NFR-SEC-05).

**4. HEALTHCHECK nằm trong image.** Mọi service có sẵn health check, không phải khai lại trong `docker-compose.yml` từng service.

Phân bổ dung lượng image auth-service — **248 MB** giải nén trên đĩa, **~122 MB** khi push/pull (đã nén):

| Layer | Dung lượng | Đổi khi nào |
| --- | ---: | --- |
| Alpine + JRE 17 (`eclipse-temurin:17-jre-alpine`) | ~179 MB | Chỉ khi nâng Java |
| Thư viện (`dependencies`) | 60,1 MB | Khi sửa `pom.xml` |
| **Code ứng dụng (`application`)** | **20,5 kB** | **Mỗi commit** |

> Lưu ý khi tự kiểm tra: `docker images` trên Docker 29 (containerd image store) hiển thị 370 MB vì cộng cả manifest đa nền tảng và attestation. Con số thật lấy bằng `docker image inspect <tag> --format '{{.Size}}'`.

---

## 4. Pipeline CI/CD hoạt động thế nào

### Kiến trúc "1 workflow chung + N workflow gọi lại"

```
.github/workflows/
├── _reusable-springboot-service.yml   ← toàn bộ logic, viết MỘT LẦN
├── auth-service.yml                   ← ~20 dòng, path filter riêng
├── user-service.yml                   ← ~20 dòng, path filter riêng
└── ...
```

Sửa quy trình CI (ví dụ đổi cách chạy test) → sửa **một** file, cả 11 service hưởng.

### Path-based trigger — cốt lõi của "build độc lập"

```yaml
on:
  push:
    paths:
      - 'services/auth-service/**'      # code của chính service này
      - 'services/pom.xml'              # parent POM đổi -> ảnh hưởng mọi service
      - 'templates/Dockerfile.springboot'
      - '.github/workflows/auth-service.yml'
      - '.github/workflows/_reusable-springboot-service.yml'
```

Sửa `services/order-service/**` → pipeline auth-service **không chạy**. Đây là điều kiện để 13 service cùng nằm trong một repository mà vẫn build/deploy tách biệt.

### Bốn job

| Job | Làm gì | Vì sao cần |
| --- | --- | --- |
| `build-test` | `./mvnw -pl <service> -am verify` + báo cáo JaCoCo | `-pl ... -am` chỉ build đúng module này, không kéo theo 10 service khác |
| `image` | Build image từ Dockerfile mẫu, push lên `ghcr.io` | Cache layer `type=gha` tái dùng giữa các lần chạy |
| `smoke-test` | Chạy container **thật** cùng PostgreSQL/MongoDB/Redis/RabbitMQ, chờ `/actuator/health` trả `UP` | Bắt các lỗi unit test không thấy: sai ENTRYPOINT, thiếu thư viện, chết lúc khởi động |
| `deploy` | SSH vào server, `pull` + `up -d --no-deps <service>` | `--no-deps` đảm bảo **chỉ service này** restart |

### Ngưỡng coverage

`build-test` đọc `target/site/jacoco/jacoco.xml` và fail nếu độ phủ dòng dưới ngưỡng `coverage-min` của service đó. JaCoCo khai một lần ở `services/pom.xml` nên mọi module đều được đo; các package `config`, `dto`, `entity` và `*Application.class` bị loại trừ để con số phản ánh đúng tầng nghiệp vụ (NFR-MAI-02).

auth-service đang đặt `coverage-min: 60`. Service mới nên khởi đầu `0`, nâng lên `60` ngay khi có unit test nghiệp vụ đầu tiên.

### Quy ước tag image

| Tag | Khi nào | Dùng để |
| --- | --- | --- |
| `sha-a1b2c3d` | Mọi lần push | **Tag bất biến** — deploy và rollback chính xác |
| `dev` / `release` / `product` | Theo nhánh | Xem nhánh đó đang ở bản nào |
| `latest` | Chỉ nhánh mặc định | Mặc định của `docker-compose.prod.yml` |
| `pr-42` | Pull request | Chỉ build kiểm tra, **không push** |

---

## 5. Biến môi trường: `.env` theo từng service

### Ba file cho mỗi service

```
services/<service>/env/
├── .env.example        # COMMIT — danh mục ĐẦY ĐỦ biến, chỉ placeholder
├── .env.dev            # COMMIT — giá trị dev, KHÔNG có bí mật thật
└── .env.prod.example   # COMMIT — khung prod, giá trị bí mật để trống
    .env.prod           # KHÔNG BAO GIỜ COMMIT — tạo trên server, chmod 600
```

`.gitignore` đã chặn `**/.env.prod` và giữ lại `.env.prod.example`.

### Ba lớp cấu hình, thứ tự ưu tiên từ cao xuống thấp

1. `environment:` trong `docker-compose*.yml` — giá trị hạ tầng dùng chung
2. `env_file:` trỏ tới `.env.dev` / `.env.prod` — cấu hình riêng của service
3. Giá trị mặc định `${BIẾN:mặc-định}` trong `application.yml` — để chạy được bằng `mvnw` không cần Docker

### Khác biệt dev ↔ prod

| | dev | prod |
| --- | --- | --- |
| Spring profile | `dev` | `prod` |
| `ddl-auto` | `update` (Hibernate tự tạo bảng) | `validate` (không đụng schema) |
| `show-sql` | `true` | `false` |
| Log | `DEBUG` | `INFO` (NFR-SEC-06) |
| Bí mật | giá trị dev dùng chung, commit được | chỉ trong `.env.prod` trên server / GitHub Secrets |
| Nguồn image | `build:` tại chỗ | `image:` kéo từ ghcr.io |
| Port hạ tầng | publish ra host để debug | không publish |

### Quy tắc bí mật

- Bí mật thật **chỉ** ở hai nơi: **GitHub Secrets** (cho CI/CD) và **`.env.prod` trên server** (chmod 600).
- Thêm biến mới: khai vào `.env.example` **trước**, rồi mới tới `.env.dev` / `.env.prod.example`.
- `.dockerignore` chặn mọi `**/.env*` để file cấu hình không lọt vào image.

---

## 6. Triển khai lên server

### Bật deploy tự động

Khai 4 secret trong **Settings → Secrets and variables → Actions**:

| Secret | Nội dung |
| --- | --- |
| `DEPLOY_HOST` | IP hoặc domain của VPS |
| `DEPLOY_USER` | User SSH |
| `DEPLOY_SSH_KEY` | Private key (toàn bộ nội dung, kể cả dòng `-----BEGIN...`) |
| `DEPLOY_PATH` | Đường dẫn repo trên server, vd `/opt/vmarket` |

Rồi đổi trong workflow của service: `deploy: false` → `deploy: true`.

### Triển khai lần đầu trên server

```bash
git clone https://github.com/maousekai/VMarket.git && cd VMarket
cp .env.example .env
cp services/auth-service/env/.env.prod.example services/auth-service/env/.env.prod
# ...điền các giá trị <<< ĐIỀN >>>
chmod 600 services/*/env/.env.prod
echo $GHCR_TOKEN | docker login ghcr.io -u <user> --password-stdin
docker compose -f docker-compose.prod.yml up -d
```

### Cập nhật một service (chính là việc job `deploy` làm)

```bash
docker compose -f docker-compose.prod.yml pull auth-service
docker compose -f docker-compose.prod.yml up -d --no-deps auth-service
```

### Rollback

```bash
AUTH_SERVICE_TAG=sha-a1b2c3d \
  docker compose -f docker-compose.prod.yml up -d --no-deps auth-service
```

---

## 7. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp | Cách xử lý |
| --- | --- | --- |
| `dependency:go-offline` fail | Thiếu dependency trong POM hoặc mạng chập chờn | Chạy `./mvnw -pl <svc> -am dependency:go-offline` tại máy để xem lỗi thật |
| Build Docker báo không thấy `services/pom.xml` | Build context sai | Phải build từ **thư mục gốc repo**, kèm `-f templates/Dockerfile.springboot` |
| Smoke test fail, log rỗng | App chết ngay lúc khởi động | Xem step *In log container khi thất bại* trong Actions |
| `/actuator/health` trả `DOWN` | Thiếu health indicator (RabbitMQ/DB không kết nối được) | Kiểm tra `DB_HOST`, `RABBITMQ_HOST` truyền vào container |
| Pipeline không chạy khi sửa code | `paths` trong workflow chưa khớp thư mục | Đối chiếu `paths` với đường dẫn thật của file vừa sửa |
| Pipeline của service khác cũng chạy | Bạn vừa sửa `services/pom.xml` hoặc Dockerfile mẫu | Đúng như thiết kế — file dùng chung ảnh hưởng mọi service |
| `denied: permission_denied` khi push image | Workflow thiếu `permissions: packages: write` | Kiểm tra khối `permissions` ở workflow gọi |
| Tiếng Việt trong file sinh ra bị lỗi ký tự | PowerShell 5.1 đọc UTF-8 theo ANSI | Script đã xử lý bằng `Get-Content -Encoding UTF8` |

---

## 8. Việc còn lại (ticket sau)

1. **Chuyển 10 service còn lại sang Dockerfile mẫu.** Hiện chỉ auth-service dùng template; api-gateway và 9 service khác vẫn giữ Dockerfile riêng. Theo quy ước của nhóm (xem `worklogs/PBL6-10.md`), nên làm **1–2 service mỗi ticket**, mỗi lần kèm review riêng: xoá `services/<svc>/Dockerfile`, đổi khối compose sang `templates/Dockerfile.springboot`, chạy `scripts/new-service.ps1` để sinh env, thêm workflow.
2. **Nâng `coverage-min` lên 60** cho từng service khi có unit test nghiệp vụ.
3. **Chuẩn bị VPS** và bật `deploy: true`.
4. **Template cho service Python/FastAPI** (ai-search, recommendation, chatbot) — cấu trúc tương tự nhưng stage build khác.
5. **Cập nhật SRS §2.5**: tài liệu ghi CI/CD bằng **Jenkins** "thay cho GitHub Actions", trong khi NFR-MAI-02 (§6.5) lại ghi **GitHub Actions**. Hai chỗ mâu thuẫn nhau; ticket này đã triển khai theo GitHub Actions, cần sửa §2.5 cho khớp.

---

## 9. Tham chiếu

- [SRS — Đặc tả yêu cầu](SRS-VMarket.md) — §2.5 ràng buộc, §6.3 NFR-SCA, §6.5 NFR-MAI, §7.1 chiến lược dữ liệu
- [CONTRIBUTING.md](../CONTRIBUTING.md) — quy ước branch / commit / PR
- [README.md](../README.md) — kiến trúc tổng quan, cách chạy local
