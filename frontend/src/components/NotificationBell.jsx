import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import {
  getUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationRoute,
} from '../services/api/notificationApi'
import { formatDateTime } from '../utils/formatDate'
import './accountForms.css'
import './notifications.css'

const POLL_MS = 60_000
const PAGE_SIZE = 10

/** Chuong thong bao: so chua doc + danh sach moi nhat, bam de mo trang lien quan. */
export default function NotificationBell({ variant = 'header' }) {
  const navigate = useNavigate()
  const rootRef = useRef(null)
  const buttonRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [unread, setUnread] = useState(0)
  const [list, setList] = useState({ status: 'idle', items: [], error: '' })

  const refreshCount = useCallback(
    () =>
      getUnreadCount()
        .then(setUnread)
        .catch(() => {}),
    [],
  )

  useEffect(() => {
    refreshCount()
    const timer = setInterval(refreshCount, POLL_MS)
    const onVisible = () => document.visibilityState === 'visible' && refreshCount()
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [refreshCount])

  useEffect(() => {
    if (!open) return undefined
    let current = true
    setList((value) => ({ ...value, status: 'loading', error: '' }))
    listNotifications({ size: PAGE_SIZE })
      .then((page) => current && setList({ status: 'ready', items: page.items || [], error: '' }))
      .catch(
        (failure) =>
          current && setList({ status: 'error', items: [], error: getApiError(failure).message }),
      )
    refreshCount()
    const closeOutside = (event) => {
      if (!rootRef.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => {
      current = false
      document.removeEventListener('pointerdown', closeOutside)
    }
  }, [open, refreshCount])

  function onKeyDown(event) {
    if (event.key !== 'Escape') return
    event.preventDefault()
    setOpen(false)
    buttonRef.current?.focus()
  }

  async function openItem(item) {
    if (!item.read) {
      setList((value) => ({
        ...value,
        items: value.items.map((entry) =>
          entry.id === item.id ? { ...entry, read: true } : entry,
        ),
      }))
      setUnread((count) => Math.max(count - 1, 0))
      markNotificationRead(item.id).catch(() => refreshCount())
    }
    const route = notificationRoute(item.link)
    if (route) {
      setOpen(false)
      navigate(route)
    }
  }

  async function readAll() {
    setList((value) => ({
      ...value,
      items: value.items.map((entry) => ({ ...entry, read: true })),
    }))
    setUnread(0)
    try {
      await markAllNotificationsRead()
    } catch {
      refreshCount()
    }
  }

  return (
    <div className={`notification-bell notification-bell--${variant}`} ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className="notification-bell__trigger"
        aria-label={unread ? `Thông báo, ${unread} chưa đọc` : 'Thông báo'}
        aria-expanded={open}
        aria-controls="notification-panel"
        onClick={() => setOpen(!open)}
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M6 9a6 6 0 1 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9Zm4 10a2 2 0 0 0 4 0"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        {unread > 0 && (
          <span className="notification-bell__badge">{unread > 99 ? '99+' : unread}</span>
        )}
      </button>
      {open && (
        <div
          className="notification-bell__panel"
          id="notification-panel"
          role="dialog"
          aria-label="Thông báo"
          onKeyDown={onKeyDown}
        >
          <div className="notification-bell__head">
            <strong>Thông báo</strong>
            {unread > 0 && (
              <button type="button" className="link-button" onClick={readAll}>
                Đánh dấu đã đọc tất cả
              </button>
            )}
          </div>
          {list.status === 'loading' && !list.items.length && (
            <p className="notification-bell__state" role="status">
              Đang tải thông báo…
            </p>
          )}
          {list.status === 'error' && (
            <p className="notification-bell__state" role="alert">
              {list.error}
            </p>
          )}
          {list.status === 'ready' && !list.items.length && (
            <p className="notification-bell__state">Bạn chưa có thông báo nào.</p>
          )}
          {list.items.length > 0 && (
            <ul className="notification-bell__list">
              {list.items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    className={`notification-bell__item${item.read ? '' : ' is-unread'}`}
                    onClick={() => openItem(item)}
                  >
                    <strong>{item.title}</strong>
                    {item.message && <span>{item.message}</span>}
                    <small>{formatDateTime(item.createdAt)}</small>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
