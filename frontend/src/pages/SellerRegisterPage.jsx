import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import ShopForm from '../components/ShopForm'
import { getApiError } from '../services/api/apiClient'
import { getMyShop, registerShop } from '../services/api/shopApi'
import '../components/account.css'
import '../components/seller.css'

export default function SellerRegisterPage() {
  const navigate = useNavigate()
  const [check, setCheck] = useState({ state: 'loading', error: '' })

  useEffect(() => {
    let current = true
    getMyShop()
      .then(() => {
        if (current) navigate('/seller', { replace: true })
      })
      .catch((failure) => {
        if (!current) return
        const error = getApiError(failure)
        if (error.status === 404 && error.code === 'SHOP_NOT_FOUND') {
          setCheck({ state: 'ready', error: '' })
        } else {
          setCheck({ state: 'error', error: error.message })
        }
      })
    return () => {
      current = false
    }
  }, [navigate])

  async function onSubmit(body) {
    await registerShop(body)
    navigate('/seller', { replace: true, state: { registered: true } })
  }

  if (check.state === 'loading') {
    return (
      <main className="account-page account-page--narrow">
        <p className="account-notice" role="status">
          Đang kiểm tra gian hàng của bạn…
        </p>
      </main>
    )
  }

  if (check.state === 'error') {
    return (
      <main className="account-page account-page--narrow">
        <section className="account-card account-state">
          <h1>Chưa thể mở biểu mẫu đăng ký</h1>
          <p role="alert">{check.error}</p>
          <Link className="account-button" to="/seller">
            Quay lại kênh người bán
          </Link>
        </section>
      </main>
    )
  }

  return (
    <main className="account-page account-page--narrow">
      <nav className="account-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        <span aria-hidden="true">›</span>
        <Link to="/seller">Kênh người bán</Link>
        <span aria-hidden="true">›</span>
        <span>Đăng ký gian hàng</span>
      </nav>
      <section className="seller-register">
        <header className="seller-register__header">
          <span className="account-eyebrow">Bắt đầu kinh doanh</span>
          <h1>Đăng ký gian hàng VMarket</h1>
          <p className="account-subtitle">
            Điền đầy đủ thông tin để VMarket xét duyệt. Hồ sơ chỉ hiển thị với người mua sau khi
            được duyệt.
          </p>
        </header>
        <ShopForm
          withBank
          onSubmit={onSubmit}
          submitLabel="Gửi hồ sơ xét duyệt"
          busyLabel="Đang gửi hồ sơ…"
          actions={
            <Link className="account-button account-button--secondary" to="/seller">
              Hủy
            </Link>
          }
        />
      </section>
    </main>
  )
}
