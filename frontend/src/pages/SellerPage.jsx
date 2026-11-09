import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { getMyShop } from '../services/api/shopApi'
import { getApiError } from '../services/api/apiClient'
import ShopStatusBadge from '../components/ShopStatusBadge'
import ShopStepper from '../components/ShopStepper'
import { formatDate } from '../utils/formatDate'
import '../components/account.css'
import '../components/seller.css'

const POLL_INTERVAL = 15_000
const ERROR_BACKOFF = 30_000

const statusContent = {
  PENDING: {
    title: 'Hồ sơ gian hàng đang chờ duyệt',
    message:
      'VMarket đang kiểm tra hồ sơ của bạn. Bạn có thể tiếp tục chỉnh sửa thông tin trong lúc chờ.',
  },
  REJECTED: {
    title: 'Hồ sơ gian hàng chưa được duyệt',
    message: 'Xem lý do bên dưới, cập nhật hồ sơ rồi gửi lại để VMarket xét duyệt lần nữa.',
  },
  SUSPENDED: {
    title: 'Gian hàng đang bị đình chỉ',
    message: 'Gian hàng chưa thể tiếp tục hoạt động. Xem lý do và liên hệ VMarket nếu cần.',
  },
  ACTIVE: {
    title: 'Chào mừng đến kênh người bán',
    message: 'Gian hàng của bạn đã được duyệt trên VMarket.',
  },
}

export default function SellerPage() {
  const { session, refresh } = useAuth()
  const location = useLocation()
  const registered = location.state?.registered === true
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ state: 'loading', shop: null })
  const refreshAttempted = useRef(false)
  const userId = session?.userId
  const roleKey = session?.roles?.join(',') || ''

  useEffect(() => {
    let current = true
    let timer
    refreshAttempted.current = false
    setResult({ state: 'loading', shop: null, userId })

    async function load() {
      try {
        const shop = await getMyShop()
        if (!current) return
        let sellerAllowed = roleKey.split(',').includes('SELLER')
        if (shop.status === 'ACTIVE' && !sellerAllowed && !refreshAttempted.current) {
          refreshAttempted.current = true
          try {
            const freshSession = await refresh()
            sellerAllowed =
              freshSession?.userId === userId && freshSession.roles?.includes('SELLER')
          } catch {
            sellerAllowed = false
          }
        }
        if (!current) return
        setResult({ state: 'ready', shop, sellerAllowed, userId })
        if (shop.status === 'PENDING' && !document.hidden) {
          timer = setTimeout(load, POLL_INTERVAL)
        }
      } catch (error) {
        if (!current) return
        const failure = getApiError(error)
        setResult({
          state: failure.status === 404 && failure.code === 'SHOP_NOT_FOUND' ? 'missing' : 'error',
          shop: null,
          error: failure.message,
          userId,
        })
        if (failure.retryable) timer = setTimeout(load, ERROR_BACKOFF)
      }
    }

    function onVisibility() {
      if (document.visibilityState === 'visible') {
        clearTimeout(timer)
        load()
      }
    }

    load()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      current = false
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [userId, roleKey, refresh, attempt])

  const retry = () => setAttempt((value) => value + 1)

  if (result.state === 'missing' && result.userId === userId)
    return <Navigate to="/seller/register" replace />
  const shop = result.shop
  const content = statusContent[shop?.status]
  const isActive = shop?.status === 'ACTIVE'
  const sellerAllowed = result.sellerAllowed

  return (
    <section className="seller-page" aria-labelledby="seller-status-heading">
      <div className="seller-page__header">
        <span className="account-eyebrow">Kênh người bán</span>
        <h1 id="seller-status-heading">Gian hàng của bạn</h1>
        <p className="account-subtitle">
          Theo dõi tiến trình xét duyệt và chuẩn bị mọi thứ để bắt đầu bán hàng trên VMarket.
        </p>
      </div>

      {registered && (
        <p className="account-notice account-notice--success" role="status">
          Đã gửi đơn đăng ký gian hàng. Hồ sơ đang chờ VMarket xét duyệt.
        </p>
      )}

      {result.state === 'loading' || result.userId !== userId ? (
        <section className="account-card account-state">
          <p role="status">Đang kiểm tra gian hàng của bạn…</p>
        </section>
      ) : result.state === 'error' ? (
        <section className="account-card account-state">
          <h1>Chưa thể tải gian hàng</h1>
          <p role="alert">{result.error}</p>
          <button className="account-button" type="button" onClick={retry}>
            Thử lại
          </button>
        </section>
      ) : !content ? (
        <section className="account-card account-state">
          <h1>Chưa thể hiển thị trạng thái gian hàng</h1>
          <p>Vui lòng tải lại để kiểm tra trạng thái mới nhất.</p>
          <button className="account-button" type="button" onClick={retry}>
            Tải lại trạng thái
          </button>
        </section>
      ) : (
        <>
          <ShopStepper status={shop.status} />
          <section className="seller-status-card account-card">
            <div className="seller-status-card__top">
              <ShopStatusBadge status={shop.status} />
              {shop.updatedAt && (
                <span className="seller-status-card__updated">
                  Cập nhật {formatDate(shop.updatedAt)}
                </span>
              )}
            </div>
            <h2>
              {isActive && !sellerAllowed ? 'Quyền người bán đang được cập nhật' : content.title}
            </h2>
            <p>{content.message}</p>

            {shop.status === 'PENDING' && (
              <div className="seller-status-card__pending">
                <span className="seller-pulse" aria-hidden="true" />
                <span>Tự động cập nhật mỗi 15 giây khi trang đang mở.</span>
              </div>
            )}

            <dl className="seller-status-card__facts">
              <div>
                <dt>Gian hàng</dt>
                <dd>{shop.name}</dd>
              </div>
              <div>
                <dt>Khu vực</dt>
                <dd>{[shop.ward, shop.province].filter(Boolean).join(', ')}</dd>
              </div>
              <div>
                <dt>Liên hệ</dt>
                <dd>{shop.contactEmail}</dd>
              </div>
              {shop.approvedAt && (
                <div>
                  <dt>Hoạt động từ</dt>
                  <dd>{formatDate(shop.approvedAt)}</dd>
                </div>
              )}
            </dl>

            {shop.statusReason && (
              <div className="account-notice account-notice--warning">
                <strong>
                  {shop.status === 'REJECTED' ? 'Lý do cần bổ sung' : 'Lý do từ quản trị viên'}
                </strong>
                <p>{shop.statusReason}</p>
              </div>
            )}

            {isActive && !sellerAllowed && (
              <p className="account-notice account-notice--warning" role="status">
                Gian hàng đã được duyệt nhưng quyền người bán chưa được cập nhật. Hãy thử lại sau.
              </p>
            )}

            <div className="account-actions">
              {isActive && sellerAllowed && (
                <Link className="account-button" to="/seller/dashboard">
                  Vào dashboard
                </Link>
              )}
              {(shop.status === 'PENDING' ||
                shop.status === 'REJECTED' ||
                shop.status === 'ACTIVE') && (
                <Link className="account-button account-button--secondary" to="/seller/settings">
                  {shop.status === 'REJECTED' ? 'Chỉnh sửa & gửi lại' : 'Cài đặt gian hàng'}
                </Link>
              )}
              {shop.status === 'SUSPENDED' && (
                <Link className="account-button account-button--secondary" to="/seller/settings">
                  Xem hồ sơ
                </Link>
              )}
              <button
                className="account-button account-button--secondary"
                type="button"
                onClick={retry}
              >
                Tải lại trạng thái
              </button>
              <Link className="account-button account-button--secondary" to="/">
                Về trang mua sắm
              </Link>
            </div>
          </section>
        </>
      )}
    </section>
  )
}
