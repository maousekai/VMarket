import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'
import Pagination from '../components/Pagination'
import { getApiError } from '../services/api/apiClient'
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationRoute,
} from '../services/api/notificationApi'
import { announceNotificationsChanged, useReloadOnNotification } from '../notifications/events'
import { formatDateTime } from '../utils/formatDate'
import '../components/orders.css'
import '../components/notifications.css'

const PAGE_SIZE = 15
const TABS = [
  [false, 'Tất cả'],
  [true, 'Chưa đọc'],
]

/** Trung tam thong bao: toan bo lich su, loc chua doc, danh dau da doc, mo trang lien quan. */
export default function NotificationsPage() {
  const navigate = useNavigate()
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [actionError, setActionError] = useState('')

  useReloadOnNotification(() => setReload((value) => value + 1))

  useEffect(() => {
    let current = true
    setState((value) => ({ ...value, status: 'loading', error: '' }))
    listNotifications({ page, size: PAGE_SIZE, unreadOnly })
      .then((result) => current && setState({ status: 'ready', result, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', result: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [page, unreadOnly, reload])

  const items = state.result?.items || []

  function markLocal(update) {
    setState((value) =>
      value.result
        ? { ...value, result: { ...value.result, items: value.result.items.map(update) } }
        : value,
    )
  }

  async function open(item) {
    setActionError('')
    if (!item.read) {
      markLocal((entry) => (entry.id === item.id ? { ...entry, read: true } : entry))
      try {
        await markNotificationRead(item.id)
      } catch (failure) {
        setActionError(getApiError(failure).message)
      }
      announceNotificationsChanged()
    }
    const route = notificationRoute(item.link)
    if (route) navigate(route)
  }

  async function readAll() {
    setActionError('')
    try {
      await markAllNotificationsRead()
      if (unreadOnly) setReload((value) => value + 1)
      else markLocal((entry) => ({ ...entry, read: true }))
    } catch (failure) {
      setActionError(getApiError(failure).message)
    }
    announceNotificationsChanged()
  }

  return (
    <AccountLayout title="Thông báo" current="/profile/notifications">
      <section className="orders-page" aria-labelledby="notifications-title">
        <div className="orders-page__head">
          <div>
            <h1 id="notifications-title">Thông báo</h1>
            <p className="account-subtitle">
              Cập nhật về đơn hàng, thanh toán, giao hàng và gian hàng của bạn.
            </p>
          </div>
          <Link
            className="account-button account-button--secondary"
            to="/profile/notifications/settings"
          >
            Cài đặt thông báo
          </Link>
        </div>
        <div className="account-card notification-center">
          <div className="notification-center__toolbar">
            <div className="order-tabs" role="group" aria-label="Lọc thông báo">
              {TABS.map(([value, label]) => (
                <button
                  key={label}
                  type="button"
                  aria-pressed={unreadOnly === value}
                  onClick={() => {
                    setUnreadOnly(value)
                    setPage(0)
                  }}
                >
                  {label}
                </button>
              ))}
            </div>
            <button type="button" className="link-button" onClick={readAll}>
              Đánh dấu đã đọc tất cả
            </button>
          </div>
          {actionError && (
            <p className="form-message form-message--error" role="alert">
              {actionError}
            </p>
          )}
          {state.status === 'loading' && !state.result && (
            <p className="notification-bell__state" role="status">
              Đang tải thông báo…
            </p>
          )}
          {state.status === 'error' && (
            <div className="account-state">
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
          {state.status === 'ready' && !items.length && (
            <p className="notification-bell__state">
              {unreadOnly ? 'Bạn đã đọc hết thông báo.' : 'Bạn chưa có thông báo nào.'}
            </p>
          )}
          {items.length > 0 && (
            <ul className="notification-bell__list notification-center__list">
              {items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    className={`notification-bell__item${item.read ? '' : ' is-unread'}`}
                    onClick={() => open(item)}
                  >
                    <strong>{item.title}</strong>
                    {item.message && <span>{item.message}</span>}
                    <small>
                      {formatDateTime(item.createdAt)}
                      {!item.read && ' · Chưa đọc'}
                    </small>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <Pagination
          page={state.result?.page ?? page}
          totalPages={state.result?.totalPages ?? 0}
          onChange={setPage}
        />
      </section>
    </AccountLayout>
  )
}
