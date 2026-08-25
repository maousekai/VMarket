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
