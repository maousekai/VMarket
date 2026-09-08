# Worklog — PBL6-3: Dockerfile cho ứng dụng React (FE)

- **Branch:** `pbl6-3`
- **Ngày làm:** 08/09/2026
- **Phạm vi:** đóng gói FE thành container (multi-stage Node → Nginx), cấu hình
  Nginx (SPA routing + reverse proxy tới API Gateway), gắn service FE vào
  `docker-compose.yml` chung (PBL6-9) và kiểm chứng bản build production.

> **Lưu ý cấu trúc:** khác backend (mỗi microservice một Dockerfile riêng), FE là
> **một codebase dùng chung** cho Web end-user / Web Admin / Mobile nên chỉ có
> **duy nhất một** `frontend/Dockerfile` — đây là chủ ý thiết kế, không phải lỗi
> cấu trúc giống phần BE. Ghi chú này đã được viết thẳng vào đầu Dockerfile.

---

## 0. Điểm xuất phát: phần nào đã có, phần nào còn thiếu

Ticket PBL6-10 (FE gọi được Gateway qua compose) **phụ thuộc** ticket này nên đã
tạo trước một phần deliverable (worklog PBL6-10 mục 2 ghi rõ _"PBL6-3 chưa xong"_).
So sánh với yêu cầu của PBL6-3:

| Hạng mục ticket yêu cầu                                    | Trạng thái trước         | Xử lý ở ticket này                                             |
| ---------------------------------------------------------- | ------------------------ | -------------------------------------------------------------- |
| Dockerfile multi-stage (Node build → Nginx serve)          | ✅ đã có (commit `3a820eb`) | Giữ khung, sửa mặc định `VITE_API_BASE_URL` + thêm template/ENV |
| Nginx xử lý route SPA                                      | ✅ đã có                  | Giữ, tách `server` block ra template                            |
| **Nginx reverse proxy tới API Gateway**                    | ❌ **chưa có**            | ✅ Làm mới — deliverable chính của ticket                       |
| Thêm service FE vào `docker-compose.yml`                   | ✅ đã có                  | Cập nhật theo hướng proxy (bỏ bake URL, thêm `API_GATEWAY_URL`) |
| Kiểm chứng build production trong container, gọi API qua Gateway | ✅ đã có (đường cũ)  | Kiểm chứng lại theo đường mới, mục 4                            |

**Vấn đề của đường cũ:** bundle được bake `VITE_API_BASE_URL=http://localhost:8080`
→ browser ở origin `:5173` gọi thẳng Gateway ở origin `:8080` ⇒ **cross-origin**,
sống hoàn toàn nhờ CORS. Đúng chỗ này đã đẻ ra 2 bug ghi trong worklog PBL6-10
mục 5 (preflight bị chặn vì `globalcors` của WebMVC không có tác dụng; header
`Access-Control-Allow-Origin` bị trả 2 lần). DoD của ticket là _"không lỗi
CORS/route"_ → cách bền nhất là **xóa hẳn tình huống cross-origin**, chứ không
phải vá tiếp CORS.

## 1. Thiết kế: FE và API cùng một origin

```
Browser ──► http://localhost:5173/            → nginx trả index.html (SPA)
Browser ──► http://localhost:5173/api/auth/health
                    │  (cùng origin ⇒ không có preflight, không cần CORS)
                    ▼
              nginx (container FE)
                    │  proxy_pass  http://api-gateway:8080/api/...
                    ▼
              api-gateway ──► auth-service
```

Code FE luôn gọi **đường dẫn tương đối** `/api/...`. Vai trò "proxy" do tầng
phục vụ đảm nhiệm, khác nhau theo môi trường nhưng cùng bản chất:

| Môi trường    | Ai proxy `/api`         | Cấu hình                                                              |
| ------------- | ----------------------- | --------------------------------------------------------------------- |
| `npm run dev` | Vite dev server         | `server.proxy` trong `vite.config.js`, đích = `VITE_DEV_API_TARGET`   |
| Docker        | nginx trong container FE | `location /api/` trong `default.conf.template`, đích = `API_GATEWAY_URL` |

## 2. Chi tiết kỹ thuật đáng lưu ý

### 2.1. Tách `nginx.conf` và `default.conf.template`

Đích proxy phải **đổi được lúc runtime** (không build lại image chỉ để trỏ sang
Gateway khác). nginx không đọc được biến môi trường, nên dùng cơ chế template có
sẵn của image nginx: file trong `/etc/nginx/templates/*.template` được entrypoint
chạy `envsubst` rồi ghi ra `/etc/nginx/conf.d/`.

- `nginx.conf` → phần `http` **tĩnh** (gzip, temp path, `map` cho WebSocket).
- `default.conf.template` → `server` block, chứa `${API_GATEWAY_URL}` và
  `${NGINX_RESOLVER}`.
- Container chạy **non-root** (`USER nginx`) nên `/etc/nginx/conf.d` phải được
  `chown` cho user `nginx`, nếu không script `20-envsubst-on-templates.sh` sẽ
  **âm thầm bỏ qua** (nó chỉ ghi khi thư mục writable). Đã xử lý trong Dockerfile.
- `envsubst` của image chỉ thay các biến **đã định nghĩa** và ở dạng `${VAR}`,
  nên biến của nginx (`$uri`, `$host`, `$request_uri`…) không bị đụng.

### 2.2. Resolve DNS theo từng request (`resolver` + biến)

Viết thẳng `proxy_pass http://api-gateway:8080` thì nginx resolve tên **một lần
lúc khởi động** — kéo theo 2 vấn đề thật:

1. Gateway chưa chạy ⇒ nginx **không start nổi** (`host not found in upstream`),
   container FE crash dù phần tĩnh chẳng liên quan gì tới Gateway.
2. Gateway recreate (rất hay xảy ra khi dev rebuild) đổi IP ⇒ nginx vẫn giữ IP cũ
   ⇒ **502 vĩnh viễn** cho tới khi restart luôn container FE.

Cách dùng ở đây: khai báo `resolver ${NGINX_RESOLVER}` (mặc định `127.0.0.11` —
DNS nội bộ của Docker) và đưa đích qua biến `set $gateway "${API_GATEWAY_URL}";`
→ nginx resolve **mỗi request** (cache theo `valid=10s`). Đánh đổi: khi `proxy_pass`
có biến thì nginx **không tự nối URI**, phải viết `proxy_pass $gateway$request_uri;`
(giữ nguyên `/api/...` + query string). Cả hai tình huống trên đã test ở mục 4.3.

### 2.3. Các điểm nhỏ khác

- `/healthz`: endpoint riêng cho `HEALTHCHECK`, trả `200 ok` không đụng
  `index.html` (trước đây healthcheck wget vào `/`).
- `map $http_upgrade $connection_upgrade` + `proxy_set_header Upgrade/Connection`:
  để WebSocket/SSE qua Gateway (notification, chatbot) không bị chết khi dùng tới.
- `client_max_body_size 20m`: upload ảnh sản phẩm đi qua proxy không bị 413.
- Xóa `/etc/nginx/conf.d/default.conf` của image gốc để không còn server `:80` thừa.
- `eslint.config.js`: thêm block `globals.node` cho `*.config.js` vì
  `vite.config.js` dùng `process.cwd()` (nếu không sẽ lỗi `'process' is not defined`).

## 3. File thay đổi

| File                                       | Trạng thái | Thay đổi                                                                                                                                                                             |
| ------------------------------------------ | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `frontend/Dockerfile`                      | M          | Ghi chú chủ ý "1 Dockerfile cho FE"; `ARG VITE_API_BASE_URL` mặc định **rỗng**; copy thêm `default.conf.template`; `ENV API_GATEWAY_URL` / `NGINX_RESOLVER`; `chown` `conf.d` cho non-root; healthcheck đổi sang `/healthz` |
| `frontend/nginx.conf`                      | M          | Chỉ còn mức `http`; thêm `map` WebSocket, `client_max_body_size`; `include conf.d/*.conf`                                                                                              |
| `frontend/default.conf.template`           | **A**      | `server` block: `/healthz`, reverse proxy `/api/` (resolver + biến), cache `/assets/`, SPA fallback                                                                                    |
| `frontend/vite.config.js`                  | M          | Dev server proxy `/api` → `VITE_DEV_API_TARGET` (mặc định `http://localhost:8080`)                                                                                                    |
| `frontend/src/services/api/apiClient.js`   | M          | `baseURL` mặc định **rỗng** = gọi tương đối cùng origin                                                                                                                                |
| `frontend/src/components/HealthStatus.jsx` | M          | Sửa thông báo lỗi (không còn in biến rỗng)                                                                                                                                            |
| `frontend/eslint.config.js`                | M          | `globals.node` cho `*.config.js`                                                                                                                                                      |
| `frontend/.env.example`                    | M          | Thêm `VITE_DEV_API_TARGET`; `VITE_API_BASE_URL` để rỗng + giải thích                                                                                                                  |
| `frontend/README.md`                       | M          | Mục "Chạy bằng Docker", bảng biến môi trường, bỏ bước tạo `.env` bắt buộc                                                                                                             |
| `docker-compose.yml`                       | M          | Block `frontend`: `VITE_API_BASE_URL` rỗng, thêm `environment.API_GATEWAY_URL`; cập nhật comment đầu file                                                                              |
| `.env.example`                             | M          | Thêm `API_GATEWAY_URL`; `VITE_API_BASE_URL` để rỗng + giải thích                                                                                                                      |
| `README.md`                                | M          | Mục "Chạy frontend": dev không cần `.env`, mô tả reverse proxy                                                                                                                        |
| `worklogs/PBL6-3.md`                       | **A**      | Tài liệu này                                                                                                                                                                          |

## 4. Kiểm chứng

Môi trường: `docker compose up -d` (không có file `.env` → chạy bằng giá trị mặc
định trong compose), 7/7 container healthy.

### 4.1. DoD — build production chạy đúng trong container

| Kiểm tra                        | Lệnh                                | Kết quả                                                                                          |
| ------------------------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------ |
| Build image                     | `docker compose build frontend`     | ✅ Vite build 84 modules, image `vmarket-frontend`                                                 |
| Container                       | `docker compose ps`                 | ✅ 7/7 Up, `vmarket-frontend` **healthy**                                                          |
| envsubst chạy đúng dưới non-root | `docker logs vmarket-frontend`      | ✅ `Running envsubst on /etc/nginx/templates/default.conf.template to /etc/nginx/conf.d/default.conf` |
| Trang chủ                       | `curl :5173/`                       | ✅ 200 `text/html`                                                                                 |
| **Route SPA** `/about`          | `curl :5173/about`                  | ✅ 200 (fallback `index.html`, không 404)                                                          |
| Route không tồn tại             | `curl :5173/khong-co-trang-nay`     | ✅ 200 → NotFoundPage do react-router render                                                       |
| Cache asset                     | `curl -D- :5173/assets/index-*.js`  | ✅ `Cache-Control: public, max-age=31536000, immutable`                                            |
| Healthcheck endpoint            | `curl :5173/healthz`                | ✅ 200 `ok`                                                                                        |

### 4.2. DoD — gọi API qua Gateway, không lỗi CORS

| Kiểm tra                                        | Kết quả                                                                                                            |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `curl :5173/api/auth/health` (FE → nginx → gateway → auth) | ✅ 200 `{"status":"UP","service":"auth-service",...}`                                                     |
| Bundle có bị bake `localhost:8080` không?       | ✅ **Không** — `grep localhost:8080` trong `dist/assets/*.js` không ra kết quả                                       |
| Đường dẫn API trong bundle                      | ✅ `/api/auth/health` (tương đối)                                                                                   |
| Header CORS trong response `/api`               | ✅ **Không có** `Access-Control-Allow-Origin` — và không cần, vì request cùng origin `:5173` ⇒ browser không gửi `Origin`, không có preflight |
| Config sinh ra trong container                  | ✅ `set $gateway "http://api-gateway:8080";` / `proxy_pass $gateway$request_uri;`                                    |

### 4.3. Kiểm chứng riêng phần `resolver` (mục 2.2)

| Tình huống                | Cách test                                                                                             | Kết quả                                                                                                    |
| ------------------------- | ----------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Gateway **chưa/không** chạy | `docker compose stop api-gateway` rồi recreate riêng FE                                               | ✅ FE vẫn **start được và healthy**, trang tĩnh 200, `/api` trả **502** (không crash). Cấu hình resolve-lúc-start sẽ crash ở đây |
| Gateway lên lại           | `docker compose up -d api-gateway`                                                                     | ✅ `/api` trả 200 trở lại, **không cần restart FE**                                                         |
| Gateway **đổi IP**        | Chiếm IP cũ `172.18.0.2` bằng container tạm → `docker compose up -d api-gateway` nhận IP mới `172.18.0.9` | ✅ `/api` trả 200, `StartedAt` của FE **không đổi** (FE không hề restart)                                |

> Một lần test hỏng cần ghi lại để khỏi lặp: dùng `docker network disconnect` +
> `connect --ip` để ép đổi IP **không dùng được** — thao tác đó làm mất luôn DNS
> alias và port publish của container gateway (gọi thẳng IP cũng timeout), tức là
> hỏng chính cái container đang test chứ không phải lỗi nginx. Cách đúng là để
> compose recreate và chặn IP cũ bằng container tạm như bảng trên.

### 4.4. Không làm hỏng thứ khác

| Kiểm tra                                     | Kết quả                                                                                            |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `docker compose config`                      | ✅ 7 service, block `frontend` resolve đúng (`API_GATEWAY_URL: http://api-gateway:8080`, `VITE_API_BASE_URL: ""`) |
| `scripts/check-env.ps1` (job CI `env-consistency`) | ✅ OK — đã đối chiếu 49 giá trị mặc định trong compose với `.env.example`                       |
| `npm run build`                              | ✅ built in ~1s                                                                                     |
| `npm run lint`                               | ✅ 0 lỗi                                                                                            |

## 5. Lưu ý & việc cần làm tiếp

1. **CORS ở BE vẫn giữ nguyên**, không xóa: luồng web qua nginx là cùng origin nên
   không dùng tới, nhưng mobile (React Native) và trường hợp deploy FE tách rời vẫn
   gọi cross-origin. `CORS_ALLOWED_ORIGINS` giờ là **đường phụ**, không còn là thứ
   luồng chính phụ thuộc — bug CORS kiểu PBL6-10 mục 5 không tái diễn ở web nữa.
2. **Đổi Gateway không cần build lại image FE**: sửa `API_GATEWAY_URL` trong `.env`
   rồi `docker compose up -d frontend`.
3. Muốn quay lại kiểu gọi thẳng Gateway (FE deploy tách rời, không qua nginx này):
   đặt `VITE_API_BASE_URL=http://<gateway>` — nhớ thêm origin FE vào
   `CORS_ALLOWED_ORIGINS`, vì lúc đó là cross-origin thật.
4. **Chưa có CI cho FE**: `.github/workflows/` mới có pipeline cho service BE
   (PBL6-2) và `env-consistency`. Build image FE + lint/build hiện chỉ chạy tay.
   Đề xuất tách ticket riêng thêm `frontend.yml` theo mẫu `service-ci.yml`.
5. `npm run format:check` đang báo 13 file — **có từ trước**, do `.gitattributes`
   dùng `* text=auto` (checkout thành CRLF trên Windows) trong khi `.prettierrc`
   đặt `endOfLine: "lf"`. Không sửa trong ticket này để tránh diff rác toàn repo;
   nên xử lý riêng (thêm `*.{js,jsx,json,css,md} text eol=lf` vào `.gitattributes`).
