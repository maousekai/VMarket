# CONTRIBUTING — Quy ước làm việc nhóm VMarket (PBL6)

## 1. Mô hình branch

| Branch    | Vai trò                                                                 |
| --------- | ----------------------------------------------------------------------- |
| `dev`     | Nhánh mặc định, tích hợp chính. Mọi ticket đều merge vào đây.           |
| `release` | Ổn định cuối mỗi sprint. Được đẩy qua từ `dev`.                         |
| `product` | Bản chạy production. Chỉ đẩy từ `release` khi release đã ổn định.       |

```
ticket branch ──PR──> dev ──PR (cuối sprint)──> release ──PR (khi ổn định)──> product
```

## 2. Quy trình làm việc với một ticket

1. `git checkout dev && git pull origin dev`
2. Tạo nhánh mới từ `dev`, **đặt tên theo ticket**:
   ```bash
   git checkout -b PBL6-7-setup-be
   ```
3. Code và commit theo quy ước ở mục 3.
4. Push nhánh lên origin:
   ```bash
   git push -u origin PBL6-7-setup-be
   ```
5. Tạo **Pull Request** vào nhánh `dev`, cần **1 người review** approve.
6. Sau khi merge thì xoá nhánh ticket.

Cuối mỗi sprint: tạo PR `dev → release`. Khi release ổn định: tạo PR `release → product`.

## 3. Quy ước commit message (Conventional Commits)

Dạng chung:

```
type(scope): mô tả ngắn gọn, viết thường, không kết thúc bằng dấu chấm
```

Các `type` cho phép:

| Type       | Ý nghĩa                                        |
| ---------- | ---------------------------------------------- |
| `feat`     | Thêm tính năng mới                             |
| `fix`      | Sửa lỗi                                        |
| `docs`     | Thay đổi tài liệu                              |
| `style`    | Format, không thay đổi logic                   |
| `refactor` | Refactor, không thêm tính năng / không sửa lỗi |
| `perf`     | Tối hiệu năng                                  |
| `test`     | Thêm / sửa test                                |
| `build`    | Thay đổi build system, dependencies            |
| `ci`       | Thay đổi CI/CD                                 |
| `chore`    | Việc lặt vặt khác                              |

`scope` gợi ý: `backend`, `frontend`, `docker`, `docs`, `repo`.

Ví dụ:

```
feat(backend): thêm endpoint health-check
fix(frontend): sửa lỗi CORS khi gọi API
docs(repo): bổ sung hướng dẫn chạy local
chore(docker): thêm docker-compose cho PostgreSQL
```

## 4. Quy tắc Pull Request

- Tiêu đề PR: `[PBL6-x] Mô tả ngắn`.
- Cần **ít nhất 1 approval** trước khi merge.
- Không push trực tiếp vào `dev`, `release`, `product`.
- Merge xong xoá nhánh ticket trên remote.
- **PR phải có pipeline xanh** trước khi merge: mọi thay đổi trong `services/<tên>/**`
  đều kích hoạt workflow riêng của service đó (build → unit test → coverage →
  đóng gói image → smoke test).

## 5. CI/CD — mỗi service một pipeline độc lập

Toàn bộ logic CI/CD nằm ở **một** workflow dùng chung
`.github/workflows/_reusable-springboot-service.yml`. Mỗi service chỉ có một file
~20 dòng gọi lại nó, kèm **path-based trigger**:

```
.github/workflows/
├── _reusable-springboot-service.yml   ← logic dùng chung, viết một lần
├── auth-service.yml                   ← chỉ chạy khi services/auth-service/** đổi
└── <service>.yml                      ← mỗi service một file
```

Hệ quả cần nhớ khi làm việc:

- Sửa code của service nào thì **chỉ pipeline của service đó chạy**. Sửa
  `services/pom.xml` hoặc `templates/Dockerfile.springboot` thì **mọi** pipeline chạy
  (đúng như thiết kế — đó là file dùng chung).
- Muốn đổi quy trình CI (thêm bước lint, đổi cách chạy test...) thì sửa
  **workflow dùng chung**, đừng sửa từng file service.
- Image được đẩy lên `ghcr.io` với tag `sha-<7 ký tự>`; deploy và rollback đều
  dựa vào tag này.

Chi tiết đầy đủ: [docs/CICD-TEMPLATE.md](docs/CICD-TEMPLATE.md).

## 6. Quy tắc bí mật và biến môi trường

Mỗi service có 3 file cấu hình trong `services/<tên>/env/`:

| File | Commit? | Nội dung |
| --- | :---: | --- |
| `.env.example` | ✅ | Danh mục **đầy đủ** biến, chỉ placeholder |
| `.env.dev` | ✅ | Giá trị dev (khớp `docker-compose.yml`), **không có bí mật thật** |
| `.env.prod.example` | ✅ | Khung prod, giá trị bí mật để trống |
| `.env.prod` | ❌ **KHÔNG BAO GIỜ** | Bí mật thật, chỉ tồn tại trên server (`chmod 600`) |

Quy tắc bắt buộc:

1. Bí mật thật (`AUTH_JWT_SECRET`, `MAIL_PASSWORD`, `PAYOS_CHECKSUM_KEY`...) chỉ
   nằm ở **GitHub Secrets** hoặc **`.env.prod` trên server**. Không bao giờ trong git.
2. Thêm biến mới: khai vào `.env.example` **trước**, rồi mới tới `.env.dev` và
   `.env.prod.example`.
3. **Tên biến trong `env/` phải khớp đúng tên `application.yml` đang đọc.** Đặt
   sai tên thì Spring không thấy biến; với placeholder không có giá trị mặc định
   (vd `secret: ${AUTH_JWT_SECRET}` của auth-service) container sẽ **chết ngay
   lúc khởi động**. Kiểm tra bằng `grep -o '\${[A-Z_]*' src/main/resources/application.yml`.
4. `application.yml` không hard-code host/port/mật khẩu — luôn dùng
   `${BIẾN:giá-trị-mặc-định}`, trừ bí mật bắt buộc thì cố ý **không** đặt mặc định.
5. Nếu lỡ commit bí mật: **đổi ngay giá trị đó**, đừng chỉ xoá khỏi file — nó vẫn
   nằm trong lịch sử git.

## 7. Checklist trước khi mở PR cho một service

- [ ] `cd services && ./mvnw -pl <service> -am verify` xanh tại máy
- [ ] `docker build -f templates/Dockerfile.springboot --build-arg SERVICE_NAME=<service> --build-arg SERVICE_PORT=<port> -t vmarket-<service>:local .` thành công
- [ ] Container chạy được và `GET /actuator/health` trả `{"status":"UP"}`
- [ ] Biến môi trường mới đã khai đủ trong cả 3 file `env/`
- [ ] `git status` không thấy file `.env` hay `.env.prod` nào
- [ ] Commit message đúng Conventional Commits (mục 3)
