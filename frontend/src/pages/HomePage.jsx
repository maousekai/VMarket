import { Link } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import illustration from '../assets/auth/da-nang.png'
import FormMessage from '../components/FormMessage'

export default function HomePage() {
  const auth = useAuth()
  return (
    <main className="page">
      {auth.bootstrapState === 'error' && (
        <div className="session-notice">
          <FormMessage error={auth.error} />
          <button className="text-button" onClick={() => auth.refresh().catch(() => {})}>
            Thử lại kết nối
          </button>
        </div>
      )}
      <section className="welcome">
        <div>
          <h1>
            Chợ trực tuyến
            <br />
            Đà Nẵng
          </h1>
          <p>
            Mua sắm dễ dàng, giao hàng nhanh. Kết nối với cộng đồng người mua và nhà bán ngay tại
            thành phố của bạn.
          </p>
          <div className="welcome-actions">
            {auth.session ? (
              <Link
                className="button button--primary"
                to={auth.session.status === 'PENDING' ? '/verify-email' : '/profile'}
              >
                {auth.session.status === 'PENDING' ? 'Xác thực tài khoản' : 'Hồ sơ của tôi'}
              </Link>
            ) : (
              <>
                <Link className="button button--primary" to="/register">
                  Tham gia VMarket
                </Link>
                <Link className="button button--secondary" to="/login">
                  Đăng nhập
                </Link>
              </>
            )}
          </div>
        </div>
        <img src={illustration} width="455" height="315" alt="Cầu Rồng Đà Nẵng bên sông Hàn" />
      </section>
    </main>
  )
}
