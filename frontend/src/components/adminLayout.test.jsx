// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import AdminPlaceholderPage from '../pages/AdminPlaceholderPage'
import AdminNotFoundPage from '../pages/AdminNotFoundPage'
import { adminSections } from '../pages/adminSections'
import AdminLayout from './AdminLayout'

const session = {
  userId: '01JBQ9YDX7K3M8N5P2R4T6V8AA',
  username: 'admin.vmarket',
  email: 'admin@vmarket.local',
  roles: ['ADMIN'],
  status: 'ACTIVE',
}
const profile = { userId: session.userId, fullName: 'Nguyễn Quản Trị', avatarUrl: null }

let logout

function auth(overrides = {}) {
  return {
    session,
    bootstrapState: 'ready',
    userProfile: profile,
    profileState: 'ready',
    logout,
    ...overrides,
  }
}

function view(path = '/admin', value = auth()) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<p>trang đăng nhập</p>} />
          <Route path="/profile" element={<p>trang hồ sơ</p>} />
          <Route path="/" element={<p>trang chủ</p>} />
          <Route path="/admin" element={<AdminLayout />}>
            {adminSections.map((section) => {
              const element =
                section.path === 'users' ? (
                  <p>danh sách người dùng</p>
                ) : (
                  <AdminPlaceholderPage section={section} />
                )
              return section.path === '' ? (
                <Route key="overview" index element={element} />
              ) : (
                <Route key={section.path} path={section.path} element={element} />
              )
            })}
            <Route path="*" element={<AdminNotFoundPage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

const nav = () => within(screen.getByRole('navigation', { name: 'Điều hướng quản trị' }))
const current = () =>
  nav()
    .getAllByRole('link')
    .filter((link) => link.getAttribute('aria-current') === 'page')
    .map((link) => link.textContent)

beforeEach(() => {
  logout = vi.fn().mockResolvedValue(undefined)
})
afterEach(cleanup)

describe('Admin sidebar', () => {
  it('lists every section of the design in order with its address', () => {
    view()
    const links = nav().getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual([
      'Tổng quan',
      'Kiểm duyệt sản phẩm',
      'Giám sát đơn hàng',
      'Phê duyệt gian hàng',
      'Điều phối Shipper',
      'Phân xử khiếu nại',
      'Quản lý người dùng',
      'Quản lý danh mục',
      'Báo cáo & Tài chính',
      'Cài đặt hệ thống',
    ])
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/admin',
      '/admin/products',
      '/admin/orders',
      '/admin/shops',
      '/admin/shippers',
      '/admin/disputes',
      '/admin/users',
      '/admin/categories',
      '/admin/reports',
      '/admin/settings',
    ])
    expect(screen.getByText('ĐIỀU HÀNH & QUẢN TRỊ')).toBeTruthy()
    expect(screen.getByText('Admin')).toBeTruthy()
  })

  it('marks only the section being viewed as current', () => {
    view('/admin/users')
    expect(current()).toEqual(['Quản lý người dùng'])
    cleanup()
    view('/admin')
    expect(current()).toEqual(['Tổng quan'])
    cleanup()
    view('/admin/orders')
    expect(current()).toEqual(['Giám sát đơn hàng'])
  })

  it('navigates when a section is chosen and moves the highlight', () => {
    view('/admin/users')
    fireEvent.click(nav().getByRole('link', { name: 'Giám sát đơn hàng' }))
    expect(screen.getByRole('heading', { name: 'Giám sát đơn hàng' })).toBeTruthy()
    expect(current()).toEqual(['Giám sát đơn hàng'])
    fireEvent.click(nav().getByRole('link', { name: 'Quản lý người dùng' }))
    expect(screen.getByText('danh sách người dùng')).toBeTruthy()
    expect(current()).toEqual(['Quản lý người dùng'])
  })

  it('leaves the sections without a design empty', () => {
    for (const section of adminSections.filter((item) => !item.built)) {
      view(section.to)
      expect(screen.getByRole('heading', { level: 1, name: section.title })).toBeTruthy()
      expect(screen.getByText('Chức năng này đang được phát triển.')).toBeTruthy()
      cleanup()
    }
  })

  it('shows the not found page for an unknown admin address inside the layout', () => {
    view('/admin/khong-co')
    expect(screen.getByRole('heading', { name: 'Không tìm thấy trang' })).toBeTruthy()
    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(nav().getAllByRole('link')).toHaveLength(10)
    fireEvent.click(screen.getByRole('link', { name: 'Về trang tổng quan' }))
    expect(screen.getByRole('heading', { name: 'Tổng quan nền tảng' })).toBeTruthy()
  })
})

describe('Admin top bar', () => {
  it('shows the console title, the admin name and role', () => {
    view()
    expect(screen.getByText('VMarket Operations Console')).toBeTruthy()
    const trigger = screen.getByRole('button', { name: 'Tài khoản của Nguyễn Quản Trị' })
    expect(within(trigger).getByText('Nguyễn Quản Trị')).toBeTruthy()
    expect(within(trigger).getByText('Quản trị viên')).toBeTruthy()
  })

  it('falls back to the username before the profile has loaded', () => {
    view('/admin', auth({ userProfile: null }))
    expect(screen.getByRole('button', { name: 'Tài khoản của admin.vmarket' })).toBeTruthy()
  })

  it('keeps the search box and notifications present but unavailable', () => {
    view()
    expect(screen.getByLabelText('Tìm mã đơn').disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'Thông báo' }).disabled).toBe(true)
  })
})

describe('Admin mobile menu', () => {
  const toggle = () => screen.getByRole('button', { name: 'Mở menu' })
  const sidebar = () => document.getElementById('admin-sidebar')

  it('opens and closes the sidebar with the menu button', () => {
    view()
    expect(toggle().getAttribute('aria-expanded')).toBe('false')
    expect(sidebar().classList.contains('is-open')).toBe(false)
    fireEvent.click(toggle())
    expect(toggle().getAttribute('aria-expanded')).toBe('true')
    expect(sidebar().classList.contains('is-open')).toBe(true)
    fireEvent.click(toggle())
    expect(sidebar().classList.contains('is-open')).toBe(false)
  })

  it('closes after choosing a section, with Escape or by tapping the backdrop', () => {
    view()
    fireEvent.click(toggle())
    fireEvent.click(nav().getByRole('link', { name: 'Quản lý danh mục' }))
    expect(sidebar().classList.contains('is-open')).toBe(false)
    fireEvent.click(toggle())
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(sidebar().classList.contains('is-open')).toBe(false)
    fireEvent.click(toggle())
    fireEvent.click(screen.getByRole('button', { name: 'Đóng menu' }))
    expect(sidebar().classList.contains('is-open')).toBe(false)
    expect(screen.queryByRole('button', { name: 'Đóng menu' })).toBeNull()
  })
})

describe('Admin account menu', () => {
  const open = () =>
    fireEvent.click(screen.getByRole('button', { name: 'Tài khoản của Nguyễn Quản Trị' }))
  const panel = () => screen.getByRole('group', { name: 'Tài khoản' })

  it('offers the profile, the storefront and logout', () => {
    view()
    expect(screen.queryByRole('group', { name: 'Tài khoản' })).toBeNull()
    open()
    expect(within(panel()).getByText('admin@vmarket.local')).toBeTruthy()
    expect(within(panel()).getByRole('link', { name: 'Hồ sơ cá nhân' }).getAttribute('href')).toBe(
      '/profile',
    )
    expect(within(panel()).getByRole('link', { name: 'Về trang chủ' }).getAttribute('href')).toBe(
      '/',
    )
    expect(within(panel()).getByRole('button', { name: 'Đăng xuất' })).toBeTruthy()
  })

  it('closes with Escape and when clicking elsewhere', () => {
    view()
    open()
    fireEvent.keyDown(panel(), { key: 'Escape' })
    expect(screen.queryByRole('group', { name: 'Tài khoản' })).toBeNull()
    open()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('group', { name: 'Tài khoản' })).toBeNull()
  })

  it('leaves the admin area through the profile link', () => {
    view()
    open()
    fireEvent.click(within(panel()).getByRole('link', { name: 'Hồ sơ cá nhân' }))
    expect(screen.getByText('trang hồ sơ')).toBeTruthy()
  })

  it('logs out and goes to the login page', async () => {
    view()
    open()
    fireEvent.click(within(panel()).getByRole('button', { name: 'Đăng xuất' }))
    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('trang đăng nhập')).toBeTruthy()
  })

  it('keeps the menu and explains when logging out fails', async () => {
    logout.mockRejectedValue({
      response: { status: 503, data: { error: { code: 'UNAVAILABLE', message: 'Máy chủ bận.' } } },
    })
    view()
    open()
    fireEvent.click(within(panel()).getByRole('button', { name: 'Đăng xuất' }))
    expect((await screen.findByRole('alert')).textContent).toContain('Máy chủ bận.')
    expect(within(panel()).getByRole('button', { name: 'Đăng xuất' }).disabled).toBe(false)
  })
})
