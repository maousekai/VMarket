import { useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { getApiError } from '../services/api/apiClient'
import './delivery.css'

/**
 * Khung rieng cho khu vuc giao hang: khong co header mua sam va nut tro ly chatbot, vi tren dien
 * thoai hai phan nay che noi dung cua shipper (review PR 68).
 */
export default function ShipperLayout() {
  const { session, userProfile, logout } = useAuth()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const name = userProfile?.fullName || session?.username || session?.email

  async function onLogout() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await logout()
      navigate('/login', { replace: true, state: { toast: 'Bạn đã đăng xuất.' } })
    } catch (failure) {
      setError(getApiError(failure).message)
      setBusy(false)
    }
  }

  return (
    <>
      <header className="shipper-topbar">
        <Link to="/shipper" className="shipper-topbar__brand">
          VMarket <span>Giao hàng</span>
        </Link>
        <div className="shipper-topbar__account">
          <span className="shipper-topbar__name">{name}</span>
          {session?.roles?.includes('ADMIN') && (
            <Link className="shipper-topbar__link" to="/admin">
              Quản trị
            </Link>
          )}
          <button
            type="button"
            className="shipper-topbar__link"
            aria-disabled={busy}
            onClick={onLogout}
          >
            {busy ? 'Đang đăng xuất…' : 'Đăng xuất'}
          </button>
        </div>
      </header>
      {error && (
        <p className="account-notice account-notice--warning shipper-topbar__error" role="alert">
          {error} Chọn Đăng xuất để thử lại.
        </p>
      )}
      <Outlet />
    </>
  )
}
