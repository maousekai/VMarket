import { Link, Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import './account.css'

export default function RouteGuard({ children, roles = [] }) {
  const { session, bootstrapState, error, refresh } = useAuth()
  const location = useLocation()
  if (bootstrapState === 'loading') {
    return (
      <main className="account-page">
        <p className="account-notice" role="status">
          Đang khôi phục phiên đăng nhập…
        </p>
      </main>
    )
  }
  if (bootstrapState === 'error') {
    return (
      <main className="account-page">
        <section className="account-card account-state">
          <h1>Chưa thể khôi phục phiên</h1>
          <p role="alert">{error?.message || 'Không thể kết nối máy chủ. Vui lòng thử lại.'}</p>
          <button
            type="button"
            className="account-button"
            onClick={() => refresh().catch(() => {})}
          >
            Thử lại
          </button>
        </section>
      </main>
    )
  }
  const returnTo = `${location.pathname}${location.search}${location.hash}`
  if (!session) return <Navigate to="/login" replace state={{ returnTo }} />
  if (session.status === 'PENDING')
    return <Navigate to="/verify-email" replace state={{ returnTo }} />
  if (session.status !== 'ACTIVE') return <Navigate to="/login" replace />
  if (roles.length && !roles.some((role) => session.roles.includes(role))) {
    return (
      <main className="account-page">
        <section className="account-card account-state">
          <span className="account-eyebrow">Phân quyền tài khoản</span>
          <h1>Bạn chưa có quyền truy cập</h1>
          <p>Khu vực này không nằm trong quyền của tài khoản đang đăng nhập.</p>
          <Link className="account-button" to="/">
            Về trang chủ
          </Link>
        </section>
      </main>
    )
  }
  return children || <Outlet />
}
