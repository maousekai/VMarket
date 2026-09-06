# Worklog — Dockerfile mẫu + pipeline CI/CD dùng chung cho microservices

- **Ticket Jira:** _(điền mã sau khi tạo, ví dụ PBL6-12 — nhớ đổi tên file này thành `PBL6-<số>.md`)_
- **Branch đề xuất:** `ticket/PBL6-<số>-cicd-template`
- **Ngày thực hiện:** 06/09/2026
- **Sprint:** Sprint 1 (phải xong trước khi Sprint 2 code Auth Service)
- **Căn cứ SRS:** §2.1 (13 microservice), §2.5 (bắt buộc Docker), §6.3 NFR-SCA-01, §6.5 NFR-MAI-01/02/03, §7.1

---

## 0. Mục tiêu

Mỗi microservice phải **build – test – đóng gói – triển khai độc lập**, không gộp thành một khối "BE" duy nhất. Ticket này xây bộ khung dùng chung để mọi service từ Sprint 2 trở đi chỉ việc copy, thay vì mỗi người tự viết Dockerfile/pipeline riêng.

## 1. Hiện trạng trước khi làm

| Điểm | Trạng thái cũ | Vấn đề |
| --- | --- | --- |
| Dockerfile | 11 file gần như giống hệt (`services/*/Dockerfile`) | Sửa cách đóng gói phải sửa 11 chỗ; dễ lệch nhau |
| Cache dependency | Copy `pom.xml` + `src` rồi mới `mvn package` | Sửa 1 dòng code → tải lại toàn bộ ~95 thư viện |
| Layer | Copy nguyên fat jar 60 MB thành 1 layer | Mỗi deploy đẩy/kéo lại 60 MB |
| CI/CD | **Không có** — thư mục `.github/` chưa tồn tại | Không có gì tự động build/test; NFR-MAI-02 chưa đáp ứng |
| Health check | Khai tay trong `docker-compose.yml`, chỉ auth-service có | Service khác container hoá sẽ không có |
| `.dockerignore` | Chỉ `frontend/` có | Build context gốc gửi cả `.git` + `node_modules` |
| Biến môi trường | 1 file `.env` gốc cho toàn hệ thống | Không tách được cấu hình dev/prod theo từng service |
| Coverage | Không đo | NFR-MAI-02 yêu cầu tối thiểu 60% |

## 2. Đã làm

### 2.1. Dockerfile mẫu dùng chung — `templates/Dockerfile.springboot`

**Một** file cho cả 11 service Spring Boot; đổi service chỉ cần đổi build-arg:

```bash
docker build -f templates/Dockerfile.springboot \
  --build-arg SERVICE_NAME=auth-service --build-arg SERVICE_PORT=8081 \
  -t vmarket-auth-service:local .
```

Bốn thay đổi kỹ thuật so với Dockerfile cũ:

1. **Tách 2 pha build** — copy `pom.xml` → `dependency:go-offline` → mới copy `src` → `package`. Layer thư viện chỉ dựng lại khi POM đổi.
2. **Layered jar** — `java -Djarmode=tools -jar app.jar extract --layers --application-filename app.jar`. Đã kiểm chứng trực tiếp: Spring Boot 4.1.1 hỗ trợ `jarmode=tools` (cơ chế cũ `layertools` đã bị bỏ). Tuỳ chọn `--application-filename` chuẩn hoá tên jar để một `ENTRYPOINT` dùng chung cho mọi service.
3. **HEALTHCHECK nằm trong image** — mọi service tự có, không phải khai lại trong compose.
4. **Non-root + `exec java`** — chạy user `vmarket` (uid 100); `exec` cho java làm PID 1 để nhận SIGTERM và shutdown gracefully.

Kèm `templates/Dockerfile.springboot-gradle` (biến thể Gradle, chưa dùng — giữ sẵn cho service tương lai) và `.dockerignore` ở thư mục gốc.

### 2.2. Pipeline CI/CD — `.github/workflows/`

Kiến trúc **1 workflow dùng chung + N workflow gọi lại**:

```
_reusable-springboot-service.yml   ← toàn bộ logic, viết một lần
auth-service.yml                   ← ~20 dòng, path filter riêng
```

Bốn job: `build-test` → `image` → `smoke-test` → `deploy`.

- `build-test`: `./mvnw -pl <service> -am verify` — **chỉ** build module đó, không kéo theo 10 service khác. Đọc `jacoco.xml`, fail nếu coverage dưới ngưỡng.
- `image`: build từ Dockerfile mẫu, cache layer `type=gha`, push lên `ghcr.io` với tag `sha-<7 ký tự>` / `<branch>` / `latest`. Chỉ chạy khi thật sự push image (`if: inputs.push-image`) — với PR thì job này bị skip và `smoke-test` tự build lấy, tránh build thừa một lượt. Lưu ý `latest` bám **nhánh mặc định của repo** (`dev`) nên **không dùng để deploy prod**; prod mặc định tag `product`.
- `smoke-test`: chạy container **thật** cùng PostgreSQL/MongoDB/Redis/RabbitMQ (service containers của GitHub Actions), chờ `/actuator/health` trả `UP`. Đây là bằng chứng tự động cho DoD; bắt được lỗi mà unit test không thấy.
- `deploy`: SSH + `docker compose up -d --no-deps <service>` — cờ `--no-deps` đảm bảo **chỉ service này** restart. Mặc định `deploy: false` vì nhóm chưa có VPS.

**Path-based trigger** (`services/auth-service/**`, `services/pom.xml`, `templates/Dockerfile.springboot`, 2 file workflow) là cơ chế đảm bảo 13 service nằm chung một repository mà vẫn build/deploy tách biệt.

### 2.3. Biến môi trường theo từng service, tách dev/prod

```
services/auth-service/env/
├── .env.example        # COMMIT — danh mục đầy đủ biến, chỉ placeholder
├── .env.dev            # COMMIT — giá trị dev, không có bí mật thật
└── .env.prod.example   # COMMIT — khung prod, bí mật để trống
    .env.prod           # KHÔNG commit — chỉ trên server, chmod 600
```

- `.gitignore` bổ sung `**/.env.prod` + `!**/.env.prod.example`; đã kiểm chứng bằng `git check-ignore`.
- `docker-compose.yml` (dev): auth-service dùng `env_file` trỏ `.env.dev`, build bằng Dockerfile mẫu, bỏ khối `healthcheck` trùng lặp (image đã có).
- `docker-compose.prod.yml` (mới): **không build**, kéo image từ `ghcr.io`; `restart: unless-stopped`; giới hạn log 10 MB × 3; hạ tầng **không publish port** ra Internet; service chỉ mở trên `127.0.0.1`.

### 2.4. Tài liệu & script

- `docs/CICD-TEMPLATE.md` — hướng dẫn chính: 6 bước tạo service mới, cách Dockerfile/pipeline hoạt động, quy ước `.env`, triển khai, rollback, bảng xử lý sự cố.
- `CONTRIBUTING.md` — thêm mục 5 (CI/CD), mục 6 (quy tắc bí mật), mục 7 (checklist trước khi mở PR).
- `README.md` — cập nhật cấu trúc repo, lệnh build Docker mới, mục CI/CD.
- `services/auth-service/README.md` — cập nhật (Dockerfile riêng đã bị xoá).
- `scripts/new-service.ps1` + `new-service.sh` — sinh sẵn 3 file `env/` + workflow cho service mới, thay placeholder tự động, không ghi đè file đã có.

### 2.5. JaCoCo

Khai một lần ở `services/pom.xml` (`prepare-agent` + `report` phase `verify`) nên **mọi module** đều được đo. Loại trừ `**/*Application.class`, `config`, `dto`, `entity` để con số phản ánh đúng tầng nghiệp vụ theo NFR-MAI-02. Plugin chỉ **sinh báo cáo**, việc chặn/không chặn do input `coverage-min` của workflow quyết định (mỗi service một ngưỡng).

## 3. Kiểm chứng (đã chạy thật, không phải lý thuyết)

| # | Kiểm tra | Lệnh | Kết quả |
| --- | --- | --- | --- |
| 1 | Spring Boot 4.1.1 hỗ trợ `jarmode=tools` | `java -Djarmode=tools -jar app.jar help extract` | ✅ Có, kèm tuỳ chọn `--application-filename` |
| 2 | Layered jar chạy được | `java -jar extracted/application/app.jar` | ✅ Boot lên tới bước kết nối DB (đúng, vì Postgres chưa bật) |
| 3 | Build image từ template | `docker build -f templates/Dockerfile.springboot ...` | ✅ Thành công, 1 phút 58 |
| 4 | **Build lại sau khi sửa 1 dòng code** | so sánh Dockerfile cũ vs template | ✅ **66 s → 9,8 s** (nhanh 6,7 lần) |
| 5 | Layer thay đổi mỗi commit | `docker history` | ✅ **60,1 MB → 20,5 kB** |
| 6 | Container chạy & health | `docker run` + `/actuator/health` | ✅ `{"status":"UP"}` sau ~12 s |
| 7 | Docker HEALTHCHECK | `docker inspect --format '{{.State.Health.Status}}'` | ✅ `healthy` |
| 8 | Chạy non-root | `docker exec auth id` | ✅ `uid=100(vmarket) gid=101(vmarket)` |
| 9 | Endpoint nghiệp vụ | `GET /api/auth/health` | ✅ `{"status":"UP","service":"auth-service",...}` |
| 10 | Build qua compose | `docker compose build auth-service` | ✅ Thành công |
| 11 | Compose dev hợp lệ | `docker compose config --quiet` | ✅ OK |
| 12 | Compose prod hợp lệ | `docker compose -f docker-compose.prod.yml config --quiet` | ✅ OK, `SPRING_PROFILES_ACTIVE=prod` |
| 13 | JaCoCo chạy & sinh report | `./mvnw -pl auth-service -am verify` | ✅ 2 test pass, `jacoco.xml` sinh ra |
| 14 | Độ phủ tầng nghiệp vụ | đọc `jacoco.xml` | ✅ **100 % (12/12 dòng)** — nhưng lúc đó auth-service mới có `HealthService`; xem mục 6 về việc để `coverage-min: 0` |
| 15 | `.env.prod` bị git chặn | `git check-ignore -v` | ✅ Bị chặn; `.env.dev`/`.env.prod.example` vẫn được commit |
| 16 | Script scaffold | `.\scripts\new-service.ps1 -Name demo-service -Port 8199` | ✅ Sinh 4 file, không còn placeholder (đã xoá demo sau khi thử) |
| 17 | YAML workflow hợp lệ | parse bằng PyYAML | ✅ 4 job: build-test, image, smoke-test, deploy |

**Chưa kiểm chứng được tại máy:** pipeline chạy thật trên GitHub Actions (cần push lên remote) và job `deploy` (nhóm chưa có VPS). Xem mục 5.

## 4. File thay đổi

| File | Trạng thái | Nội dung |
| --- | :---: | --- |
| `templates/Dockerfile.springboot` | mới | Dockerfile mẫu dùng chung (Maven) |
| `templates/Dockerfile.springboot-gradle` | mới | Biến thể Gradle |
| `templates/caller-workflow.yml.tpl` | mới | Khuôn workflow cho service mới |
| `templates/env/*.tpl` | mới | Khuôn 3 file `.env` |
| `.github/workflows/_reusable-springboot-service.yml` | mới | Pipeline CI/CD dùng chung (4 job) |
| `.github/workflows/auth-service.yml` | mới | Pipeline riêng auth-service (PoC) |
| `.dockerignore` | mới | Giới hạn build context ở thư mục gốc |
| `docker-compose.prod.yml` | mới | Compose môi trường production |
| `docs/CICD-TEMPLATE.md` | mới | Hướng dẫn chính |
| `scripts/new-service.ps1`, `.sh` | mới | Script scaffold service mới |
| `services/auth-service/env/*` | mới | 3 file `.env` của auth-service |
| `services/auth-service/Dockerfile` | **xoá** | Thay bằng template dùng chung |
| `docker-compose.yml` | sửa | auth-service dùng template + `env_file`; bỏ healthcheck trùng; cập nhật khối mẫu |
| `services/pom.xml` | sửa | Thêm JaCoCo (`prepare-agent` + `report`) |
| `.gitignore` | sửa | Chặn `**/.env.prod`, giữ `.env.prod.example` |
| `README.md`, `CONTRIBUTING.md`, `services/auth-service/README.md` | sửa | Cập nhật theo cấu trúc mới |

> Lưu ý khi review: thư mục đặt template ban đầu định là `build/` nhưng `.gitignore` của repo đã chặn `build/` (dành cho output Gradle/Vite) nên template sẽ không được commit. Đã đổi thành `templates/`.

## 5. Việc còn lại

1. **Chuyển 10 service còn lại sang Dockerfile mẫu** — ticket riêng, mỗi lần 1–2 service kèm review, đúng quy ước nhóm đã thống nhất ở `PBL6-10.md`. Hiện api-gateway và 9 service khác vẫn giữ Dockerfile riêng (vẫn chạy bình thường, không bị ảnh hưởng).
2. **Xác nhận pipeline chạy xanh trên GitHub** sau khi push nhánh — và chạy 2 commit đối chứng để chụp bằng chứng path filter (sửa auth → chỉ pipeline auth chạy; sửa order → pipeline auth không chạy).
3. **Chuẩn bị VPS**, khai 4 secret `DEPLOY_*`, bật `deploy: true`.
4. **Nâng `coverage-min` lên 60** cho từng service khi có unit test nghiệp vụ.
5. **Template cho 3 service Python/FastAPI** (ai-search, recommendation, chatbot).
6. **Mâu thuẫn trong SRS cần chốt:** §2.5 ghi CI/CD bằng **Jenkins** "thay cho GitHub Actions", nhưng §6.5 NFR-MAI-02 ghi **GitHub Actions**. Ticket này làm theo GitHub Actions (khớp NFR-MAI-02, miễn phí với repo GitHub, không cần dựng server Jenkins riêng). Cần sửa §2.5 cho khớp — nếu nhóm/GVHD quyết định giữ Jenkins thì phải làm lại phần pipeline.

## 6. Xử lý review PR

Vòng review đầu tiên nêu 3 điểm chặn merge, 4 điểm nên sửa và 8 điểm nhỏ. Tất cả đã được xử lý:

### Chặn merge

| # | Vấn đề | Cách xử lý |
| --: | --- | --- |
| 1 | PR dựa trên base cũ (`c542b88`, trước PBL6-41/42) → chắc chắn conflict ở `services/pom.xml`, `docker-compose.yml`, `auth-service/README.md`, `.gitignore` | Nhánh đã được **rebase lên `dev` hiện tại** (đã có PBL6-41 merged + PBL6-42 merged). Conflict xử lý tại chỗ, không còn chồng lấn |
| 2 | Tên biến JWT không khớp `application.yml` mà PBL6-41 đã merge: dùng `JWT_SECRET` / `JWT_ACCESS_TTL_MINUTES=30` / `JWT_REFRESH_TTL_DAYS=7` | Đổi đúng tên **và** đúng kiểu: `AUTH_JWT_SECRET`, `AUTH_JWT_ACCESS_TTL=15m`, `AUTH_JWT_REFRESH_TTL=30d` (Duration của Spring) trong cả 3 file `env/` và `CONTRIBUTING.md`. Thêm quy tắc "tên biến phải khớp `application.yml`" kèm lệnh `grep` đối chiếu vào `CONTRIBUTING.md` mục 6, `docs/CICD-TEMPLATE.md` mục 5 và `.env.dev.tpl` |
| 3 | Hai nguồn cấu hình chồng nhau: PBL6-41 khai `AUTH_JWT_*` ở `.env.example` gốc + `docker-compose.yml`, PR này khai ở `services/<service>/env/` | Chọn hướng **per-service** của PR này. Đã gỡ `AUTH_JWT_*` khỏi `.env.example` gốc và khỏi khối `environment:` của auth-service trong `docker-compose.yml`, để lại comment trỏ sang nguồn mới |

### Nên sửa

| # | Vấn đề | Cách xử lý |
| --: | --- | --- |
| 4 | `docker-compose.prod.yml` mặc định tag `latest`, mà `latest` gắn với `is_default_branch` = nhánh `dev` → deploy prod ra HEAD của dev | Đổi mặc định thành `${AUTH_SERVICE_TAG:-product}` (cả khối auth-service lẫn khối mẫu). Comment sai ở dòng 146 đã viết lại; `docs/CICD-TEMPLATE.md` thêm cảnh báo và ví dụ ghim `sha-` |
| 5 | `coverage-min: 60` là sớm, mâu thuẫn với chính `caller-workflow.yml.tpl` (ghi `0`) | Hạ về `coverage-min: 0` (chỉ in báo cáo). Ghi rõ lý do tại chỗ trong `auth-service.yml` và trong `docs/CICD-TEMPLATE.md`: 100 % đo được là khi service mới có `HealthService`, PBL6-42 vừa thêm `service/`/`controller/`/`exception/` nên chưa có số liệu thật |
| 6 | Job `image` build xong rồi bỏ khi `push-image: false` (PR), `smoke-test` build lại từ đầu | Thêm `if: ${{ inputs.push-image }}` cho job `image`; `smoke-test` chuyển sang phụ thuộc **mềm** (`needs: [build-test, image]` + `always()` chấp nhận `image` bị skip) và tự build + load khi cần |
| 7 | `Dockerfile.springboot` `mkdir -p` + `.keep` cho cả 3 thư mục layer che lỗi `jarmode=tools extract` | Chỉ `snapshot-dependencies` (hợp lệ khi rỗng) được tạo sẵn. Thêm 2 chốt `test -n "$(ls -A ...)"` bắt buộc `dependencies/` và `application/` có nội dung — extract hỏng là **fail build ngay**, không ra image thiếu class. Áp dụng cho cả biến thể Gradle |

### Nhỏ

| Vấn đề | Cách xử lý |
| --- | --- |
| Job `deploy` nội suy `${{ secrets.* }}` / `${{ github.actor }}` thẳng vào `script:` của `appleboy/ssh-action` | Chuyển sang `env:` + `envs:` — secret đi vào phiên SSH dưới dạng biến môi trường, không nằm nguyên văn trên dòng lệnh chạy ở server. Áp dụng cho cả 2 step SSH |
| `-Djava.security.egd=file:/dev/./urandom` là workaround của JDK 8 | Bỏ khỏi `JAVA_OPTS` ở cả 2 Dockerfile mẫu |
| `# syntax=docker/dockerfile:1.7` khai nhưng không dùng tính năng nào | Bỏ khỏi cả 2 Dockerfile mẫu |
| `smoke-test` dùng `rabbitmq:4.1-alpine`, compose dùng `4.1-management` | Thống nhất `4.1-management` để CI chạy đúng broker của dev/prod |
| `new-service.sh`: `$3` (tên DB) không validate trước khi vào `sed` — ký tự `/` hoặc `&` phá cú pháp | Thêm validate `^[a-z][a-z0-9_]*$` (cũng đúng quy tắc định danh PostgreSQL). Bản `.ps1` thêm `[ValidatePattern]` tương ứng |
| `env_file` dạng dài cần Docker Compose ≥ v2.24 | Ghi vào bảng "Yêu cầu môi trường" của `README.md` và mục 5 của `docs/CICD-TEMPLATE.md` |
| `DB_*` / `RABBITMQ_*` trong `.env.dev` bị `environment:` của compose ghi đè | Không đổi hành vi (đúng thiết kế), nhưng ghi rõ hệ quả này ở đầu `.env.dev`, `.env.prod.example`, `.env.dev.tpl` và mục "Ba lớp cấu hình" của tài liệu |
| `dependency:go-offline` không đủ 100 %, stage `package` vẫn có thể tải mạng | Sửa lại comment trong Dockerfile mẫu cho đúng: mục tiêu là *cache phần lớn*, không phải build offline tuyệt đối |
