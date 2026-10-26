import { Link } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import '../components/account.css'

export default function RoleAreaPage({ role }) {
  const { session, userProfile } = useAuth()
  const admin = role === 'ADMIN'
  const title = admin ? 'Khu vực quản trị' : 'Khu vực giao hàng'
  return (
    <main className="account-page account-page--narrow">
      <section className="account-card account-state">
        <span className="account-eyebrow">{title}</span>
        <h1>Chào {userProfile?.fullName || session.username}</h1>
        <p>Tài khoản của bạn có quyền {admin ? 'quản trị viên' : 'người giao hàng'}.</p>
        <p className="account-notice">
          Các công cụ {admin ? 'quản trị' : 'quản lý giao hàng'} sẽ được bổ sung trong khu vực này.
        </p>
        <div className="account-actions">
          <Link className="account-button" to="/profile">
            Hồ sơ cá nhân
          </Link>
          <Link className="account-button account-button--secondary" to="/">
            Về trang chủ
          </Link>
        </div>
      </section>
    </main>
  )
}
