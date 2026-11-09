import { useEffect, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import ShopForm from '../components/ShopForm'
import ShopStatusBadge from '../components/ShopStatusBadge'
import ShopStepper from '../components/ShopStepper'
import { getApiError } from '../services/api/apiClient'
import {
  getMyShop,
  getMyStatusHistory,
  resubmitMyShop,
  updateMyShop,
} from '../services/api/shopApi'
import { formatDateTime } from '../utils/formatDate'
import { shopStatusLabels } from '../utils/shop'
import '../components/account.css'
import '../components/seller.css'

export default function SellerSettingsPage() {
  const [state, setState] = useState({ status: 'loading', shop: null, history: [], error: '' })
  const [notice, setNotice] = useState('')
  const [resubmitting, setResubmitting] = useState(false)
  const [resubmitError, setResubmitError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([getMyShop(), getMyStatusHistory()])
      .then(([shop, history]) => {
        if (current) setState({ status: 'ready', shop, history, error: '' })
      })
      .catch((failure) => {
        if (!current) return
        setState({
          status: 'error',
          shop: null,
          history: [],
          error: getApiError(failure).message,
          notFound:
            getApiError(failure).status === 404 && getApiError(failure).code === 'SHOP_NOT_FOUND',
        })
      })
    return () => {
      current = false
    }
  }, [reloadKey])

  async function onSave(body) {
    const shop = await updateMyShop(body)
    setState((previous) => ({ ...previous, shop }))
    setNotice('Đã lưu thay đổi hồ sơ gian hàng.')
    setReloadKey((key) => key + 1)
  }

  async function onResubmit() {
    if (resubmitting) return
    setResubmitting(true)
    setResubmitError('')
    try {
      const shop = await resubmitMyShop()
      setState((previous) => ({ ...previous, shop }))
      setNotice('Đã gửi lại hồ sơ. VMarket sẽ xét duyệt trong thời gian sớm nhất.')
      setReloadKey((key) => key + 1)
    } catch (failure) {
      setResubmitError(getApiError(failure).message)
    } finally {
      setResubmitting(false)
    }
  }

  if (state.status === 'loading') {
    return (
      <section className="seller-page">
        <p className="account-notice" role="status">
          Đang tải hồ sơ gian hàng…
        </p>
      </section>
    )
  }

  if (state.notFound) return <Navigate to="/seller/register" replace />

  if (state.status === 'error') {
    return (
      <section className="seller-page">
        <section className="account-card account-state">
          <h1>Chưa thể tải hồ sơ</h1>
          <p role="alert">{state.error}</p>
          <button
            className="account-button"
            type="button"
            onClick={() => setReloadKey((key) => key + 1)}
          >
            Thử lại
          </button>
        </section>
      </section>
    )
  }

  const { shop, history } = state
  const suspended = shop.status === 'SUSPENDED'
  const rejected = shop.status === 'REJECTED'

  return (
    <section className="seller-page" aria-labelledby="seller-settings-heading">
      <div className="seller-page__header seller-page__header--row">
        <div>
          <span className="account-eyebrow">Cài đặt gian hàng</span>
          <h1 id="seller-settings-heading">Hồ sơ gian hàng</h1>
          <p className="account-subtitle">
            Cập nhật thông tin hiển thị với người mua và thông tin vận hành của gian hàng.
          </p>
        </div>
        <div className="seller-page__header-actions">
          <ShopStatusBadge status={shop.status} />
          {shop.status === 'ACTIVE' && (
            <Link className="account-button account-button--secondary" to={`/shops/${shop.id}`}>
              Xem trang công khai
            </Link>
          )}
        </div>
      </div>

      <ShopStepper status={shop.status} />

      {suspended && (
        <p className="account-notice account-notice--error" role="alert">
          Gian hàng đang bị đình chỉ nên hồ sơ chỉ được xem, không thể chỉnh sửa.
          {shop.statusReason && ` Lý do: ${shop.statusReason}`}
        </p>
      )}
      {rejected && (
        <div className="account-notice account-notice--warning">
          <strong>Hồ sơ chưa được duyệt</strong>
          <p>{shop.statusReason}</p>
          <p>Chỉnh sửa nội dung bên dưới, lưu thay đổi rồi gửi lại hồ sơ.</p>
        </div>
      )}
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
      {resubmitError && (
        <p className="account-notice account-notice--error" role="alert">
          {resubmitError}
        </p>
      )}

      <section className="account-card seller-settings__form">
        <ShopForm
          key={`${shop.id}-${shop.updatedAt}`}
          initial={shop}
          disabled={suspended}
          onSubmit={onSave}
          submitLabel="Lưu thay đổi"
          actions={
            shop.status === 'ACTIVE' ? (
              <Link className="account-button account-button--secondary" to={`/shops/${shop.id}`}>
                Xem trang công khai
              </Link>
            ) : null
          }
          notice={
            <p className="account-field__hint">
              Thay đổi thông tin của gian hàng đang hoạt động không làm gian hàng quay lại trạng
              thái chờ duyệt.
            </p>
          }
        />
        {rejected && (
          <div className="seller-settings__resubmit">
            <div>
              <strong>Sẵn sàng gửi lại?</strong>
              <p>Hồ sơ sẽ chuyển về “Chờ duyệt” và không thể sửa trong lúc chờ.</p>
            </div>
            <button
              className="account-button"
              type="button"
              disabled={resubmitting}
              onClick={onResubmit}
            >
              {resubmitting ? 'Đang gửi lại…' : 'Gửi lại hồ sơ'}
            </button>
          </div>
        )}
      </section>

      <section className="account-card seller-history" aria-labelledby="seller-history-heading">
        <div className="seller-history__header">
          <h2 id="seller-history-heading">Lịch sử xét duyệt</h2>
          <span>{history.length} mốc</span>
        </div>
        {history.length === 0 ? (
          <p className="account-notice">Chưa có lịch sử trạng thái.</p>
        ) : (
          <ol className="seller-timeline">
            {[...history].reverse().map((item, index) => (
              <li key={`${item.createdAt}-${item.toStatus}-${index}`}>
                <span
                  className={`seller-timeline__dot seller-timeline__dot--${item.toStatus?.toLowerCase()}`}
                />
                <div>
                  <strong>
                    {item.fromStatus
                      ? `${shopStatusLabels[item.fromStatus] || item.fromStatus} → ${
                          shopStatusLabels[item.toStatus] || item.toStatus
                        }`
                      : `Nộp hồ sơ (${shopStatusLabels[item.toStatus] || item.toStatus})`}
                  </strong>
                  {item.reason && <p>{item.reason}</p>}
                  <small>{formatDateTime(item.createdAt)}</small>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    </section>
  )
}
