import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { getApiError } from '../services/api/apiClient'
import brandBasket from '../assets/account/brand-basket.svg'
import chevronDown from '../assets/account/chevron-down.svg'
import './account.css'

function initials(name) {
  const words = name.trim().split(/\s+/).filter(Boolean)
  return (
    words
      .slice(-2)
      .map((word) => word[0])
      .join('')
      .toLocaleUpperCase('vi') || 'VM'
  )
}

function AvatarImage({ src, name }) {
  const [failed, setFailed] = useState(false)
  if (!src || !/^https?:\/\//i.test(src) || failed) {
    return <span aria-hidden="true">{initials(name)}</span>
  }
  return <img src={src} alt="" onError={() => setFailed(true)} />
}

export function AccountAvatar({ name, avatarUrl, large = false }) {
  return (
    <span className={`account-avatar${large ? ' account-avatar--large' : ''}`}>
      <AvatarImage key={avatarUrl || 'initials'} src={avatarUrl} name={name} />
    </span>
  )
}

export default function AppHeader() {
  const { session, bootstrapState, userProfile, logout } = useAuth()
  const navigate = useNavigate()
  const menuRef = useRef(null)
  const buttonRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')
  const name = userProfile?.fullName || session?.username || 'Tài khoản'
  const active = session?.status === 'ACTIVE'

  useEffect(() => {
    if (!open) return
    const closeOutside = (event) => {
      if (!menuRef.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  function openMenu(focusLast = false) {
    setOpen(true)
    // Wait for the newly rendered disclosure before moving keyboard focus.
    requestAnimationFrame(() => {
      const items = menuRef.current?.querySelectorAll('[role="menuitem"]')
      items?.[focusLast ? items.length - 1 : 0]?.focus()
    })
  }

  function onMenuKeyDown(event) {
    if (event.key === 'Escape') {
      event.preventDefault()
      setOpen(false)
      buttonRef.current?.focus()
      return
    }
    if (event.key === 'Tab') {
      setOpen(false)
      return
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const items = [...menuRef.current.querySelectorAll('[role="menuitem"]')]
    const current = items.indexOf(document.activeElement)
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? items.length - 1
          : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
    items[next]?.focus()
  }

  async function onLogout() {
    if (loggingOut) return
    setLoggingOut(true)
    setLogoutError('')
    try {
      await logout()
      setOpen(false)
      navigate('/login', { replace: true, state: { message: 'Bạn đã đăng xuất.' } })
    } catch (error) {
      setLogoutError(getApiError(error).message)
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <header className="app-header">
      <div className="app-header__inner">
        <Link className="app-header__brand" to="/" aria-label="VMarket — Trang chủ">
          <span className="app-header__brand-icon">
            <img src={brandBasket} alt="" />
          </span>
          <span>
            <strong>VMarket</strong>
            <small>TP. ĐÀ NẴNG</small>
          </span>
        </Link>
        <nav className="app-header__navigation" aria-label="Điều hướng chính">
          <Link to="/">Trang chủ</Link>
        </nav>
        {bootstrapState === 'loading' ? (
          <span className="app-header__pending" role="status">
            Đang khôi phục phiên…
          </span>
        ) : session ? (
          <div className="account-menu" ref={menuRef}>
            <button
              ref={buttonRef}
              type="button"
              className="account-menu__trigger"
              aria-label={`Tài khoản của ${name}`}
              aria-haspopup="menu"
              aria-expanded={open}
              aria-controls="account-dropdown"
              onClick={() => {
                setOpen(!open)
                setLogoutError('')
              }}
              onKeyDown={(event) => {
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                  event.preventDefault()
                  openMenu(event.key === 'ArrowUp')
                }
              }}
            >
              <AccountAvatar name={name} avatarUrl={userProfile?.avatarUrl} />
              <span className="account-menu__name">{name}</span>
              <img className="account-menu__chevron" src={chevronDown} alt="" />
            </button>
            {open && (
              <div
                className="account-menu__panel"
                id="account-dropdown"
                role="menu"
                aria-label="Tài khoản"
                onKeyDown={onMenuKeyDown}
              >
                <div className="account-menu__identity" role="presentation">
                  <strong>{name}</strong>
                  <span>{session.email}</span>
                </div>
                {active ? (
                  <>
                    <Link role="menuitem" to="/profile" onClick={() => setOpen(false)}>
                      Hồ sơ cá nhân
                    </Link>
                    <Link role="menuitem" to="/seller" onClick={() => setOpen(false)}>
                      Kênh người bán
                    </Link>
                    {session.roles.includes('ADMIN') && (
                      <Link role="menuitem" to="/admin" onClick={() => setOpen(false)}>
                        Khu vực quản trị
                      </Link>
                    )}
                    {session.roles.includes('SHIPPER') && (
                      <Link role="menuitem" to="/shipper" onClick={() => setOpen(false)}>
                        Khu vực giao hàng
                      </Link>
                    )}
                  </>
                ) : (
                  <Link role="menuitem" to="/verify-email" onClick={() => setOpen(false)}>
                    Xác thực email
                  </Link>
                )}
                <button
                  className="account-menu__logout"
                  role="menuitem"
                  type="button"
                  aria-disabled={loggingOut}
                  onClick={onLogout}
                >
                  {loggingOut ? 'Đang đăng xuất…' : 'Đăng xuất'}
                </button>
                {logoutError && (
                  <p className="account-menu__error" role="alert">
                    {logoutError} Chọn Đăng xuất để thử lại.
                  </p>
                )}
              </div>
            )}
          </div>
        ) : (
          <div className="app-header__auth-links">
            <Link to="/login">Đăng nhập</Link>
            <Link className="app-header__register" to="/register">
              Đăng ký
            </Link>
          </div>
        )}
      </div>
    </header>
  )
}
