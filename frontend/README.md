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

Mở `http://localhost:5173`. Trang chủ sẽ gọi thử API health-check của backend — nếu backend đang chạy ở `http://localhost:8080` bạn sẽ thấy thông báo **kết nối API thành công** (đã cấu hình CORS ở backend cho phép `localhost:5173` và `localhost:3000`).

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

| Biến               | Mặc định                 | Ý nghĩa                        |
| ------------------ | ------------------------ | ------------------------------ |
| `VITE_API_BASE_URL` | `http://localhost:8080` | Base URL của API Gateway/BE    |

> File `.env` không được commit (đã gitignore). Chỉ commit `.env.example` làm mẫu.

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
