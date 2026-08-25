# VMarket (PBL6)

Dự án marketplace — PBL6. Repository chứa backend (Spring Boot) và frontend (React + Vite).

## Cấu trúc repo

```
VMarket/
├── backend/            # Spring Boot (Java 17) — service backend mẫu
├── frontend/           # React + Vite — frontend mẫu (web end-user/admin)
├── docker-compose.yml  # PostgreSQL cho môi trường dev
├── CONTRIBUTING.md     # Quy ước branch, commit, PR
└── README.md
```

## Yêu cầu môi trường

| Công cụ   | Phiên bản | Dùng cho                     |
| --------- | --------- | ---------------------------- |
| JDK       | 17+       | Backend                      |
| Node.js   | 20+       | Frontend                     |
| Docker    | mới nhất  | Chạy PostgreSQL local        |
| Git       | mới nhất  | —                            |

> Không cần cài Maven (backend dùng Maven Wrapper `mvnw`).

## Chạy nhanh toàn bộ

Bước 1 — khởi động database:

```bash
docker compose up -d db
```

Bước 2 — chạy backend (terminal 1):

```bash
cd backend
.\mvnw.cmd spring-boot:run     # Windows
# ./mvnw spring-boot:run       # macOS/Linux
```

Bước 3 — chạy frontend (terminal 2):

```bash
cd frontend
npm install
copy .env.example .env         # Windows (macOS/Linux: cp .env.example .env)
npm run dev
```

Mở `http://localhost:5173` — trang chủ hiển thị **kết nối API thành công** nếu backend đã chạy.

## Kiểm tra cài đặt thành công

- Backend: `curl http://localhost:8080/api/health` trả về `{"status":"UP",...}`
- Actuator: `curl http://localhost:8080/actuator/health` trả về `{"status":"UP"}`
- Frontend: `http://localhost:5173` hiển thị trạng thái API, không có lỗi console

## Tài liệu chi tiết

- Backend: [`backend/README.md`](backend/README.md)
- Frontend: [`frontend/README.md`](frontend/README.md)
- Quy ước git (branch/commit/PR): [`CONTRIBUTING.md`](CONTRIBUTING.md)
