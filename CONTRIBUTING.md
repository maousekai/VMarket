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
- **CI phải xanh trước khi merge.** Mỗi service có workflow riêng, chỉ chạy khi
  thư mục của service đó thay đổi — nên PR sửa `auth-service` không phải chờ CI
  của 10 service còn lại.

## 5. Khởi tạo service mới — luôn copy template

Mỗi service phải build / test / đóng gói **độc lập** với các service khác. Đừng
tự viết Dockerfile hay workflow mới: copy bộ template dùng chung ở
[`docs/templates/`](docs/templates/README.md) để cả 11 service giữ đúng một chuẩn.

```powershell
# Sau khi đã có skeleton Spring Boot trong services/<tên-service>:
scripts\new-service.cmd -Name order-service -Port 8086            # PostgreSQL
scripts\new-service.cmd -Name product-service -Port 8084 -Store mongo
scripts\new-service.cmd -Name cart-service -Port 8085 -Store redis
```

Script sinh ra 4 file cho service đó:

| File                                | Vai trò                                              |
| ----------------------------------- | ---------------------------------------------------- |
| `services/<svc>/Dockerfile`         | Build multi-stage (Maven → JRE slim)                 |
| `services/<svc>/.env.example`       | Biến môi trường **dev**                              |
| `services/<svc>/.env.prod.example`  | Biến môi trường **prod** (giá trị nhạy cảm để trống) |
| `.github/workflows/<svc>.yml`       | CI trigger theo thư mục của riêng service            |

Rồi làm nốt bằng tay: thêm `<module>` vào `services/pom.xml`, thêm block vào
`docker-compose.yml`, thêm `application-dev.yml` / `application-prod.yml`.

Chi tiết đầy đủ (cách hoạt động, cách bật push image lên registry, cách sửa
template): [`docs/templates/README.md`](docs/templates/README.md).

### Nguyên tắc bắt buộc

- **Không commit file `.env` thật.** Chỉ commit `*.example`. `.gitignore` đã chặn
  cả `.env.*` để không lỡ đẩy secret lên repo.
- **Secret prod không có giá trị mặc định.** `application-prod.yml` để trống
  host/mật khẩu → thiếu biến thì service fail ngay lúc khởi động, thay vì âm thầm
  chạy bằng mật khẩu dev.
- **Sửa logic build thì sửa template trước**, rồi mới đồng bộ cho các service.
