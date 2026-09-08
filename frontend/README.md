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

# 2. Chạy dev server
npm run dev
```

Mở `http://localhost:5173`. Trang chủ gọi API health-check qua đường dẫn tương
đối `/api/auth/health`; dev server proxy tiếp sang API Gateway
`http://localhost:8080` (xem `vite.config.js`) nên request **cùng origin**,
không dính CORS. Chỉ cần tạo `.env` từ `.env.example` khi muốn đổi mặc định.

## Scripts

| Lệnh                   | Chức năng                     |
| ---------------------- | ----------------------------- |
| `npm run dev`          | Chạy dev server (hot reload)  |
| `npm run build`        | Build production vào `dist/`  |
| `npm run preview`      | Xem thử bản build             |
| `npm run lint`         | Kiểm tra code bằng ESLint     |
| `npm run format`       | Format code bằng Prettier     |
| `npm run format:check` | Kiểm tra format (dùng cho CI) |

## Biến môi trường

| Biến                  | Mặc định                | Ý nghĩa                                                                                                                                                                        |
| --------------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `VITE_DEV_API_TARGET` | `http://localhost:8080` | Đích proxy `/api` của dev server (chỉ dùng khi `npm run dev`)                                                                                                                  |
| `VITE_API_BASE_URL`   | _(rỗng)_                | Base URL API bake vào bundle. Rỗng = gọi tương đối `/api` cùng origin (khuyến nghị). Chỉ điền khi cố ý gọi thẳng Gateway ở origin khác — khi đó BE phải bật CORS cho origin FE |

> File `.env` không được commit (đã gitignore). Chỉ commit `.env.example` làm mẫu.

## Chạy bằng Docker

```bash
docker compose up -d --build frontend    # http://localhost:5173
```

Image multi-stage: build bằng `node:22-alpine`, phục vụ `dist/` bằng
`nginx:1.28-alpine` (chạy non-root). nginx đảm nhiệm 2 việc:

- **SPA routing** — route của react-router không trùng file tĩnh đều fallback về
  `index.html` (không 404), asset có hash được cache 1 năm.
- **Reverse proxy** `/api/*` sang API Gateway → browser gọi cùng origin nên
  **không dính CORS/preflight**.

Đích proxy đổi được lúc runtime bằng biến `API_GATEWAY_URL`
(mặc định `http://api-gateway:8080`) — **không cần build lại image**, vì
`default.conf.template` được envsubst lúc container khởi động.

| File                    | Vai trò                                                    |
| ----------------------- | ---------------------------------------------------------- |
| `Dockerfile`            | Multi-stage build → image nginx tĩnh                       |
| `nginx.conf`            | Cấu hình mức `http` (gzip, temp path cho non-root, map WS) |
| `default.conf.template` | `server` block: SPA fallback + reverse proxy `/api`        |

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
