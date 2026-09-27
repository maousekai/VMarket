import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [react()],
    server: {
      // Dev server proxy /api -> API Gateway, giong vai tro cua nginx trong
      // container. Nho vay code FE luon goi duong dan tuong doi (/api/...),
      // cung origin ca khi dev lan khi chay docker => khong dinh CORS.
      proxy: {
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
