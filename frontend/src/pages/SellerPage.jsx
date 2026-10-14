import { useEffect, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { getMyShop } from '../services/api/shopApi'
import { getApiError } from '../services/api/apiClient'
import '../components/account.css'

const statusContent = {
  PENDING: {
    label: 'Chờ duyệt',
    title: 'Hồ sơ gian hàng đang chờ duyệt',
    message: 'Bạn có thể quay lại kênh người bán để theo dõi kết quả xét duyệt.',
  },
  REJECTED: {
    label: 'Cần bổ sung',
    title: 'Hồ sơ gian hàng chưa được duyệt',
    message: 'Xem lý do bên dưới để chuẩn bị cập nhật hồ sơ gian hàng.',
  },
  SUSPENDED: {
    label: 'Đình chỉ',
    title: 'Gian hàng đang bị đình chỉ',
    message: 'Gian hàng chưa thể tiếp tục hoạt động. Xem lý do đình chỉ bên dưới.',
  },
  ACTIVE: {
    label: 'Đang hoạt động',
    title: 'Chào mừng đến kênh người bán',
    message: 'Gian hàng của bạn đã được duyệt trên VMarket.',
  },
}

export default function SellerPage() {
  const { session, refresh } = useAuth()
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ state: 'loading', shop: null })
  const userId = session.userId
  const roleKey = session.roles.join(',')

  useEffect(() => {
    let current = true
    setResult({ state: 'loading', shop: null, userId })
    async function load() {
      try {
        const shop = await getMyShop()
        let sellerAllowed = roleKey.split(',').includes('SELLER')
        if (shop.status === 'ACTIVE' && !sellerAllowed) {
          const freshSession = await refresh()
          sellerAllowed = freshSession?.userId === userId && freshSession.roles.includes('SELLER')
        }
        if (current) setResult({ state: 'ready', shop, sellerAllowed, userId })
      } catch (error) {
        if (!current) return
        const failure = getApiError(error)
        setResult({
          state: failure.status === 404 && failure.code === 'SHOP_NOT_FOUND' ? 'missing' : 'error',
          shop: null,
          error: failure.message,
          userId,
        })
      }
    }
    load()
    return () => {
      current = false
    }
  }, [userId, roleKey, refresh, attempt])

  if (result.state === 'missing' && result.userId === userId)
    return <Navigate to="/seller/register" replace />
  const content = statusContent[result.shop?.status]
  return (
    <main className="account-page account-page--narrow">
      <nav className="account-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        <span aria-hidden="true">›</span>
        <span>Kênh người bán</span>
      </nav>
      <section className="account-card account-state">
        <span className="account-eyebrow">Kênh người bán</span>
        {result.state === 'loading' || result.userId !== userId ? (
          <p role="status">Đang kiểm tra gian hàng của bạn…</p>
        ) : result.state === 'error' ? (
          <>
            <h1>Chưa thể tải gian hàng</h1>
            <p role="alert">{result.error}</p>
            <button
              className="account-button"
              type="button"
              onClick={() => setAttempt((value) => value + 1)}
            >
              Thử lại
            </button>
          </>
        ) : !content ? (
          <>
            <h1>Chưa thể hiển thị trạng thái gian hàng</h1>
            <p>Vui lòng tải lại để kiểm tra trạng thái mới nhất.</p>
            <button
              className="account-button"
              type="button"
              onClick={() => setAttempt((value) => value + 1)}
            >
              Thử lại
            </button>
          </>
        ) : (
          <>
            <span className={`account-badge account-badge--${result.shop.status.toLowerCase()}`}>
              {content.label}
            </span>
            <h1>
              {result.shop.status === 'ACTIVE' && !result.sellerAllowed
                ? 'Quyền người bán đang được cập nhật'
                : content.title}
            </h1>
            <p>{content.message}</p>
            <div className="seller-details">
              <strong>{result.shop.name}</strong>
              {result.shop.description && <p>{result.shop.description}</p>}
              <span>{result.shop.province}</span>
            </div>
            {result.shop.statusReason && (
              <div className="account-notice account-notice--warning">
                <strong>Lý do từ quản trị viên</strong>
                <p>{result.shop.statusReason}</p>
              </div>
            )}
            {result.shop.status === 'ACTIVE' && !result.sellerAllowed && (
              <p className="account-notice account-notice--warning" role="status">
                Gian hàng đã được duyệt nhưng quyền người bán chưa được cập nhật. Hãy thử lại sau.
              </p>
            )}
            <div className="account-actions">
              <Link className="account-button account-button--secondary" to="/">
                Về trang mua sắm
              </Link>
              <button
                className="account-button account-button--secondary"
                type="button"
                onClick={() => setAttempt((value) => value + 1)}
              >
                Tải lại trạng thái
              </button>
            </div>
          </>
        )}
      </section>
    </main>
  )
}
