import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { adminSections } from '../pages/adminSections'
import { getApiError } from '../services/api/apiClient'
import brandBasket from '../assets/account/brand-basket.svg'
import { AccountAvatar } from './AppHeader'
import AdminIcon from './AdminIcons'
import './adminLayout.css'

export default function AdminLayout() {
  const { session, userProfile, logout } = useAuth()
  const navigate = useNavigate()
  const menuRef = useRef(null)
  const menuButtonRef = useRef(null)
  const [navOpen, setNavOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')
  const name = userProfile?.fullName || session.username

  useEffect(() => {
    if (!navOpen) return
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setNavOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [navOpen])

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
    setLogoutError('')
    try {
      await logout()
      setMenuOpen(false)
      navigate('/login', { replace: true, state: { message: 'Bạn đã đăng xuất.' } })
    } catch (error) {
      setLogoutError(getApiError(error).message)
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <div className="admin-shell">
      <aside
        className={`admin-sidebar${navOpen ? ' is-open' : ''}`}
        id="admin-sidebar"
        aria-label="Thanh bên quản trị"
      >
        <div className="admin-sidebar__brand">
          <Link to="/admin" className="admin-sidebar__logo" onClick={() => setNavOpen(false)}>
            <span className="admin-sidebar__logo-icon">
              <img src={brandBasket} alt="" />
            </span>
            <strong>VMarket</strong>
          </Link>
          <span className="admin-sidebar__badge">Admin</span>
        </div>
        <p className="admin-sidebar__label">{'ĐIỀU HÀNH & QUẢN TRỊ'}</p>
        <nav className="admin-nav" aria-label="Điều hướng quản trị">
          {adminSections.map((section) => (
            <NavLink
              key={section.to}
              to={section.to}
              end={section.path === ''}
              className={({ isActive }) => `admin-nav__link${isActive ? ' is-active' : ''}`}
              onClick={() => setNavOpen(false)}
            >
              <AdminIcon name={section.icon} />
              <span>{section.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="admin-sidebar__status">
          <span className="admin-sidebar__dot" aria-hidden="true" />
          <span>
            <strong>Hệ thống trực tuyến</strong>
            <small>Đà Nẵng Hub</small>
          </span>
        </div>
      </aside>
      {navOpen && (
        <button
          className="admin-backdrop"
          type="button"
          aria-label="Đóng menu"
          onClick={() => setNavOpen(false)}
        />
      )}

      <div className="admin-body">
        <header className="admin-topbar">
          <div className="admin-topbar__left">
            <button
              className="admin-topbar__menu"
              type="button"
              aria-label="Mở menu"
              aria-expanded={navOpen}
              aria-controls="admin-sidebar"
              onClick={() => setNavOpen(!navOpen)}
            >
              <AdminIcon name="menu" size={20} />
            </button>
            <span className="admin-topbar__dot" aria-hidden="true" />
            <strong>VMarket Operations Console</strong>
          </div>
          <div className="admin-topbar__right">
            <label className="admin-topbar__search">
              <AdminIcon name="search" size={14} />
              <input
                type="search"
                aria-label="Tìm mã đơn"
                placeholder="Tìm mã đơn…"
                disabled
                title="Chưa khả dụng"
              />
            </label>
            <button
              className="admin-topbar__bell"
              type="button"
              aria-label="Thông báo"
              disabled
              title="Chưa khả dụng"
            >
              <AdminIcon name="bell" size={16} />
            </button>
            <span className="admin-topbar__divider" aria-hidden="true" />
            <div className="admin-user" ref={menuRef}>
              <button
                ref={menuButtonRef}
                className="admin-user__trigger"
                type="button"
                aria-label={`Tài khoản của ${name}`}
                aria-expanded={menuOpen}
                aria-controls="admin-user-menu"
                onClick={() => {
                  setMenuOpen(!menuOpen)
                  setLogoutError('')
                }}
              >
                <AccountAvatar name={name} avatarUrl={userProfile?.avatarUrl} />
                <span className="admin-user__text">
                  <strong>{name}</strong>
                  <small>Quản trị viên</small>
                </span>
                <AdminIcon name="chevron" size={14} />
              </button>
              {menuOpen && (
                <div
                  className="admin-user__panel"
                  id="admin-user-menu"
                  role="group"
                  aria-label="Tài khoản"
                  onKeyDown={onMenuKeyDown}
                >
                  <div className="admin-user__identity">
                    <strong>{name}</strong>
                    <span>{session.email}</span>
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
                  {logoutError && (
                    <p className="admin-user__error" role="alert">
                      {logoutError} Chọn Đăng xuất để thử lại.
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>
        </header>
        <main className="admin-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
