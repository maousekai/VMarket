# User Service (PBL6-13)

Hồ sơ cá nhân và sổ địa chỉ giao hàng. Cổng `8082`, CSDL PostgreSQL `vmarket_user`.

Service này **không** giữ email, username hay mật khẩu — đó là dữ liệu định danh do
auth-service sở hữu trong CSDL `vmarket_auth`. Ở đây chỉ có thông tin hiển thị và
liên lạc.

## Phạm vi

| Yêu cầu | Trạng thái |
| --- | --- |
| FR-USER-01 Quản lý hồ sơ | ✅ trong service này |
| FR-USER-02 Sổ địa chỉ (CRUD + đặt mặc định) | ✅ trong service này |
| FR-USER-04 Admin tìm kiếm / liệt kê người dùng | ✅ trong service này |
| FR-USER-03 Đổi mật khẩu | ❌ thuộc auth-service |
| FR-USER-04 Admin khoá / mở khoá tài khoản | ❌ thuộc auth-service |

Hai mục cuối thao tác trên `users.password_hash` và trạng thái khoá tài khoản —
đều nằm trong CSDL của auth-service. SRS mục 5 quy định database-per-service nên
user-service không được đọc chéo; đặt hai endpoint đó ở đây sẽ phá đúng ranh giới
mà mô tả ticket muốn tách ra.

## API

Mọi endpoint đều cần `Authorization: Bearer <access token>`, trừ `/api/users/health`.

| Method | Đường dẫn | Việc |
| --- | --- | --- |
| GET | `/api/users/me` | Xem hồ sơ của tôi (tự tạo hồ sơ rỗng ở lần gọi đầu) |
| PUT | `/api/users/me` | Cập nhật hồ sơ — **thay thế toàn bộ** |
| GET | `/api/users/me/addresses` | Danh sách địa chỉ (mặc định đứng đầu) |
| POST | `/api/users/me/addresses` | Thêm địa chỉ (địa chỉ đầu tiên tự thành mặc định) |
| GET | `/api/users/me/addresses/{id}` | Chi tiết một địa chỉ |
| PUT | `/api/users/me/addresses/{id}` | Sửa địa chỉ (không đổi cờ mặc định) |
| DELETE | `/api/users/me/addresses/{id}` | Xoá địa chỉ |
| PUT | `/api/users/me/addresses/{id}/default` | Đặt địa chỉ mặc định |
| GET | `/api/users?q=&page=&size=` | **\[ADMIN\]** Tìm kiếm / liệt kê hồ sơ |

Swagger UI: <http://localhost:8082/swagger-ui.html> · spec: `/v3/api-docs`

### Ba điều dễ hiểu nhầm

1. **`PUT /api/users/me` thay thế toàn bộ hồ sơ**, không vá từng trường. Trường
   không gửi sẽ bị **xoá**. Form hồ sơ phải gửi lại cả những trường không đổi.
2. **Không có endpoint nào nhận `userId`** từ path hay body. Danh tính luôn lấy từ
   claim `sub` của token đã verify — nếu để client truyền thì ai cũng sửa được hồ sơ
   người khác bằng cách đổi một tham số.
3. **Đặt mặc định là hành động riêng**, không phải một trường trong body sửa địa chỉ.
   Nó ảnh hưởng tới các địa chỉ *khác* của cùng người dùng.

## Xác thực

user-service **tự verify** access token (HS256) bằng `AUTH_JWT_SECRET` — cùng chuỗi
bí mật mà auth-service dùng để ký. HS256 là thuật toán đối xứng nên hai bên bắt buộc
dùng chung một giá trị; đặt lệch nhau sẽ ra lỗi khó đoán: đăng nhập thành công nhưng
mọi API hồ sơ đều trả 401.

Claim được dùng:

| Claim | Ý nghĩa |
| --- | --- |
| `sub` | `users.id` bên auth-service — chính là `userId` của mọi bản ghi ở đây |
| `roles` | Mảng chuỗi, ví dụ `["BUYER"]`. Được ánh xạ thành `ROLE_*` cho `hasRole(...)` |

> **Giả định cần đối chiếu:** auth-service chưa có endpoint đăng nhập (PBL6-43) nên
> hình dạng claim chưa được chốt. Tên claim `roles` khai ở một hằng số duy nhất trong
> `JwtAuthenticationFilter` — nếu PBL6-43 chọn tên khác thì sửa đúng chỗ đó.

## Chạy

```bash
# Bằng docker compose (khuyến nghị)
docker compose up -d --build user-service

# Hoặc chạy local — cần PostgreSQL của compose đang bật
cd services/user-service
..\mvnw.cmd spring-boot:run        # Windows
# ../mvnw spring-boot:run          # macOS/Linux
```

Kiểm tra nhanh:

```bash
curl http://localhost:8082/api/users/health          # không cần token
curl http://localhost:8082/api/users/me              # 401 khi chưa có token
```

## Biến môi trường

| Biến | Mặc định (dev) | Ý nghĩa |
| --- | --- | --- |
| `SERVER_PORT` | `8082` | Cổng service |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5433` / `vmarket_user` | PostgreSQL (trong container: `postgres` / `5432`) |
| `DB_USERNAME` / `DB_PASSWORD` | `vmarket` / `vmarket` | Tài khoản CSDL |
| `AUTH_JWT_SECRET` | *(giả, ở dev yml)* | **Bắt buộc ở prod** — dùng chung với auth-service |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | Origin được phép gọi trực tiếp |

`application.yml` nền khai `${AUTH_JWT_SECRET}` **không có giá trị mặc định** — thiếu
biến này ở prod là service chết ngay lúc khởi động, thay vì âm thầm chạy bằng khoá
đoán được.

## Schema

Flyway quản lý schema (`db/migration/V1__init_user_schema.sql`), Hibernate chỉ
`validate`. Hai bảng: `user_profiles` (1-1 với tài khoản) và `addresses`.

`user_id` **không có khoá ngoại** vì bảng `users` nằm ở CSDL khác. Ràng buộc là ngữ
nghĩa: giá trị luôn đến từ claim `sub` của token đã verify.

Bất biến *tối đa một địa chỉ mặc định mỗi người dùng* được cưỡng chế bằng partial
unique index:

```sql
CREATE UNIQUE INDEX uq_addresses_one_default_per_user
    ON addresses (user_id) WHERE is_default;
```

> Test chạy trên H2 (`ddl-auto: create-drop`) nên **không** có index này — H2 không
> hỗ trợ partial index. Bất biến được test ở tầng service; lớp bảo vệ ở CSDL chỉ tồn
> tại trên PostgreSQL thật.

## Test

```bash
cd services
.\mvnw.cmd -pl user-service test
```

35 test, gồm: xác thực token (thiếu / hết hạn / sai chữ ký), ngữ nghĩa thay thế của
`PUT /me`, các bất biến của địa chỉ mặc định, và chống IDOR (người dùng A không
chạm được địa chỉ của B dù biết id).
