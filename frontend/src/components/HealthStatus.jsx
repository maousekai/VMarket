import { useHealth } from '../hooks/useHealth'

export default function HealthStatus() {
  const { health, error, loading } = useHealth()

  if (loading) {
    return <p className="status status--loading">Đang kiểm tra kết nối API…</p>
  }

  if (error) {
    // Mặc định FE gọi cùng origin (`/api/...`) qua reverse proxy, nên không có
    // URL tuyệt đối để hiển thị — nói rõ đang gọi qua đâu để dễ chẩn đoán.
    const target = import.meta.env.VITE_API_BASE_URL || '/api (qua reverse proxy)'

    return (
      <p className="status status--error">
        Chưa kết nối được API ({error.message}). Đang gọi qua <code>{target}</code> — hãy chắc
        chắn API Gateway và Auth Service đã khởi động.
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
