import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Navigate, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { AccountAvatar } from './AppHeader'
import { getApiError } from '../services/api/apiClient'
import { getMyShop } from '../services/api/shopApi'
import brandBasket from '../assets/account/brand-basket.svg'
import './account.css'
import './seller.css'

function SellerIcon({ name }) {
  const paths = {
    overview: 'M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6V11h-6v9Zm0-16v5h6V4h-6Z',
    settings:
      'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm9 4-2.1-.7a7 7 0 0 0-.6-1.4l1-2-2.2-2.2-2 1a7 7 0 0 0-1.4-.6L13 4h-2l-.7 2.1a7 7 0 0 0-1.4.6l-2-1L4.7 7.9l1 2a7 7 0 0 0-.6 1.4L3 12v2l2.1.7a7 7 0 0 0 .6 1.4l-1 2 2.2 2.2 2-1a7 7 0 0 0 1.4.6L11 22h2l.7-2.1a7 7 0 0 0 1.4-.6l2 1 2.2-2.2-1-2a7 7 0 0 0 .6-1.4L21 14v-2Z',
    store: 'M4 4h16l1 6H3l1-6Zm1 8h14v8H5v-8Zm3 2v4h3v-4H8Z',
    home: 'm12 3 9 8h-3v9h-5v-6h-2v6H6v-9H3l9-8Z',
  }
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="currentColor">
      <path d={paths[name]} />
    </svg>
  )
}

export default function SellerLayout() {
  const { session, userProfile, logout } = useAuth()
  const navigate = useNavigate()
  const menuRef = useRef(null)
  const menuButtonRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [error, setError] = useState('')
  const [gate, setGate] = useState({ state: 'loading', error: '' })
  const [gateAttempt, setGateAttempt] = useState(0)
  const userId = session?.userId
  const name = userProfile?.fullName || session?.username || 'Người bán'

  // Do not paint the seller shell before we know a shop exists. Otherwise a buyer
  // without a shop sees the sidebar/dashboard chrome for a moment before the
  // nested page's 404 redirect happens.
  useEffect(() => {
    if (!userId) return undefined
    let current = true
    setGate({ state: 'loading', error: '' })
    getMyShop()
      .then(() => {
        if (current) setGate({ state: 'ready', error: '' })
      })
      .catch((failure) => {
        if (!current) return
        const apiError = getApiError(failure)
        setGate({
          state:
            apiError.status === 404 && apiError.code === 'SHOP_NOT_FOUND' ? 'missing' : 'error',
          error: apiError.message,
        })
      })
    return () => {
      current = false
    }
  }, [userId, gateAttempt])

  useEffect(() => {
    if (!menuOpen) return
    const closeOutside = (event) => {
      if (!menuRef.current?.contains(event.target)) setMenuOpen(false)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [menuOpen])

  function onMenuKeyDown(event) {
    if (event.key !== 'Escape') return
    event.preventDefault()
    setMenuOpen(false)
    menuButtonRef.current?.focus()
  }

  async function onLogout() {
    if (loggingOut) return
    setLoggingOut(true)
    setError('')
    try {
      await logout()
      setMenuOpen(false)
      navigate('/login', { replace: true, state: { toast: 'Bạn đã đăng xuất.' } })
    } catch (failure) {
      setError(getApiError(failure).message)
      setLoggingOut(false)
    }
  }

  if (gate.state === 'loading') {
    return (
      <main className="account-page">
        <p className="account-notice" role="status">
          Đang kiểm tra gian hàng của bạn…
        </p>
      </main>
    )
  }

  if (gate.state === 'missing') return <Navigate to="/seller/register" replace />

  if (gate.state === 'error') {
    return (
      <main className="account-page account-page--narrow">
        <section className="account-card account-state">
          <h1>Chưa thể tải gian hàng</h1>
          <p role="alert">{gate.error}</p>
          <div className="account-actions">
            <button
              className="account-button"
              type="button"
              onClick={() => setGateAttempt((value) => value + 1)}
            >
              Thử lại
            </button>
            <Link className="account-button account-button--secondary" to="/">
              Về trang mua sắm
            </Link>
          </div>
        </section>
      </main>
    )
  }

  return (
    <div className="seller-shell">
      <aside className={`seller-sidebar${open ? ' is-open' : ''}`} aria-label="Kênh người bán">
        <Link className="seller-sidebar__brand" to="/seller" onClick={() => setOpen(false)}>
          <span className="seller-sidebar__logo">
            <img src={brandBasket} alt="" />
          </span>
          <span>
            <strong>VMarket</strong>
            <small>Kênh người bán</small>
          </span>
        </Link>
        <nav className="seller-nav" aria-label="Điều hướng kênh người bán">
          <NavLink
            to="/seller"
            end
            className={({ isActive }) => `seller-nav__link${isActive ? ' is-active' : ''}`}
            onClick={() => setOpen(false)}
          >
            <SellerIcon name="store" />
            <span>Trạng thái gian hàng</span>
          </NavLink>
          <NavLink
            to="/seller/dashboard"
            className={({ isActive }) => `seller-nav__link${isActive ? ' is-active' : ''}`}
            onClick={() => setOpen(false)}
          >
            <SellerIcon name="overview" />
            <span>Tổng quan bán hàng</span>
          </NavLink>
          <NavLink
            to="/seller/settings"
            className={({ isActive }) => `seller-nav__link${isActive ? ' is-active' : ''}`}
            onClick={() => setOpen(false)}
          >
            <SellerIcon name="settings" />
            <span>Cài đặt gian hàng</span>
          </NavLink>
          <Link className="seller-nav__link" to="/" onClick={() => setOpen(false)}>
            <SellerIcon name="home" />
            <span>Về trang mua sắm</span>
          </Link>
        </nav>
        <div className="seller-sidebar__tip">
          <strong>Cần hỗ trợ?</strong>
          <p>Gửi yêu cầu cho VMarket để được hướng dẫn vận hành gian hàng.</p>
        </div>
      </aside>
      {open && (
        <button
          type="button"
          className="seller-backdrop"
          aria-label="Đóng menu"
          onClick={() => setOpen(false)}
        />
      )}
      <div className="seller-body">
        <header className="seller-topbar">
          <div className="seller-topbar__left">
            <button
              type="button"
              className="seller-topbar__menu"
              aria-label="Mở menu"
              aria-expanded={open}
              onClick={() => setOpen(!open)}
            >
              ☰
            </button>
            <span className="seller-topbar__eyebrow">Không gian người bán</span>
          </div>
          <div className="seller-user" ref={menuRef}>
            <button
              ref={menuButtonRef}
              className="seller-user__trigger"
              type="button"
              aria-label={`Tài khoản của ${name}`}
              aria-expanded={menuOpen}
              aria-controls="seller-user-menu"
              onClick={() => {
                setMenuOpen(!menuOpen)
                setError('')
              }}
            >
              <AccountAvatar name={name} avatarUrl={userProfile?.avatarUrl} />
              <span className="seller-user__text">
                <strong>{name}</strong>
                <small>{session?.roles?.includes('SELLER') ? 'Người bán' : 'Chủ gian hàng'}</small>
              </span>
              <svg
                className="seller-user__chevron"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                aria-hidden="true"
              >
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>
            {menuOpen && (
              <div
                className="seller-user__panel"
                id="seller-user-menu"
                role="group"
                aria-label="Tài khoản"
                onKeyDown={onMenuKeyDown}
              >
                <div className="seller-user__identity">
                  <strong>{name}</strong>
                  <span>{session?.email}</span>
                </div>
                <Link to="/profile" onClick={() => setMenuOpen(false)}>
                  Hồ sơ cá nhân
                </Link>
                <Link to="/" onClick={() => setMenuOpen(false)}>
                  Về trang chủ
                </Link>
                <button type="button" disabled={loggingOut} onClick={onLogout}>
                  {loggingOut ? 'Đang đăng xuất…' : 'Đăng xuất'}
                </button>
                {error && (
                  <p className="seller-user__error" role="alert">
                    {error} Chọn Đăng xuất để thử lại.
                  </p>
                )}
              </div>
            )}
          </div>
        </header>
        <main className="seller-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
