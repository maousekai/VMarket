import axios from 'axios'

// baseURL rong = goi duong dan tuong doi (/api/...) ve chinh origin dang mo:
//   - dev  (npm run dev): vite proxy /api -> API Gateway (vite.config.js)
//   - docker (nginx)    : nginx reverse proxy /api -> API Gateway
// Ca hai deu cung origin nen khong dinh CORS/preflight.
// Chi dat VITE_API_BASE_URL khi co chu dinh goi thang Gateway o origin khac
// (luc do BE phai cho phep origin cua FE trong CORS_ALLOWED_ORIGINS).
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

export default apiClient
