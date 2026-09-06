import axios from 'axios'

// Mặc định là chuỗi RỖNG = gọi cùng origin với trang web: đường dẫn `/api/...`
// đi qua reverse proxy rồi mới tới API Gateway.
//   - chạy bằng Docker: nginx trong container frontend proxy `/api/` → api-gateway
//   - chạy `npm run dev`: Vite dev server proxy `/api` → gateway (xem vite.config.js)
// Cùng origin nên KHÔNG phát sinh CORS (không preflight, không phụ thuộc cấu hình
// allowed-origins của gateway).
//
// Dùng `??` chứ không phải `||`: `||` sẽ coi chuỗi rỗng là "chưa đặt" và nhảy sang
// giá trị mặc định, khiến không thể chọn chế độ cùng origin bằng biến môi trường.
// Chỉ đặt VITE_API_BASE_URL thành URL tuyệt đối khi thực sự cần gọi thẳng gateway
// ở host khác — lúc đó CORS quay lại và gateway phải cho phép origin của FE.
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

export default apiClient
