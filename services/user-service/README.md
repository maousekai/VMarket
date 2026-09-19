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
| `AUTH_SERVICE_URL` | `http://localhost:8081` | Base URL API nội bộ auth-service (trong compose: `http://auth-service:8081`) |
| `INTERNAL_API_KEY` | *(giả, ở dev yml)* | **Bắt buộc ở prod** — khoá `/internal/**`, dùng chung với auth-service |
| `AUTH_SERVICE_CONNECT_TIMEOUT` / `AUTH_SERVICE_READ_TIMEOUT` | `2s` / `5s` | Timeout gọi auth-service |
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
| `AUTH_SERVICE_UNAVAILABLE` | 503 | **Chưa gọi tới được** auth-service — thao tác chắc chắn chưa chạy, thử lại được |
| `PASSWORD_CHANGE_UNKNOWN` | 504 | **Đã gọi tới nhưng không rõ kết quả** — xem bên dưới, ĐỪNG thử lại |

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

### `503` và `504` là hai chuyện khác nhau — đừng gộp

`503 AUTH_SERVICE_UNAVAILABLE` nghĩa là **chưa byte nào tới auth-service** (connection
refused, sai host, hết thời gian *kết nối*). Thao tác chắc chắn chưa chạy → bảo người
dùng thử lại là đúng.

`504 PASSWORD_CHANGE_UNKNOWN` nghĩa là **request đã tới nơi nhưng không biết kết quả**
(hết thời gian *đọc*, đứt kết nối giữa chừng). auth-service có thể đã chạy xong: mật
khẩu đã đổi và mọi phiên đã bị thu hồi.

Vì sao phải tách: nếu gộp `504` vào `503` *"vui lòng thử lại sau"* thì người dùng bấm
thử lại với mật khẩu **cũ** → `INVALID_CURRENT_PASSWORD` → bộ đếm khoá tăng. Vài lần là
khoá tài khoản 15 phút **dù họ không làm gì sai**.

> ⚠️ **Lưu ý cho Frontend.** Gặp `504 PASSWORD_CHANGE_UNKNOWN` thì **không** gửi lại
> request và **không** hiện nút "Thử lại". Hướng người dùng sang đăng nhập bằng mật
> khẩu **mới**: vào được nghĩa là đã đổi thành công; không vào được thì mật khẩu cũ vẫn
> còn hiệu lực và họ đổi lại từ đầu. Đây là cách kiểm tra duy nhất không có tác dụng phụ.

Phân loại dựa trên **kiểu exception**, không dò chuỗi thông báo lỗi — `AuthServiceClient`
dùng `JdkClientHttpRequestFactory` vì nó ném `HttpConnectTimeoutException` cho timeout
kết nối và `HttpTimeoutException` cho timeout đọc (`SimpleClientHttpRequestFactory` ném
`SocketTimeoutException` cho cả hai, chỉ khác chuỗi thông báo). Lỗi lạ không nhận ra
mặc định rơi vào nhóm "không rõ": đoán nhầm theo hướng *"chắc chắn hỏng"* mới là cái
gây hại.

### Giới hạn đã biết

| Giới hạn | Hệ quả | Hướng xử lý |
| --- | --- | --- |
| **Access token cũ sống thêm ≤ 15 phút** sau khi đổi mật khẩu | Chỉ refresh token bị thu hồi. Access token là JWT tự chứa nên không vô hiệu hoá được mà không tra CSDL ở mỗi request. Lý do đổi mật khẩu thường là nghi bị lộ → kẻ đang giữ access token vẫn gọi API bình thường tới khi nó hết hạn. | Cần `token_version` trong claim hoặc denylist theo `sub + iat`. Đụng tới mọi service đang verify token → **ticket riêng**, không làm trong PBL6-13. |
| **Người giữ access token có thể khoá tài khoản chủ sở hữu** | Gửi 5 lần sai mật khẩu hiện tại là khoá 15 phút, và `registerFailedAttempt` thu hồi mọi refresh token → chủ tài khoản bị đăng xuất và chưa đăng nhập lại được. | **Chủ đích**, không phải sót. Đổi lại là chặn được việc dò mật khẩu — thiệt hại đó không hồi phục được, còn khoá 15 phút thì có. Nhánh khoá xuất phát từ endpoint này được log WARN riêng ở auth-service để phát hiện khi bị lạm dụng. |

**Lỗi 401 từ auth-service không được chuyển tiếp** — đó là dấu hiệu `INTERNAL_API_KEY`
hai service lệch nhau, không phải token người dùng sai; trả 401 cho client sẽ đăng xuất
người dùng vì một sự cố cấu hình họ không liên quan. `AuthServiceClient` dịch thành 502.

`AUTH_SERVICE_URL` / `INTERNAL_API_KEY` phải trùng auth-service; `scripts\check-env.cmd`
đối chiếu tự động. user-service **không** `depends_on` auth-service trong compose: hồ sơ
và sổ địa chỉ vẫn chạy khi auth-service chết, chỉ các endpoint cần auth-service (đổi mật
khẩu, Admin quản lý người dùng) trả 503.

**Timeout của endpoint Admin ra `504 AUTH_SERVICE_TIMEOUT`, không phải 503.** Cùng lý do
với đổi mật khẩu: 503 nghĩa là "chắc chắn chưa chạy", còn timeout đọc là "không rõ đã
chạy chưa". Khác một điểm quan trọng — khoá / mở khoá **lặp lại được** (chạy hai lần cho
ra đúng một trạng thái), nên ở đây thử lại là an toàn; chỉ cần tải lại danh sách để biết
trạng thái thật. Đổi mật khẩu thì không: thử lại bằng mật khẩu cũ làm tăng bộ đếm khoá.

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
xạ 401→502 / 5xx→502, và quan trọng nhất: timeout **kết nối** → 503 còn timeout **đọc**
/ đứt giữa chừng → 504 `PASSWORD_CHANGE_UNKNOWN` với thông báo không có chữ "thử lại").
Admin (`AdminUserApiTest`): 401 khi không có token, 403 với BUYER/SELLER mà không gọi
auth-service, tìm theo họ tên/SĐT, ghép hồ sơ, lọc trạng thái + phân trang, `userId` sai
định dạng → 400, `adminId` lấy từ token khi khoá, chuyển tiếp `USER_NOT_FOUND` /
`CANNOT_LOCK_SELF`. `Idempotency-Key`: gửi lại không tạo bản trùng, phát lại đúng
response cũ, cùng key khác nội dung bị chặn, request lỗi không khoá key, key treo quá
hạn được nhả, hai người dùng trùng key không che nhau.

`DataIntegrityMappingTest` kiểm phần dịch lỗi ràng buộc CSDL bằng cách dựng thẳng
exception như driver ném ra — partial unique index chỉ có trên PostgreSQL nên test
API trên H2 không chạm tới nhánh đó được.

Phần lớn test chạy trên H2 với schema do Hibernate sinh (`ddl-auto: create-drop`,
Flyway tắt) cho nhanh. Riêng `FlywayMigrationTest` chạy **Flyway thật** trên H2
`MODE=PostgreSQL` với `ddl-auto: validate`, trên một DB in-memory riêng: nếu script
migration lỗi hoặc entity lệch cột/kiểu so với migration thì test này đỏ — chỗ mà
các test còn lại không kiểm được. Cùng khuôn với `FlywayMigrationTest` của
auth-service.
