# Template khởi tạo service — Dockerfile & CI/CD dùng chung

Thư mục này chứa **bộ template dùng chung** để mỗi microservice mới build, test,
đóng gói và deploy **độc lập** với các service khác — không gộp chung thành một
khối "BE" duy nhất.

| File                       | Sinh ra                            | Vai trò                                              |
| -------------------------- | ---------------------------------- | ---------------------------------------------------- |
| `Dockerfile.springboot`    | `services/<svc>/Dockerfile`        | Build multi-stage bằng Maven, chạy bằng JRE slim      |
| `ci-service.yml`           | `.github/workflows/<svc>.yml`      | Trigger CI theo thư mục của riêng service             |
| `service.env.example`      | `services/<svc>/.env.example`      | Biến môi trường **dev**                               |
| `service.env.prod.example` | `services/<svc>/.env.prod.example` | Biến môi trường **prod** (giá trị nhạy cảm để trống)  |

Logic build/test thật sự nằm ở **`.github/workflows/service-ci.yml`** — một
*reusable workflow* viết một lần, mọi service gọi lại.

---

## 1. Khởi tạo service mới (cách nhanh)

```powershell
# 1. Tạo skeleton Spring Boot (Spring Initializr) vào services/<tên-service>
# 2. Gắn Dockerfile + CI + .env từ template:
scripts\new-service.cmd -Name order-service -Port 8086            # PostgreSQL (mặc định)
scripts\new-service.cmd -Name product-service -Port 8084 -Store mongo
scripts\new-service.cmd -Name cart-service -Port 8085 -Store redis
scripts\new-service.cmd -Name api-gateway -Port 8080 -Store none
```

Tham số: `-Name` (bắt buộc), `-Port` (bắt buộc), `-Store` = `pg|mongo|redis|none`,
`-DbName` (mặc định `vmarket_<tên>`), `-Force` (ghi đè file đã có).

Script **không** sửa file dùng chung, nên còn 4 việc làm tay (script in ra ở cuối):

1. `services/pom.xml` — thêm `<module>order-service</module>`
2. `docker-compose.yml` — thêm block service (đã có mẫu comment sẵn trong file)
3. `.env.example` ở gốc repo — thêm `ORDER_SERVICE_PORT=8086` nếu cần publish ra host
4. Thêm `application-dev.yml` / `application-prod.yml` cho service

Kiểm tra ngay:

```powershell
cd services; .\mvnw.cmd -pl order-service -am test
docker build -f services/order-service/Dockerfile -t vmarket-order-service .
cd ..; scripts\check-env.cmd     # đối chiếu .env service mới với .env gốc
```

## 2. Khởi tạo service mới (cách thủ công)

Copy 4 file trong thư mục này vào đúng chỗ rồi thay chuỗi thay thế:

| Chuỗi                              | Thay bằng       |
| ---------------------------------- | --------------- |
| `__SERVICE_NAME__` / `__SERVICE__` | `order-service` |
| `__SERVICE_PORT__` / `__PORT__`    | `8086`          |
| `__DB_NAME__`                      | `vmarket_order` |

Với file `.env`, xoá các khối hạ tầng không dùng (service dùng PostgreSQL thì xoá
khối MongoDB và Redis).

---

## 3. Dockerfile hoạt động thế nào

3 stage, thân file **giống hệt nhau ở mọi service** — chỉ khác 2 dòng `ARG`:

```dockerfile
ARG SERVICE_NAME=order-service
ARG SERVICE_PORT=8086
```

| Stage     | Làm gì                                             | Vì sao                                                                    |
| --------- | -------------------------------------------------- | ------------------------------------------------------------------------- |
| `deps`    | Chỉ COPY `pom.xml` rồi `mvn dependency:go-offline`  | Layer này **chỉ đổi khi pom đổi** → sửa code Java không phải tải lại `.m2` |
| `build`   | COPY `src` rồi `mvn package -DskipTests`            | Test chạy ở job riêng trong CI (nhanh hơn, báo lỗi rõ hơn)                 |
| `runtime` | `eclipse-temurin:${JAVA_VERSION}-jre-alpine` + jar   | Không có Maven/source/`.m2` → image nhỏ, ít bề mặt tấn công                |

Đo thực tế trên auth-service: build nguội **2 phút 38 giây**, build lại sau khi sửa
code **13,6 giây** (layer `deps` được cache).

Điểm cần nhớ:

- **Build context bắt buộc là thư mục gốc repo** (Dockerfile cần `services/pom.xml`):

  ```bash
  docker build -f services/auth-service/Dockerfile -t vmarket-auth-service .
  ```

- File `.dockerignore` ở gốc dùng kiểu **allowlist** (chặn hết, chỉ mở `services/`).
  Thêm thư mục dùng chung mới (ví dụ `libs/common`) thì **phải thêm một dòng
  `!libs/common`**, nếu không `COPY` sẽ báo "file not found".
- Container chạy bằng user thường `vmarket`, không phải root.
- `HEALTHCHECK` gọi **`/actuator/health/liveness`**, không phải `/actuator/health`.
  Endpoint tổng hợp `/actuator/health` gộp cả sức khoẻ của **phụ thuộc bên ngoài**
  (PostgreSQL, MongoDB, RabbitMQ) — RabbitMQ chớp tắt là container bị đánh dấu
  unhealthy dù ứng dụng vẫn phục vụ bình thường; với service dùng Mongo thì
  endpoint đó còn **treo đến hết timeout** (đã đo thực tế). Muốn kiểm cả phụ thuộc
  thì đổi biến: `-e HEALTHCHECK_PATH=/actuator/health`.
- Đổi JVM options lúc chạy bằng biến `JAVA_OPTS`, không cần build lại image.
- Phiên bản JDK khai báo **một chỗ duy nhất**: `ARG JAVA_VERSION=17`. Cả Maven image
  (stage build) lẫn JRE runtime đều dẫn xuất từ nó, và CI truyền lại giá trị này qua
  `build-args` — xem mục 6.

---

## 4. Pipeline CI hoạt động thế nào

```
.github/workflows/order-service.yml     (~40 dòng, chỉ khai báo "khi nào chạy")
        |  uses:
        v
.github/workflows/service-ci.yml        (toàn bộ logic, viết MỘT lần)
        |-- job test  : mvnw -pl <svc> -am test   -> chỉ test service đó
        +-- job image : docker build + smoke test -> chỉ build image service đó
```

**Trigger theo đường dẫn (path-based)** — sửa `services/auth-service/**` thì chỉ CI
của auth-service chạy, 10 service còn lại không đụng tới. Riêng `services/pom.xml`
(parent POM) nằm trong `paths` của *mọi* service, vì nâng version Spring Boot ảnh
hưởng tất cả.

**Độc lập thật sự:** mỗi service một workflow riêng → CI của service này đỏ không
chặn service khác merge.

Job `test` dùng `mvnw -pl <svc> -am`:

- `-pl <svc>` — chỉ build module của service đó
- `-am` — kèm module mà nó phụ thuộc (ở đây là parent POM)

Job `image` build image rồi **smoke test** không cần DB/RabbitMQ: xác nhận image có
JRE chạy được, có file jar, và **không chạy bằng root**.

Smoke test tìm jar ở `/app/app.jar` — đúng quy ước của `Dockerfile.springboot`. Service
nào dùng Dockerfile khác chuẩn (Gradle, tên jar khác) thì truyền lại đường dẫn thật:

```yaml
with:
  service: order-service
  jar-path: /opt/app/order.jar
```

Cả hai job đều có `timeout-minutes` (test 20, image 30) để lần chạy bị treo bị cắt sớm
thay vì chạy hết timeout mặc định 6 tiếng của GitHub.

Tag nhánh lấy từ `github.head_ref` khi chạy trên pull request, nên tag là tên nhánh
nguồn (`PBL6-2`) chứ không phải `123-merge`.

> **PR từ fork:** khi nhóm bật `push-image: true` sau này, PR từ fork không được cấp
> secret và `GITHUB_TOKEN` chỉ có quyền đọc → bước push sẽ hỏng. Đó là hành vi đúng
> (không cho PR lạ đẩy image lên registry của nhóm).

### Push image lên registry

Mặc định pipeline **chỉ build, không push** — nên chưa cần khai báo secret nào.
Khi nhóm đã chốt registry, bỏ comment khối cuối trong `.github/workflows/<svc>.yml`:

```yaml
jobs:
  ci:
    uses: ./.github/workflows/service-ci.yml
    with:
      service: order-service
      push-image: true
      registry: ghcr.io
    permissions:
      contents: read
      packages: write # bắt buộc để push lên GHCR
    secrets:
      registry-username: ${{ github.actor }}
      registry-password: ${{ secrets.GITHUB_TOKEN }}
```

Image được tag `<registry>/<owner>/vmarket-<service>:<tên-nhánh>` và `:<sha7>`.
Dùng Docker Hub thì đổi `registry: docker.io` và trỏ secret sang
`DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`.

---

## 5. Biến môi trường: tách dev / prod

Mỗi service có 2 file mẫu; file `.env` thật **không bao giờ được commit**
(`.gitignore` chặn cả `.env.*`, chỉ cho qua `*.example`).

| File                | Dùng khi    | Giá trị nhạy cảm               |
| ------------------- | ----------- | ------------------------------ |
| `.env.example`      | Dev local   | Có giá trị giả, chạy được ngay |
| `.env.prod.example` | Deploy prod | **Để trống** — bơm lúc deploy  |

```bash
cp services/auth-service/.env.example services/auth-service/.env
docker run --env-file services/auth-service/.env -p 8081:8081 vmarket-auth-service
```

Nguyên tắc tách dev/prod trong repo này (đã áp dụng ở auth-service):

- `application-dev.yml` **có** giá trị mặc định → clone repo về là chạy được
- `application-prod.yml` **không có** giá trị mặc định cho host/secret → thiếu biến
  là service **fail ngay lúc khởi động**. Cố ý như vậy, để không bao giờ chạy prod
  bằng mật khẩu dev

### Nguồn sự thật — file nào cho cách chạy nào

Repo **cố ý** giữ hai hệ file env, vì hai cách chạy có topology mạng khác nhau
(chạy lẻ thì trỏ `localhost:<port publish>`, chạy compose thì trỏ
`<tên-service>:<port nội bộ>`). Mỗi file là nguồn sự thật cho **đúng một** cách chạy:

| Nguồn                        | Là nguồn sự thật cho             | Ai đọc                                                      |
| ---------------------------- | -------------------------------- | ------------------------------------------------------------ |
| `.env` ở **gốc repo**        | `docker compose up`              | `docker-compose.yml` (anchor `x-common-environment`)         |
| `services/<svc>/.env`        | Chạy lẻ 1 service                | `docker run --env-file`, plugin `.env` của IDE               |
| `application-{dev,prod}.yml` | Giá trị mặc định trong ảnh       | Spring Boot — biến môi trường luôn ghi đè                    |

Compose **không bao giờ** đọc `services/<svc>/.env`. Sửa cấu hình cho cách chạy nào
thì sửa đúng file của cách chạy đó.

**Vấn đề đi kèm:** một số giá trị buộc phải trùng ở cả hai hệ — mật khẩu Postgres /
RabbitMQ, `CORS_ALLOWED_ORIGINS`, `AUTH_JWT_*`. Đổi một bên mà quên bên kia thì không
có gì báo lỗi. Nên có script đối chiếu:

```powershell
scripts\check-env.cmd
```

Script kiểm 3 việc và **exit code 1 nếu lệch**:

1. `.env.example` gốc ↔ `services/*/.env.example` — các giá trị bắt buộc trùng
2. `.env.example` gốc ↔ giá trị mặc định `${VAR:-...}` viết thẳng trong
   `docker-compose.yml` (nơi thứ ba dễ lệch — máy chưa copy `.env` sẽ chạy bằng nó)
3. `services/*/.env.prod.example` — secret phải để trống, `SPRING_PROFILES_ACTIVE=prod`

Workflow `.github/workflows/env-consistency.yml` chạy đúng script này trên CI khi có
thay đổi ở file env hoặc `docker-compose.yml`, nên drift không thể lọt im lặng. Job này
tách riêng khỏi `service-ci.yml` vì là kiểm tra **cấp repo**, chạy một lần cho cả
monorepo và không được chặn CI của 11 service.

Đầu mỗi file `.env.example` đều có khối `NGUON SU THAT` liệt kê biến nào trùng với
`.env` gốc và biến nào **cố ý khác** (host/port hạ tầng).

---

## 6. Sửa template thì làm gì

Thân Dockerfile và workflow **giống hệt nhau ở mọi service**. Khi cần đổi:

1. Sửa `docs/templates/Dockerfile.springboot` trước
2. Đồng bộ lại cho các service (2 dòng `ARG SERVICE_*` của từng service giữ nguyên)
3. Chạy lại CI của một service để kiểm chứng

**Riêng nâng JDK (17 → 21) thì không phải sửa Dockerfile:** đặt `java-version` ở file
gọi CI là xong — job `test` dùng nó cho `setup-java`, job `image` truyền nó vào
Dockerfile qua `build-args: JAVA_VERSION=...`, và Dockerfile dẫn xuất cả Maven image
lẫn JRE runtime từ `ARG JAVA_VERSION`.

```yaml
jobs:
  ci:
    uses: ./.github/workflows/service-ci.yml
    with:
      service: order-service
      java-version: "21" # áp cho CẢ test lẫn image
```

Với pipeline thì dễ hơn: sửa `.github/workflows/service-ci.yml` là **tất cả** service
nhận thay đổi ngay, không phải sửa 11 file.
