import { useHealth } from '../hooks/useHealth'

export default function HealthStatus() {
  const { health, error, loading } = useHealth()

  if (loading) {
    return <p className="status status--loading">Đang kiểm tra kết nối API…</p>
  }

  if (error) {
    return (
      <p className="status status--error">
        Chưa kết nối được API ({error.message}). Hãy chắc chắn API Gateway đang chạy ở{' '}
        <code>{import.meta.env.VITE_API_BASE_URL}</code> và Auth Service đã khởi động.
      </p>
    )
  }

  return (
    <p className="status status--ok">
      Kết nối API thành công — <strong>{health.service}</strong> · trạng thái{' '}
      <strong>{health.status}</strong>
    </p>
  )
}
