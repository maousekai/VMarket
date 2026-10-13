// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import AppHeader from './AppHeader'
import RouteGuard from './RouteGuard'
import ProfilePage from '../pages/ProfilePage'
import SellerPage from '../pages/SellerPage'
import { getMyShop } from '../services/api/shopApi'

vi.mock('../services/api/shopApi', () => ({ getMyShop: vi.fn() }))

const activeSession = {
  userId: 'buyer-one',
  username: 'buyer',
  email: 'buyer@example.com',
  roles: ['BUYER'],
  status: 'ACTIVE',
}
const profile = {
  userId: 'buyer-one',
  fullName: 'Nguyễn Minh Tuấn',
  phone: '0905123456',
  avatarUrl: null,
  dateOfBirth: '1998-08-12',
  gender: 'UNDISCLOSED',
}
const apiFailure = (status, code, message) => ({
  response: { status, data: { error: { code, message } } },
})

function auth(overrides = {}) {
  return {
    session: activeSession,
    bootstrapState: 'ready',
    userProfile: profile,
    profileState: 'ready',
    loadProfile: vi.fn(),
    saveProfile: vi.fn().mockResolvedValue(profile),
    logout: vi.fn().mockResolvedValue(undefined),
    refresh: vi.fn().mockResolvedValue(activeSession),
    ...overrides,
  }
}

function LocationTarget({ name }) {
  const location = useLocation()
  return (
    <p>
      {name}: {location.state?.returnTo}
    </p>
  )
}

function AppView({ value, children, path = '/' }) {
  return (
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<LocationTarget name="login" />} />
          <Route path="/verify-email" element={<LocationTarget name="verify" />} />
          <Route path="/seller/register" element={<p>seller intro</p>} />
          <Route path="*" element={children} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('Account route guard', () => {
  it('keeps protected contents hidden during restoration and denies an unauthorized role', () => {
    const view = render(
      <AppView value={auth({ bootstrapState: 'loading' })}>
        <RouteGuard roles={['ADMIN']}>
          <p>secret dashboard</p>
        </RouteGuard>
      </AppView>,
    )
    expect(screen.queryByText('secret dashboard')).toBeNull()
    view.rerender(
      <AppView value={auth()}>
        <RouteGuard roles={['ADMIN']}>
          <p>secret dashboard</p>
        </RouteGuard>
      </AppView>,
    )
    expect(screen.queryByText('secret dashboard')).toBeNull()
    expect(screen.getByRole('heading', { name: 'Bạn chưa có quyền truy cập' })).toBeTruthy()
  })

  it('preserves the internal target when a guest needs to log in', async () => {
    render(
      <AppView value={auth({ session: null })} path="/profile?from=checkout">
        <RouteGuard>
          <p>private profile</p>
        </RouteGuard>
      </AppView>,
    )
    expect(await screen.findByText('login: /profile?from=checkout')).toBeTruthy()
  })

  it('sends pending accounts to email verification without exposing protected contents', async () => {
    render(
      <AppView value={auth({ session: { ...activeSession, status: 'PENDING' } })} path="/profile">
        <RouteGuard>
          <p>private profile</p>
        </RouteGuard>
      </AppView>,
    )
    expect(await screen.findByText('verify: /profile')).toBeTruthy()
    expect(screen.queryByText('private profile')).toBeNull()
  })
})

describe('Account menu', () => {
  it('shows only available role areas and supports Escape to return focus', () => {
    render(
      <AppView value={auth()}>
        <AppHeader />
      </AppView>,
    )
    const button = screen.getByRole('button', { name: /Tài khoản của/ })
    fireEvent.click(button)
    expect(screen.getByRole('menuitem', { name: 'Hồ sơ cá nhân' })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: 'Kênh người bán' })).toBeTruthy()
    expect(screen.queryByRole('menuitem', { name: 'Khu vực quản trị' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Khu vực giao hàng' })).toBeNull()
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(button)
  })

  it('keeps logout failure visible and permits retry before reporting success', async () => {
    const logout = vi
      .fn()
      .mockRejectedValueOnce(apiFailure(503, 'UNAVAILABLE', 'Máy chủ đang bận.'))
      .mockResolvedValueOnce(undefined)
    render(
      <AppView value={auth({ logout })}>
        <AppHeader />
      </AppView>,
    )
    fireEvent.click(screen.getByRole('button', { name: /Tài khoản của/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Đăng xuất' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByText('login:')).toBeNull()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Đăng xuất' }))
    await waitFor(() => expect(logout).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('login:')).toBeTruthy()
  })
})

describe('Profile form', () => {
  it('retains edits after a failed save and lets the same changes be retried', async () => {
    const saveProfile = vi
      .fn()
      .mockRejectedValueOnce(apiFailure(503, 'UNAVAILABLE', 'Chưa lưu được hồ sơ.'))
      .mockResolvedValueOnce(profile)
    const value = auth({ saveProfile })
    const view = render(
      <AppView value={value}>
        <ProfilePage />
      </AppView>,
    )
    fireEvent.change(screen.getByLabelText('Họ và tên'), { target: { value: 'Tên mới' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    await screen.findByRole('alert')
    view.rerender(
      <AppView
        value={{
          ...value,
          profileState: 'error',
          profileError: { status: 503, message: 'Chưa lưu được hồ sơ.' },
        }}
      >
        <ProfilePage />
      </AppView>,
    )
    expect(screen.getByLabelText('Họ và tên').value).toBe('Tên mới')
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    await waitFor(() => expect(saveProfile).toHaveBeenCalledTimes(2))
    expect(saveProfile.mock.calls[0][0]).toEqual(saveProfile.mock.calls[1][0])
    expect(await screen.findByRole('status')).toBeTruthy()
  })

  it('rejects an invalid phone before calling the profile API', () => {
    const value = auth()
    render(
      <AppView value={value}>
        <ProfilePage />
      </AppView>,
    )
    fireEvent.change(screen.getByLabelText('Số điện thoại'), { target: { value: '123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    expect(value.saveProfile).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Số điện thoại').getAttribute('aria-invalid')).toBe('true')
  })
})

describe('Seller access', () => {
  it('only treats the explicit missing-shop response as no registration', async () => {
    getMyShop
      .mockRejectedValueOnce(apiFailure(503, 'UNAVAILABLE', 'Dịch vụ gian hàng đang bận.'))
      .mockRejectedValueOnce(apiFailure(404, 'SHOP_NOT_FOUND', 'Chưa có gian hàng.'))
    render(
      <AppView value={auth()} path="/seller">
        <SellerPage />
      </AppView>,
    )
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.queryByText('seller intro')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(await screen.findByText('seller intro')).toBeTruthy()
  })

  it('refreshes once for an approved shop but does not grant a missing Seller role', async () => {
    getMyShop.mockResolvedValue({ name: 'Gốm Mộc', status: 'ACTIVE', province: 'Đà Nẵng' })
    const value = auth()
    render(
      <AppView value={value} path="/seller">
        <SellerPage />
      </AppView>,
    )
    expect(
      await screen.findByRole('heading', { name: 'Quyền người bán đang được cập nhật' }),
    ).toBeTruthy()
    expect(value.refresh).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('heading', { name: 'Chào mừng đến kênh người bán' })).toBeNull()
  })

  it('shows the actual moderation reason for a suspended shop', async () => {
    getMyShop.mockResolvedValue({
      name: 'Gốm Mộc',
      status: 'SUSPENDED',
      statusReason: 'Cần cập nhật giấy tờ.',
    })
    render(
      <AppView value={auth()} path="/seller">
        <SellerPage />
      </AppView>,
    )
    expect(await screen.findByText('Cần cập nhật giấy tờ.')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Gian hàng đang bị đình chỉ' })).toBeTruthy()
  })
})
