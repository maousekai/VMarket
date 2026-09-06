# Worklog — Dockerfile FE + nginx reverse proxy tới API Gateway

- **Story / subtask:** Dockerfile hoá frontend React (tiếp nối PBL6-9 compose và PBL6-10)
- **Branch:** `ticket/PBL6-frontend-docker` (nhánh từ `dev` — `6111510`)
- **Ngày:** 06/09/2026
- **Phạm vi:** đưa FE chạy được bằng container với bản build production, và bỏ hẳn
  CORS bằng reverse proxy. **Không** làm: Web Admin, Mobile, HTTPS/TLS, khối FE
  trong `docker-compose.prod.yml`.

---

## 1. Hiện trạng trước khi làm

PBL6-10 đã có sẵn phần lớn ticket này, cần ghi rõ để không nhận vơ công việc:

| Đầu việc của ticket | Trạng thái trước |
| --- | :---: |
| Dockerfile multi-stage (Node build → nginx serve) | **Đã có** — `frontend/Dockerfile`, non-root, có `HEALTHCHECK` |
| Nginx xử lý route SPA | **Đã có** — `try_files $uri $uri/ /index.html` |
| Thêm service FE vào `docker-compose.yml` | **Đã có** — khối `frontend`, port 5173→8080 |
| **Nginx reverse proxy tới API Gateway** | **CHƯA CÓ** ← phần chính của ticket này |
| DoD "không lỗi CORS" | **Chưa đạt về bản chất** — xem mục 2 |

## 2. Vấn đề thật sự cần giải

Luồng cũ: `VITE_API_BASE_URL=http://localhost:8080` được bake vào bundle, nên trình
duyệt ở `http://localhost:5173` gọi **thẳng** sang `http://localhost:8080` — **khác
origin**. Nó chạy được, nhưng chỉ vì gateway đang khai `APP_CORS_ALLOWED_ORIGINS`
liệt kê sẵn `localhost:5173`. Hệ quả:

- Mỗi request không đơn giản đều tốn thêm một vòng preflight `OPTIONS`.
- Đổi `FRONTEND_PORT` mà quên sửa `CORS_ALLOWED_ORIGINS` là FE gãy ngay, lỗi hiện
  ra ở trình duyệt chứ không phải ở log server → mất thời gian dò.
- Lên production sẽ phải khai domain thật vào danh sách origin, thêm một thứ nữa
  phải nhớ đồng bộ.

DoD của ticket ghi "không lỗi CORS". Cách chắc chắn nhất không phải là cấu hình CORS
cho đúng, mà là **không tạo ra cross-origin** ngay từ đầu.

## 3. Cách làm

FE gọi đường dẫn tương đối `/api/...` trên **chính origin của nó**, reverse proxy
đứng trước chuyển tiếp sang gateway. Trình duyệt chỉ thấy một origin → không
preflight, không cần header CORS nào.

| Chạy bằng | Ai proxy `/api` | Đích |
| --- | --- | --- |
| Docker | nginx trong container `frontend` | `http://api-gateway:8080` |
| `npm run dev` | Vite dev server (`server.proxy`) | `VITE_DEV_API_PROXY` (mặc định `http://localhost:8080`) |

Dev và production dùng **cùng một luồng gọi**, nên lỗi kiểu "chạy dev thì được, vào
container thì hỏng" không còn đất sống.

### Điểm kỹ thuật đáng chú ý: upstream phải là biến

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;
set $api_gateway "http://api-gateway:8080";
location /api/ { proxy_pass $api_gateway$request_uri; ... }
```

Nếu viết thẳng `proxy_pass http://api-gateway:8080` thì nginx resolve DNS **một lần
lúc đọc config**:

- gateway chưa tồn tại → **nginx chết ngay lúc khởi động** (`host not found in
  upstream`), container FE không lên được dù bản thân nó chẳng hỏng gì;
- gateway restart đổi IP → nginx giữ IP cũ, proxy hỏng âm thầm đến khi restart tay.

Dùng biến + `resolver` (DNS nội bộ của Docker cho user-defined network) thì FE luôn
lên được, `/api/` trả 502 cho tới khi gateway UP, và IP mới được nhận lại sau tối đa
10 giây. Đây cũng là lý do `depends_on: api-gateway` chỉ để xếp thứ tự khởi động,
không ràng buộc vòng đời container FE vào gateway.

`$request_uri` được chuyển tiếp **nguyên vẹn** vì routes của gateway khai
`Path=/api/auth/**` — prefix `/api` phải giữ, không được cắt.

## 4. File đã sửa

| File | Nội dung |
| --- | --- |
| `frontend/nginx.conf` | Thêm `resolver`, `map $http_upgrade`, `location /api/` proxy sang gateway kèm `X-Forwarded-*`, header WebSocket/SSE, timeout. Thêm ghi chú về bẫy kế thừa `add_header` |
| `frontend/vite.config.js` | Thêm `server.proxy` cho `/api` (đọc `VITE_DEV_API_PROXY` qua `loadEnv`) để dev cùng luồng với production |
| `frontend/src/services/api/apiClient.js` | `baseURL` mặc định về chuỗi rỗng (cùng origin). Dùng `??` thay `\|\|` — `\|\|` coi chuỗi rỗng là "chưa đặt" nên không thể chọn chế độ cùng origin bằng biến môi trường |
| `frontend/src/components/HealthStatus.jsx` | Thông báo lỗi không còn in ra biến rỗng; nói rõ đang gọi qua `/api (qua reverse proxy)` |
| `frontend/Dockerfile` | `ARG VITE_API_BASE_URL=` (rỗng). Ghi rõ giá trị bị bake lúc build, đổi thì phải build lại |
| `docker-compose.yml` | `VITE_API_BASE_URL: ${VITE_API_BASE_URL:-}`; giải thích `depends_on` chỉ xếp thứ tự; ghi rõ không khai `healthcheck` vì image đã có |
| `.env.example`, `frontend/.env.example` | `VITE_API_BASE_URL` để rỗng là mặc định; thêm `VITE_DEV_API_PROXY`; nói rõ `CORS_ALLOWED_ORIGINS` giờ chỉ dành cho client gọi trực tiếp |
| `frontend/eslint.config.js` | Thêm block `globals.node` cho `vite.config.js` (file này chạy bằng Node, không phải trình duyệt) |
| `README.md`, `frontend/README.md` | Mục cách FE gọi API, mục chạy bằng Docker, thêm dòng kiểm chứng `curl http://localhost:5173/api/auth/health` |

## 5. Kiểm thử

| # | Kiểm tra | Kết quả |
| --: | --- | --- |
| 1 | `docker compose config --quiet` | ✅ hợp lệ |
| 2 | `npm ci` + `npm run build` | ✅ build 1.29s, 84 module, `dist/` sinh ra đủ |
| 3 | Bundle **không** còn URL tuyệt đối | ✅ `grep -c "localhost:8080" dist/assets/*.js` = **0** |
| 4 | Bundle gọi đường dẫn tương đối | ✅ chứa `/api/auth/health`, `baseURL` là chuỗi rỗng |
| 5 | `npm run lint` | ✅ sạch (xem ghi chú ESLint bên dưới) |

**Ghi chú ESLint:** `eslint.config.js` chỉ khai `globals.browser` cho mọi file `.js`,
nhưng `vite.config.js` chạy bằng **Node** nên `process.cwd()` bị báo
`'process' is not defined`. Đã thêm một block riêng khai `globals.node` cho đúng
file đó thay vì né tránh — đây là file cấu hình Node thật, các thứ thêm vào sau
(đọc biến môi trường, đường dẫn) cũng sẽ cần.

**Phát hiện ngoài phạm vi ticket — `npm run format:check` hỏng sẵn trên Windows:**
lệnh này báo lỗi **16/16 file** ngay trên `dev` khi chưa có thay đổi nào. Nguyên
nhân không phải style: `.prettierrc` đặt `endOfLine: "lf"`, trong khi
`.gitattributes` khai `* text=auto` + `core.autocrlf=true` nên bản làm việc trên
Windows là CRLF. `diff` giữa bản prettier sinh ra và file trên đĩa chỉ khác đúng ký
tự xuống dòng. Cố ý **không** chạy `prettier --write`: nó sẽ đổi toàn bộ 16 file
sang LF, tạo diff rác che mất thay đổi thật của ticket này. CI chạy trên Linux
(checkout LF) nên không bị. Cách sửa gọn cho ticket riêng: thêm
`*.{js,jsx,css,html,json,md} text eol=lf` vào `.gitattributes`, hoặc đổi
`endOfLine` thành `"auto"`.

**Chưa chạy được tại máy:** `docker compose up` để mở trình duyệt và gọi API xuyên
qua nginx — Docker Desktop không khởi động được lúc làm (`failed to connect to the
docker API at npipe:////./pipe/dockerDesktopLinuxEngine`). `nginx -t` cũng cần
container nên chưa xác thực được cú pháp `nginx.conf` bằng máy.

Cần chạy đủ 4 bước sau trước khi mở PR:

```bash
docker compose up -d --build frontend api-gateway auth-service
docker compose exec frontend nginx -t                      # cú pháp nginx
curl http://localhost:5173/api/auth/health                  # proxy -> gateway -> auth
curl http://localhost:5173/mot-route-bat-ky                 # SPA fallback: trả index.html, 200
```

Rồi mở `http://localhost:5173`, xem tab Network: phải thấy request tới
`localhost:5173/api/auth/health` và **không** có request `OPTIONS` nào.

## 6. TODO chuyển tiếp

1. **Chạy 4 bước kiểm chứng ở mục 5** khi Docker Desktop hoạt động — đây là điều kiện đủ của DoD.
2. **Thêm khối `frontend` + `api-gateway` vào `docker-compose.prod.yml`** — file prod hiện mới có hạ tầng và auth-service.
3. **Header bảo mật** (`X-Content-Type-Options`, `Referrer-Policy`, CSP): cố ý chưa thêm vì `add_header` không cộng dồn giữa các cấp — thêm ở `server` sẽ làm biến mất `Cache-Control` đang khai trong `location /assets/` và `location = /index.html`. Làm riêng một ticket, thêm đủ ở cả 3 nơi.
4. **`proxy_buffering off`** cho endpoint chatbot khi làm tính năng streaming (SSE).
5. **Web Admin / Mobile**: khi tách ra sẽ cần khối compose riêng; Web Admin dùng lại được nguyên Dockerfile + nginx.conf này.
