import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import AvatarDialog from './AvatarDialog'
import { AccountAvatar } from './AppHeader'
import './account.css'
import './accountForms.css'

const accountLinks = [
  { to: '/profile', label: 'Hồ sơ cá nhân' },
  { to: '/profile/addresses', label: 'Sổ địa chỉ' },
  { to: '/profile/password', label: 'Đổi mật khẩu' },
  { to: '/seller', label: 'Kênh người bán' },
]

// Shared frame of the account pages: breadcrumb, identity summary and section navigation.
export default function AccountLayout({ title, current, canChangeAvatar = false, children }) {
  const location = useLocation()
  const { session, userProfile, profileState, saveProfile } = useAuth()
  const [changingAvatar, setChangingAvatar] = useState(false)
  const displayName = userProfile?.fullName || session.username
  return (
    <main className="account-page">
      <nav className="account-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        <span aria-hidden="true">›</span>
        <span>{title}</span>
      </nav>
      {location.state?.message && (
        <p className="account-notice account-notice--warning" role="status">
          {location.state.message}
        </p>
      )}
      <div className="account-layout">
        <aside className="account-sidebar">
          <section className="account-card account-summary">
            <AccountAvatar name={displayName} avatarUrl={userProfile?.avatarUrl} large />
            {canChangeAvatar && userProfile && (
              <button
                className="link-button"
                type="button"
                disabled={profileState === 'loading'}
                onClick={() => setChangingAvatar(true)}
              >
                Đổi ảnh đại diện
              </button>
            )}
            <strong>{displayName}</strong>
            <span>{session.email}</span>
            <span className="account-badge">Email đã xác thực</span>
          </section>
          <nav className="account-card account-side-nav" aria-label="Tài khoản">
            {accountLinks.map((link) => (
              <Link
                key={link.to}
                to={link.to}
                aria-current={link.to === current ? 'page' : undefined}
              >
                {link.label}
              </Link>
            ))}
          </nav>
        </aside>
        {children}
      </div>
      {changingAvatar && (
        <AvatarDialog
          profile={userProfile}
          saveProfile={saveProfile}
          onClose={() => setChangingAvatar(false)}
        />
      )}
    </main>
  )
}
