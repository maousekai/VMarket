import HealthStatus from '../components/HealthStatus'

export default function HomePage() {
  return (
    <main className="page">
      <h1>VMarket</h1>
      <p className="page__subtitle">
        Frontend mẫu — React + Vite. Trang này gọi thử API health-check của backend để xác nhận
        kết nối và CORS.
      </p>
      <HealthStatus />
    </main>
  )
}
