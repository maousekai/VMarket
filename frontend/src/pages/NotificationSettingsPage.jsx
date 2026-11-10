import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'
import { getApiError } from '../services/api/apiClient'
import {
  getNotificationPreferences,
  updateNotificationPreferences,
} from '../services/api/notificationApi'
import { isMobileDevice } from '../utils/device'
import '../components/orders.css'
import '../components/notifications.css'

const DESCRIPTIONS = {
  ORDER: 'Đặt hàng, xác nhận, giao hàng, huỷ đơn và đơn mới của gian hàng.',
  PAYMENT: 'Thanh toán thành công / thất bại và kết quả trả hàng, hoàn tiền.',
  SHOP: 'Duyệt, đình chỉ gian hàng và kiểm duyệt sản phẩm.',
}

/**
 * Bat / tat email va push theo nhom; thong bao trong ung dung luon duoc giu. Push chi gui toi dien
 * thoai nen cot "Thong bao day" chi hien khi mo tren dien thoai.
 */
export default function NotificationSettingsPage() {
  const [mobile] = useState(isMobileDevice)
  const channels = mobile ? ['email', 'push'] : ['email']
  const [state, setState] = useState({ status: 'loading', categories: [], error: '' })
  const [saved, setSaved] = useState(null)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState({ type: '', text: '' })
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let current = true
    getNotificationPreferences()
      .then((data) => {
        if (!current) return
        setState({ status: 'ready', categories: data.categories, error: '' })
        setSaved(data.categories)
      })
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', categories: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [reload])

  const dirty = JSON.stringify(state.categories) !== JSON.stringify(saved)

  function toggle(key, channel) {
    setMessage({ type: '', text: '' })
    setState((value) => ({
      ...value,
      categories: value.categories.map((item) =>
        item.key === key ? { ...item, [channel]: !item[channel] } : item,
      ),
    }))
  }

  async function save(event) {
    event.preventDefault()
    setSaving(true)
    setMessage({ type: '', text: '' })
    try {
      const data = await updateNotificationPreferences(state.categories)
      setState({ status: 'ready', categories: data.categories, error: '' })
      setSaved(data.categories)
      setMessage({ type: 'success', text: 'Đã lưu cài đặt thông báo.' })
    } catch (failure) {
      setMessage({ type: 'error', text: getApiError(failure).message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <AccountLayout title="Cài đặt thông báo" current="/profile/notifications">
      <section className="orders-page" aria-labelledby="notification-settings-title">
        <div className="orders-page__head">
          <div>
            <Link className="link-button" to="/profile/notifications">
              ‹ Thông báo
            </Link>
            <h1 id="notification-settings-title">Cài đặt thông báo</h1>
            <p className="account-subtitle">
              {mobile
                ? 'Chọn nhận email và thông báo đẩy trên điện thoại cho từng nhóm.'
                : 'Chọn nhận email cho từng nhóm. Thông báo đẩy được cài đặt khi bạn mở VMarket trên điện thoại.'}{' '}
              Thông báo trong VMarket luôn được lưu để bạn xem lại.
            </p>
          </div>
        </div>
        {state.status === 'loading' && (
          <p className="account-notice" role="status">
            Đang tải cài đặt…
          </p>
        )}
        {state.status === 'error' && (
          <div className="account-card account-state">
            <p role="alert">{state.error}</p>
            <button
              className="account-button"
              type="button"
              onClick={() => setReload((value) => value + 1)}
            >
              Thử lại
            </button>
          </div>
        )}
        {state.status === 'ready' && (
          <form className="account-card notification-settings" onSubmit={save}>
            <table className="notification-settings__table">
              <caption className="visually-hidden">Kênh nhận thông báo theo nhóm</caption>
              <thead>
                <tr>
                  <th scope="col">Nhóm thông báo</th>
                  <th scope="col">Email</th>
                  {mobile && <th scope="col">Thông báo đẩy</th>}
                </tr>
              </thead>
              <tbody>
                {state.categories.map((item) => (
                  <tr key={item.key}>
                    <th scope="row">
                      <strong>{item.label}</strong>
                      <small>{DESCRIPTIONS[item.key]}</small>
                    </th>
                    {channels.map((channel) => (
                      <td key={channel}>
                        <label className="notification-switch">
                          <input
                            type="checkbox"
                            role="switch"
                            checked={item[channel]}
                            aria-label={`${channel === 'email' ? 'Email' : 'Thông báo đẩy'} – ${item.label}`}
                            onChange={() => toggle(item.key, channel)}
                          />
                          <span aria-hidden="true" />
                        </label>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="order-detail__hint">
              Email bảo mật (mã xác thực, đặt lại mật khẩu) luôn được gửi.
            </p>
            {message.text && (
              <p
                className={`form-message form-message--${message.type}`}
                role={message.type === 'error' ? 'alert' : 'status'}
              >
                {message.text}
              </p>
            )}
            <div className="account-actions">
              <button type="submit" className="account-button" disabled={!dirty || saving}>
                {saving ? 'Đang lưu…' : 'Lưu cài đặt'}
              </button>
            </div>
          </form>
        )}
      </section>
    </AccountLayout>
  )
}
