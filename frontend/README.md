# VMarket Frontend

Frontend mẫu (React + Vite) làm khung cho Web end-user / Web Admin của dự án VMarket.

## Công nghệ

- React 19 + Vite 8
- React Router (điều hướng trang)
- Axios (gọi API)
- ESLint + Prettier (chuẩn hoá code)

## Yêu cầu môi trường

- Node.js 20+ (khuyến nghị bản LTS mới nhất)
- npm 10+

## Cài đặt và chạy local

```bash
# 1. Cài dependencies
npm install

# 2. Tạo file môi trường từ mẫu
cp .env.example .env      # Windows: copy .env.example .env

# 3. Chạy dev server
npm run dev
```

Mở `http://localhost:5173`. Trang chủ gọi thử `GET /api/auth/health` — thấy **kết nối API thành công** khi API Gateway và Auth Service đang chạy.

## Cách FE gọi API — cùng origin, không CORS

FE **không** gọi thẳng `http://localhost:8080`. Nó gọi đường dẫn tương đối `/api/...` trên chính origin của trang, và một reverse proxy đứng trước sẽ chuyển tiếp sang API Gateway:

| Chạy bằng | Ai proxy `/api` | Đích |
| --- | --- | --- |
| `npm run dev` | Vite dev server (`server.proxy` trong `vite.config.js`) | `VITE_DEV_API_PROXY` (mặc định `http://localhost:8080`) |
| Docker | nginx trong container `frontend` (`nginx.conf`) | `http://api-gateway:8080` |

Trình duyệt chỉ thấy **một origin duy nhất** nên không có preflight `OPTIONS`, không cần `Access-Control-Allow-Origin`, và đổi port FE cũng không làm hỏng gì. Cấu hình CORS ở gateway vẫn giữ, nhưng chỉ dành cho client gọi **trực tiếp** (mobile, web admin, hoặc khi cố ý đặt `VITE_API_BASE_URL` tuyệt đối).

Gateway định tuyến theo prefix `/api/...` (`Path=/api/auth/**`, `/api/products/**`...) nên proxy chuyển tiếp URL **nguyên vẹn**, không cắt bỏ `/api`.

## Scripts

| Lệnh                 | Chức năng                        |
| -------------------- | -------------------------------- |
| `npm run dev`        | Chạy dev server (hot reload)     |
| `npm run build`      | Build production vào `dist/`     |
| `npm run preview`    | Xem thử bản build                |
| `npm run lint`       | Kiểm tra code bằng ESLint        |
| `npm run format`     | Format code bằng Prettier        |
| `npm run format:check` | Kiểm tra format (dùng cho CI)  |

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `VITE_API_BASE_URL` | *(rỗng)* | Base URL của API. **Để rỗng** = gọi cùng origin qua reverse proxy (khuyến nghị). Chỉ điền URL tuyệt đối khi cần gọi thẳng gateway ở host khác — lúc đó CORS quay lại |
| `VITE_DEV_API_PROXY` | `http://localhost:8080` | Đích mà **dev server** chuyển tiếp `/api` tới. Chỉ có tác dụng với `npm run dev`, không ảnh hưởng bản build |

> Vite đọc biến `VITE_*` **lúc build** và nhúng thẳng vào bundle. Đổi giá trị sau khi image đã build thì phải build lại (`docker compose up -d --build frontend`) — sửa biến môi trường lúc chạy container không có tác dụng.

> File `.env` không được commit (đã gitignore). Chỉ commit `.env.example` làm mẫu.

## Chạy bằng Docker

```bash
# Từ thư mục gốc repo
docker compose up -d --build frontend     # http://localhost:5173
```

Image dựng theo 2 stage: **Node 22** build bằng Vite → **nginx 1.28-alpine** phục vụ `dist/` tĩnh, chạy non-root, có sẵn `HEALTHCHECK`. `nginx.conf` lo hai việc:

1. **SPA routing** — mọi đường dẫn không khớp file tĩnh đều trả `index.html`, để react-router xử lý (F5 giữa chừng không còn 404).
2. **Reverse proxy** — `/api/` chuyển tiếp sang `api-gateway:8080`.

Upstream được viết dưới dạng **biến** kèm `resolver 127.0.0.11` (DNS nội bộ của Docker). Đây là điểm dễ sai: nếu viết thẳng `proxy_pass http://api-gateway:8080`, nginx resolve DNS **một lần lúc đọc config** — gateway chưa có thì container FE chết ngay lúc khởi động, còn gateway restart đổi IP thì proxy hỏng đến khi restart tay. Dùng biến thì FE luôn lên được, `/api/` trả 502 cho tới khi gateway UP, và IP mới được nhận lại sau tối đa 10 giây.

## Cấu trúc thư mục

```
src/
├── assets/            # Ảnh, icon tĩnh
├── components/        # Component dùng chung (HealthStatus...)
├── hooks/             # Custom hooks (useHealth...)
├── pages/             # Trang (HomePage, AboutPage...)
├── services/
│   └── api/           # Cấu hình axios + các hàm gọi API
│       ├── apiClient.js
│       └── healthApi.js
├── App.jsx            # Định nghĩa routes
├── index.css          # Style toàn cục
└── main.jsx           # Entry point (BrowserRouter)
```

## Quy ước

- Chạy `npm run lint` và `npm run format` trước khi commit.
- Component mới đặt trong `components/`, trang mới đặt trong `pages/` và thêm route trong `App.jsx`.
- API mới viết hàm trong `services/api/` dùng chung `apiClient`.
