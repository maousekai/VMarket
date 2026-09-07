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
| `runtime` | `eclipse-temurin:17-jre-alpine` + đúng file jar     | Không có Maven/source/`.m2` → image nhỏ, ít bề mặt tấn công                |

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

Ba nguồn biến môi trường, đừng nhầm:

| Nguồn                        | Ai đọc                                                              |
| ---------------------------- | ------------------------------------------------------------------- |
| `.env` ở **gốc repo**        | `docker-compose.yml` (hạ tầng chung, anchor `x-common-environment`)  |
| `services/<svc>/.env`        | Khi chạy container **riêng lẻ** bằng `--env-file`                    |
| `application-{dev,prod}.yml` | Giá trị mặc định trong ảnh, bị biến môi trường ghi đè                |

---

## 6. Sửa template thì làm gì

Thân Dockerfile và workflow **giống hệt nhau ở mọi service**. Khi cần đổi (ví dụ
nâng JDK 17 → 21):

1. Sửa `docs/templates/Dockerfile.springboot` trước
2. Đồng bộ lại cho các service (2 dòng `ARG` của từng service giữ nguyên)
3. Chạy lại CI của một service để kiểm chứng

Với pipeline thì dễ hơn: sửa `.github/workflows/service-ci.yml` là **tất cả** service
nhận thay đổi ngay, không phải sửa 11 file.
