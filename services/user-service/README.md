# User Service (PBL6-13)

Hồ sơ cá nhân (FR-USER-01), sổ địa chỉ giao hàng (FR-USER-02), đổi mật khẩu
(FR-USER-03) của người dùng và Admin quản lý người dùng (FR-USER-04).
Cổng `8082`, CSDL PostgreSQL `vmarket_user`.

## Phạm vi và ranh giới dữ liệu

| Yêu cầu | Endpoint ở | Dữ liệu nằm ở |
| --- | --- | --- |
| FR-USER-01 Quản lý hồ sơ | user-service | `vmarket_user.user_profiles` |
| FR-USER-02 Sổ địa chỉ (CRUD + đặt mặc định) | user-service | `vmarket_user.addresses` |
| FR-USER-03 Đổi mật khẩu | user-service | `vmarket_auth.users.password_hash` → gọi auth-service |
| FR-USER-04 Admin tìm kiếm / khoá / mở khoá | user-service | email, username, vai trò, trạng thái khoá ở `vmarket_auth` → gọi auth-service; họ tên, SĐT ở `vmarket_user` |

Hồ sơ **không** chứa email, username hay mật khẩu: đó là dữ liệu định danh do
auth-service sở hữu. Ở đây chỉ có thông tin hiển thị / liên lạc.

SRS đặt đổi mật khẩu ở User Service, nhưng mật khẩu nằm trong CSDL `vmarket_auth`.
user-service **không đọc chéo CSDL** — nó gọi REST nội bộ của auth-service (SRS 5.4),
xem phần *Đổi mật khẩu* bên dưới. Không giữ bản sao mật khẩu nào ở đây: hai nguồn sự
thật cho một mật khẩu nghĩa là đổi xong vẫn đăng nhập được bằng mật khẩu cũ. Trạng
thái khoá tài khoản của FR-USER-04 cũng vậy: nằm ở auth-service, user-service chỉ gọi.

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
| DELETE | `/api/users/me/addresses/{id}` | Xoá địa chỉ (xoá cái mặc định → cái mới nhất còn lại lên thay) |
| PUT | `/api/users/me/addresses/{id}/default` | Đặt địa chỉ mặc định |
| PUT | `/api/users/me/password` | Đổi mật khẩu (FR-USER-03) |
| GET | `/api/users?q=&status=&page=&size=` | **[ADMIN]** Tìm kiếm / liệt kê người dùng (FR-USER-04) |
| GET | `/api/users/{userId}` | **[ADMIN]** Chi tiết một người dùng |
| PUT | `/api/users/{userId}/lock` | **[ADMIN]** Khoá tài khoản, body `{ "reason" }` |
| PUT | `/api/users/{userId}/unlock` | **[ADMIN]** Mở khoá tài khoản |

Body `PUT /api/users/me`: `{ "fullName", "avatarUrl", "phone", "dateOfBirth", "gender" }`.

| Trường | Ràng buộc |
| --- | --- |
| `fullName` | ≤ 100 ký tự |
| `avatarUrl` | URL `http`/`https`, ≤ 500 ký tự |
| `phone` | Số di động Việt Nam: `0xxxxxxxxx` hoặc `+84xxxxxxxxx` |
| `dateOfBirth` | `yyyy-MM-dd`, phải ở quá khứ |
| `gender` | `MALE` \| `FEMALE` \| `OTHER` \| `UNDISCLOSED` |

Swagger UI: <http://localhost:8082/swagger-ui.html> · spec: `/v3/api-docs`

Body lỗi mọi endpoint: `{ "error": { "code": "...", "message": "...", "details": [...] } }`.

### Hai điều dễ hiểu nhầm

1. **`PUT /api/users/me` thay thế toàn bộ hồ sơ**, không vá từng trường. Trường
   không gửi sẽ bị **xoá**. Form hồ sơ phải gửi lại cả những trường không đổi.

   > ⚠️ **Lưu ý cho Frontend.** Đây là cái bẫy dễ gặp nhất của endpoint này. Ví dụ
   > cụ thể: người dùng chỉ đổi ảnh đại diện, FE gửi `{ "avatarUrl": "..." }` →
   > họ tên, số điện thoại, ngày sinh, giới tính **bị xoá trắng**, và API vẫn trả
   > `200 OK` nên không có lỗi nào báo ra. Cách đúng: lấy hồ sơ hiện tại bằng
   > `GET /api/users/me`, sửa đúng trường cần đổi trên object đó, rồi `PUT` lại
   > **toàn bộ** object.
   >
   > Một endpoint `PATCH /api/users/me` (vá từng trường) sẽ được cân nhắc ở ticket
   > sau; tới lúc đó `PUT` vẫn giữ đúng ngữ nghĩa thay thế của REST.
2. **Endpoint của người dùng không nhận `userId`** từ path hay body. Danh tính luôn lấy
   từ claim `sub` của token đã verify — sửa tham số không đổi được hồ sơ người khác.
   Chỉ endpoint **[ADMIN]** mới có `{userId}` trên path.

## Xác thực

user-service **tự verify** access token (HS256) bằng `AUTH_JWT_SECRET` — cùng chuỗi
bí mật mà auth-service dùng để ký — dù gateway đã verify (NFR-SEC-03: kiểm tra ở cả
gateway và service; cổng 8082 publish ra host nên request có thể không qua gateway).
Quy tắc giống gateway: đúng chữ ký, `iss = auth-service`, chưa hết hạn (lệch đồng hồ ≤ 30s).

| Claim | Ý nghĩa |
| --- | --- |
| `sub` | `users.id` bên auth-service — chính là `userId` của hồ sơ |
| `roles` | Mảng chuỗi, ví dụ `["BUYER"]`. Được ánh xạ thành `ROLE_*` |

## Chạy

```bash
# Bằng docker compose (khuyến nghị)
docker compose up -d --build auth-service user-service

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
| `AUTH_SERVICE_URL` | `http://localhost:8081` | Base URL API nội bộ auth-service (trong compose: `http://auth-service:8081`) |
| `INTERNAL_API_KEY` | *(giả, ở dev yml)* | **Bắt buộc ở prod** — khoá `/internal/**`, dùng chung với auth-service |
| `AUTH_SERVICE_CONNECT_TIMEOUT` / `AUTH_SERVICE_READ_TIMEOUT` | `2s` / `5s` | Timeout gọi auth-service |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | Origin được phép gọi trực tiếp |

`AUTH_JWT_SECRET` **không có giá trị mặc định** ở `application.yml` nền — thiếu ở prod
là service chết ngay lúc khởi động. `scripts\check-env.cmd` đối chiếu biến này giữa
`.env` gốc và file env của từng service.

## Schema

Flyway quản lý schema, Hibernate chỉ `validate`. Hai bảng: `user_profiles` (1-1 với
tài khoản, `user_id UNIQUE`) và `addresses`.

| Migration | Việc |
| --- | --- |
| `db/migration/V1__init_user_schema.sql` | `user_profiles` (FR-USER-01) |
| `db/migration/V2__add_addresses.sql` | `addresses` (FR-USER-02) |
| `db/vendor/postgresql/V3__addresses_one_default_per_user.sql` | Partial unique index — **chỉ PostgreSQL** |

Migration viết bằng kiểu **chuẩn SQL** (`TIMESTAMP WITH TIME ZONE`, không dùng bí
danh `TIMESTAMPTZ` của Postgres) để `FlywayMigrationTest` chạy được đúng script này
trên H2 `MODE=PostgreSQL` — xem phần Test.

`user_id` **không có khoá ngoại** vì bảng `users` nằm ở CSDL khác. Ràng buộc là ngữ
nghĩa: giá trị luôn đến từ claim `sub` của token đã verify.

### Tối đa một địa chỉ mặc định mỗi người dùng

Bất biến này được cưỡng chế ở **cả hai tầng**. Ở CSDL:

```sql
CREATE UNIQUE INDEX uq_addresses_one_default_per_user
    ON addresses (user_id) WHERE is_default;
```

Cần lớp ở CSDL vì hai request "đặt mặc định" chạy song song đều đọc thấy địa chỉ cũ
rồi cùng ghi `is_default = true` — không có index thì người dùng có hai địa chỉ mặc
định và trang thanh toán không biết chọn cái nào. Có index thì request tới sau bị từ
chối và `AddressService` dịch thành `409 DEFAULT_ADDRESS_CONFLICT`.

> Partial index là cú pháp riêng của PostgreSQL (H2 báo lỗi ngay tại `WHERE`), nên nó
> nằm ở `db/vendor/postgresql` và `spring.flyway.locations` dùng placeholder
> `{vendor}`: chạy trên H2 thì `db/vendor/h2` không tồn tại và Flyway bỏ qua.
> Thư mục vendor phải nằm **ngoài** `db/migration` — Flyway quét đệ quy nên thư mục
> con vẫn bị nạp ở mọi vendor. Trên H2 ràng buộc này không có, nên `AddressApiTest`
> bảo vệ bất biến ở tầng service; kiểm tra ràng buộc CSDL thật để dành PBL6-47
> (Testcontainers).

## Đổi mật khẩu (FR-USER-03)

`PUT /api/users/me/password` với body `{ "currentPassword", "newPassword" }`.
Mật khẩu mới: 8–32 ký tự, ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt (đúng chính
sách của đăng ký). Không có trường "nhập lại mật khẩu mới" — so khớp hai ô là việc
của form phía client.

Endpoint **không nhận `userId`**; danh tính lấy từ claim `sub` của token đã verify.
user-service chỉ xác định *ai* đang đổi rồi gọi
`PUT /internal/users/{id}/password` của auth-service qua `AuthServiceClient`.

| Mã lỗi | HTTP | Khi nào |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Thiếu trường, mật khẩu mới không đủ mạnh |
| `INVALID_CURRENT_PASSWORD` | 400 | Sai mật khẩu hiện tại |
| `PASSWORD_UNCHANGED` | 400 | Mật khẩu mới trùng mật khẩu cũ |
| `PASSWORD_NOT_SET` | 400 | Tài khoản tạo qua OTP chưa có mật khẩu — dùng Quên mật khẩu |
| `ACCOUNT_LOCKED` | 423 | Khoá tạm 15 phút do nhập sai 5 lần |
| `ACCOUNT_SUSPENDED` | 423 | Tài khoản bị Admin khoá (FR-USER-04) |
| `AUTH_SERVICE_ERROR` | 502 | auth-service lỗi, hoặc `INTERNAL_API_KEY` hai bên lệch |
| `AUTH_SERVICE_UNAVAILABLE` | 503 | Không kết nối được / quá thời gian |

### Ba điều cần biết

1. **Sai mật khẩu hiện tại trả 400, không phải 401.** 401 khiến frontend tưởng access
   token hết hạn và đăng xuất người dùng, trong khi họ chỉ gõ nhầm mật khẩu cũ.
2. **Sai mật khẩu hiện tại tính chung bộ đếm với đăng nhập sai** (5 lần → khoá 15
   phút). Nếu endpoint này có bộ đếm riêng thì ai cầm được access token (còn sống ≤ 15
   phút) có thể dò mật khẩu không giới hạn qua đây.
3. **Đổi thành công thu hồi mọi refresh token** → client phải đăng nhập lại bằng mật
   khẩu mới. Đổi mật khẩu thường là vì nghi bị lộ, nên các phiên cũ không được sống sót.

> ⚠️ **Lưu ý cho Frontend.** Sau `200 OK`, refresh token hiện tại đã bị thu hồi:
> access token còn sống tới khi hết hạn, nhưng lần refresh kế tiếp sẽ 401. Nên điều
> hướng về trang đăng nhập ngay sau khi đổi thành công thay vì đợi refresh thất bại.

**Lỗi 401 từ auth-service không được chuyển tiếp** — đó là dấu hiệu `INTERNAL_API_KEY`
hai service lệch nhau, không phải token người dùng sai; trả 401 cho client sẽ đăng xuất
người dùng vì một sự cố cấu hình họ không liên quan. `AuthServiceClient` dịch thành 502.

`AUTH_SERVICE_URL` / `INTERNAL_API_KEY` phải trùng auth-service; `scripts\check-env.cmd`
đối chiếu tự động. user-service **không** `depends_on` auth-service trong compose: hồ sơ
và sổ địa chỉ vẫn chạy khi auth-service chết, chỉ các endpoint cần auth-service (đổi mật
khẩu, Admin quản lý người dùng) trả 503.

## Admin quản lý người dùng (FR-USER-04)

Mọi endpoint `/api/users` (trừ `/me/**` và `/health`) yêu cầu vai trò **ADMIN** trong claim
`roles` (`@PreAuthorize`); BUYER / SELLER nhận `403 FORBIDDEN` và không có lời gọi nào sang
auth-service.

- **Danh sách lấy từ auth-service** — nơi có mọi tài khoản — rồi ghép hồ sơ vào. Người
  chưa từng mở trang hồ sơ vẫn hiện (khi đó `fullName`/`phone`/`avatarUrl` = `null`).
- **`q` khớp một phần email, username, họ tên hoặc SĐT** (không phân biệt hoa thường):
  user-service tìm userId khớp họ tên/SĐT (tối đa 500, mới nhất trước) rồi gửi kèm sang
  auth-service để lọc "email/username khớp HOẶC id thuộc danh sách". Phân trang diễn ra
  một chỗ nên `totalElements` luôn đúng.
- `status`: `ACTIVE` | `LOCKED` (bị Admin khoá). Khoá tạm do đăng nhập sai không đổi
  `status` — xem trường `loginLockedUntil`. `size` tối đa 100.
- **Khoá:** lý do bắt buộc; `lockedBy` lấy từ token của Admin (không nhận từ body). Người
  bị khoá không đăng nhập / refresh / xác thực OTP / đổi mật khẩu được (423
  `ACCOUNT_SUSPENDED`), mọi refresh token bị thu hồi ngay. Access token còn hạn (≤ 15
  phút) vẫn dùng được tới khi hết hạn — JWT không thu hồi được. Khoá lần hai giữ nguyên
  lần khoá đầu. Không tự khoá mình được (`400 CANNOT_LOCK_SELF`).
- **Khoá của Admin khác khoá tạm do đăng nhập sai:** cột riêng, không tự hết hạn, không bị
  gỡ bởi đăng nhập đúng hay "Quên mật khẩu". **Mở khoá** gỡ cả hai.
- `userId` trên path phải là ULID 26 ký tự, sai định dạng → 400 ngay, không gọi sang
  auth-service. `GET /api/users/me` vẫn là hồ sơ của chính mình, không bị hiểu là `userId`.

Tạo tài khoản ADMIN để thử (chưa có API cấp vai trò):

```sql
-- docker compose exec postgres psql -U vmarket -d vmarket_auth
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r WHERE u.email = '<email>' AND r.name = 'ADMIN';
```

Đăng nhập lại sau khi cấp để access token mang `roles` mới.

## Test

```bash
cd services
.\mvnw.cmd -pl user-service test
```

Test phủ: xác thực token (thiếu / hết hạn / sai chữ ký / sai issuer), tạo hồ sơ lười
không trùng, ngữ nghĩa thay thế của `PUT /me`, kiểm tra đầu vào, giới tính
`UNDISCLOSED` không tràn cột, hồ sơ hai người dùng tách biệt. Sổ địa chỉ: CRUD, địa
chỉ đầu tiên tự thành mặc định, đổi mặc định gỡ cờ cái cũ, xoá mặc định thì cái mới
nhất còn lại lên thay, và người dùng A không chạm được địa chỉ của B dù biết id
(IDOR). Đổi mật khẩu: userId lấy từ token (gửi userId trong body bị bỏ qua), mật
khẩu mới yếu bị chặn TRƯỚC khi gọi auth-service, và mã lỗi nghiệp vụ của
auth-service được chuyển tiếp nguyên trạng. `AuthServiceClientTest` kiểm hợp đồng
HTTP với auth-service bằng `MockRestServiceServer` (path, header khoá nội bộ, ánh
xạ 401→502 / 5xx→502 / timeout→503). Admin (`AdminUserApiTest`): 401 khi không có
token, 403 với BUYER/SELLER mà không gọi auth-service, tìm theo họ tên/SĐT, ghép hồ sơ,
lọc trạng thái + phân trang, `userId` sai định dạng → 400, `adminId` lấy từ token khi
khoá, chuyển tiếp `USER_NOT_FOUND` / `CANNOT_LOCK_SELF`.

Phần lớn test chạy trên H2 với schema do Hibernate sinh (`ddl-auto: create-drop`,
Flyway tắt) cho nhanh. Riêng `FlywayMigrationTest` chạy **Flyway thật** trên H2
`MODE=PostgreSQL` với `ddl-auto: validate`, trên một DB in-memory riêng: nếu script
migration lỗi hoặc entity lệch cột/kiểu so với migration thì test này đỏ — chỗ mà
các test còn lại không kiểm được. Cùng khuôn với `FlywayMigrationTest` của
auth-service.
