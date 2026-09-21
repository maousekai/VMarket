# User Service (PBL6-13)

Hồ sơ cá nhân (FR-USER-01) và sổ địa chỉ giao hàng (FR-USER-02) của người dùng.
Cổng `8082`, CSDL PostgreSQL `vmarket_user`.

## Phạm vi và ranh giới dữ liệu

| Yêu cầu | Endpoint ở | Dữ liệu nằm ở |
| --- | --- | --- |
| FR-USER-01 Quản lý hồ sơ | user-service | `vmarket_user.user_profiles` |
| FR-USER-02 Sổ địa chỉ (CRUD + đặt mặc định) | user-service | `vmarket_user.addresses` |

Hồ sơ **không** chứa email, username hay mật khẩu: đó là dữ liệu định danh do
auth-service sở hữu. Ở đây chỉ có thông tin hiển thị / liên lạc.

## API

Mọi endpoint đều cần `Authorization: Bearer <access token>`, trừ `/api/users/health`.
Mọi endpoint **ghi dữ liệu** (POST/PUT/PATCH/DELETE) nhận thêm header tuỳ chọn
`Idempotency-Key` — xem [Gửi lại không tạo dữ liệu trùng](#gửi-lại-không-tạo-dữ-liệu-trùng).

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
2. **Endpoint không nhận `userId`** từ path hay body. Danh tính luôn lấy từ claim `sub`
   của token đã verify — sửa tham số không đổi được hồ sơ người khác.

### Gửi lại không tạo dữ liệu trùng

Client hết thời gian chờ rồi gửi lại request là chuyện bình thường — mạng yếu, bấm
hai lần, hoặc thư viện HTTP tự retry. Với `POST /api/users/me/addresses`, lần gửi
lại tạo thêm một địa chỉ y hệt: **không có lỗi nào báo ra**, người dùng tự phát hiện
và tự dọn.

Cách tránh: sinh một chuỗi ngẫu nhiên cho mỗi **thao tác người dùng** (không phải
mỗi lần gửi đi) và gắn vào header `Idempotency-Key`.

```bash
KEY=$(uuidgen)
curl -X POST http://localhost:8082/api/users/me/addresses      -H "Authorization: Bearer $TOKEN"      -H "Idempotency-Key: $KEY"      -H 'Content-Type: application/json'      -d '{"recipientName":"An", ... }'
# Gửi lại đúng lệnh trên -> vẫn 201 với ĐÚNG địa chỉ cũ (kèm header
# Idempotency-Replayed: true), không tạo thêm bản ghi nào.
```

| Tình huống | Kết quả |
| --- | --- |
| Không gửi header | Chạy như bình thường, **không có bảo vệ** (giữ nguyên hành vi cũ) |
| Gửi lại cùng key, cùng nội dung, request trước đã xong | Phát lại nguyên response cũ + header `Idempotency-Replayed: true` |
| Gửi lại cùng key, cùng nội dung, request trước đang chạy | `409 IDEMPOTENCY_IN_PROGRESS` — thử lại sau giây lát |
| Cùng key nhưng **khác nội dung** | `422 IDEMPOTENCY_KEY_REUSED` — client đang sinh key sai |
| Key rỗng hoặc dài quá 200 ký tự | `400 IDEMPOTENCY_KEY_INVALID` |

Hai điều dễ hiểu nhầm:

1. **Request lỗi không bị khoá key.** 4xx/5xx thì bản ghi bị xoá, client sửa dữ liệu
   rồi gửi lại **cùng key** vẫn chạy được. Giữ lại một lỗi 400 sẽ biến nó thành vĩnh
   viễn.
2. **Key có phạm vi theo từng người dùng.** Hai người trùng key không che nhau.

Chốt chặn thật sự là ràng buộc `uq_idempotency_user_key` ở CSDL, không phải một câu
"kiểm tra xem đã tồn tại chưa" ở ứng dụng — hai request song song đều đọc thấy "chưa
có" rồi cùng chạy là đúng cái tình huống cần chặn.

| Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `IDEMPOTENCY_ENABLED` | `true` | Tắt hẳn cơ chế (header bị bỏ qua) |
| `IDEMPOTENCY_CLAIM_TIMEOUT` | `5m` | Quá lâu mà request chưa xong → coi như tiến trình xử lý đã chết, nhả key |
| `IDEMPOTENCY_RETENTION` | `1d` | Giữ kết quả bao lâu; `IdempotencyService.purgeExpired` dọn theo giờ |

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
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | Origin được phép gọi trực tiếp |

`AUTH_JWT_SECRET` **không có giá trị mặc định** ở `application.yml` nền — thiếu ở prod
là service chết ngay lúc khởi động. `scripts\check-env.cmd` đối chiếu biến này giữa
`.env` gốc và file env của từng service.

## Schema

Flyway quản lý schema, Hibernate chỉ `validate`. Ba bảng: `user_profiles` (1-1 với
tài khoản, `user_id UNIQUE`), `addresses` và `idempotency_keys`.

| Migration | Việc |
| --- | --- |
| `db/migration/V1__init_user_schema.sql` | `user_profiles` (FR-USER-01) |
| `db/migration/V2__add_addresses.sql` | `addresses` (FR-USER-02) |
| `db/vendor/postgresql/V3__addresses_one_default_per_user.sql` | Partial unique index — **chỉ PostgreSQL** |
| `db/migration/V4__add_idempotency_keys.sql` | `idempotency_keys` (chống xử lý trùng) |

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
chối và `GlobalExceptionHandler` dịch thành `409 DEFAULT_ADDRESS_CONFLICT`.

Cờ mặc định bị ghi ở **ba** chỗ, không phải một: thêm địa chỉ đầu tiên
(`AddressService.create`), đổi mặc định (`setDefault`), và chỉ định người kế nhiệm
sau khi xoá địa chỉ mặc định (`promoteNewDefault`). Vì vậy phần dịch lỗi nằm ở
`GlobalExceptionHandler` chứ không try/catch tại từng chỗ — chỉ cần quên một chỗ là
client nhận `500` cho một tình huống hoàn toàn bình thường. Vi phạm `UNIQUE` khác ra
`409 DATA_CONFLICT`; vi phạm **không phải** `UNIQUE` (ví dụ `NOT NULL`) vẫn ra `500`
vì đó là lỗi của chính service, trả 409 sẽ xui client thử lại mãi.

> Partial index là cú pháp riêng của PostgreSQL (H2 báo lỗi ngay tại `WHERE`), nên nó
> nằm ở `db/vendor/postgresql` và `spring.flyway.locations` dùng placeholder
> `{vendor}`: chạy trên H2 thì `db/vendor/h2` không tồn tại và Flyway bỏ qua.
> Thư mục vendor phải nằm **ngoài** `db/migration` — Flyway quét đệ quy nên thư mục
> con vẫn bị nạp ở mọi vendor. Trên H2 ràng buộc này không có, nên `AddressApiTest`
> bảo vệ bất biến ở tầng service; kiểm tra ràng buộc CSDL thật để dành PBL6-47
> (Testcontainers).

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
(IDOR). `Idempotency-Key`: gửi lại không tạo bản trùng, phát lại đúng response cũ,
cùng key khác nội dung bị chặn, request lỗi không khoá key, key treo quá hạn được
nhả, hai người dùng trùng key không che nhau.

`DataIntegrityMappingTest` kiểm phần dịch lỗi ràng buộc CSDL bằng cách dựng thẳng
exception như driver ném ra — partial unique index chỉ có trên PostgreSQL nên test
API trên H2 không chạm tới nhánh đó được.

Phần lớn test chạy trên H2 với schema do Hibernate sinh (`ddl-auto: create-drop`,
Flyway tắt) cho nhanh. Riêng `FlywayMigrationTest` chạy **Flyway thật** trên H2
`MODE=PostgreSQL` với `ddl-auto: validate`, trên một DB in-memory riêng: nếu script
migration lỗi hoặc entity lệch cột/kiểu so với migration thì test này đỏ — chỗ mà
các test còn lại không kiểm được. Cùng khuôn với `FlywayMigrationTest` của
auth-service.
