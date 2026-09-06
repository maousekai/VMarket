import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  // Đích proxy của dev server. Mặc định là gateway publish ra máy host qua
  // docker-compose (GATEWAY_PORT=8080); đổi khi chạy gateway ở port khác.
  const devApiTarget = env.VITE_DEV_API_PROXY || 'http://localhost:8080'

  return {
    plugins: [react()],

    server: {
      port: 5173,
      // Cho `npm run dev` hành xử GIỐNG bản chạy trong Docker: FE gọi `/api/...`
      // trên chính origin của nó, dev server chuyển tiếp sang gateway. Nhờ vậy
      // dev và production cùng một luồng gọi, và dev cũng không dính CORS.
      proxy: {
        '/api': {
          target: devApiTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
