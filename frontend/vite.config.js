import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    server: {
      watch: {
        ignored: ['**/.cache/**', '**/test-results/**', '**/playwright-report/**'],
      },
      // Dev server proxy /api -> API Gateway, giong vai tro cua nginx trong
      // container. Nho vay code FE luon goi duong dan tuong doi (/api/...),
      // cung origin ca khi dev lan khi chay docker => khong dinh CORS.
      proxy: {
        // WebSocket theo doi giao hang di thang toi delivery-service (Gateway WebMVC 5.0.x chua
        // proxy WebSocket); khai bao truoc '/api' de khop truoc.
        '/api/delivery/ws': {
          target: env.VITE_DEV_DELIVERY_WS_TARGET || 'http://localhost:8088',
          ws: true,
          changeOrigin: true,
        },
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
