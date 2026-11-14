// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import { getMyShop } from '../services/api/shopApi'
import { listSellerOrders, listSellerReturns } from '../services/api/orderApi'
import { refreshSellerAlerts } from '../hooks/useSellerAlerts'
import SellerLayout from './SellerLayout'

vi.mock('../services/api/shopApi', () => ({ getMyShop: vi.fn() }))
vi.mock('../services/api/orderApi', () => ({
  listSellerOrders: vi.fn(),
  listSellerReturns: vi.fn(),
}))

const session = {
  userId: 'seller-1',
  username: 'seller',
  email: 'seller@example.com',
  roles: ['SELLER'],
  status: 'ACTIVE',
}
const profile = { userId: session.userId, fullName: 'Nguyễn Văn Bán', avatarUrl: null }

let logout

function auth(overrides = {}) {
  return { session, userProfile: profile, logout, ...overrides }
}

function view(value = auth()) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={['/seller']}>
        <Routes>
          <Route path="/login" element={<p>trang đăng nhập</p>} />
          <Route path="/profile" element={<p>trang hồ sơ</p>} />
          <Route path="/" element={<p>trang chủ</p>} />
          <Route path="/seller/register" element={<p>đăng ký gian hàng</p>} />
          <Route path="/seller" element={<SellerLayout />}>
            <Route index element={<p>trang người bán</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  logout = vi.fn().mockResolvedValue(undefined)
  getMyShop.mockResolvedValue({ id: 'shop-1', status: 'ACTIVE' })
  listSellerOrders.mockResolvedValue({ totalElements: 0 })
  listSellerReturns.mockResolvedValue({ totalElements: 0 })
})
afterEach(cleanup)

describe('Seller account menu', () => {
  it('puts profile, home and logout in the dropdown instead of beside the name', async () => {
    view()
    const trigger = await screen.findByRole('button', {
      name: 'Tài khoản của Nguyễn Văn Bán',
    })
    expect(screen.queryByRole('button', { name: 'Đăng xuất' })).toBeNull()

    fireEvent.click(trigger)
    const panel = screen.getByRole('group', { name: 'Tài khoản' })
    expect(within(panel).getByText('seller@example.com')).toBeTruthy()
    expect(within(panel).getByRole('link', { name: 'Hồ sơ cá nhân' }).getAttribute('href')).toBe(
      '/profile',
    )
    expect(within(panel).getByRole('link', { name: 'Về trang chủ' }).getAttribute('href')).toBe('/')
    expect(within(panel).getAllByRole('button', { name: 'Đăng xuất' })).toHaveLength(1)
  })

  it('closes with Escape and signs out from the dropdown', async () => {
    view()
    fireEvent.click(await screen.findByRole('button', { name: 'Tài khoản của Nguyễn Văn Bán' }))
    fireEvent.keyDown(screen.getByRole('group', { name: 'Tài khoản' }), { key: 'Escape' })
    expect(screen.queryByRole('group', { name: 'Tài khoản' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Tài khoản của Nguyễn Văn Bán' }))
    fireEvent.click(screen.getByRole('button', { name: 'Đăng xuất' }))
    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('trang đăng nhập')).toBeTruthy()
  })
})

describe('Seller alerts', () => {
  it('shows red badges for orders and returns waiting for the seller', async () => {
    listSellerOrders.mockImplementation(async ({ status }) => ({
      totalElements: status === 'PENDING' ? 2 : 1,
    }))
    listSellerReturns.mockResolvedValue({ totalElements: 1 })
    view()
    expect(await screen.findByLabelText('3 đơn cần xử lý')).toHaveTextContent('3')
    expect(screen.getByLabelText('1 yêu cầu trả hàng chờ phản hồi')).toBeTruthy()
    expect(screen.getByRole('link', { name: /Trả hàng/ })).toHaveAttribute(
      'href',
      '/seller/orders?view=returns',
    )
  })

  it('hides the badge once nothing is waiting', async () => {
    listSellerReturns.mockResolvedValue({ totalElements: 1 })
    view()
    await screen.findByLabelText('1 yêu cầu trả hàng chờ phản hồi')
    expect(screen.queryByLabelText(/đơn cần xử lý/)).toBeNull()
    listSellerReturns.mockResolvedValue({ totalElements: 0 })
    refreshSellerAlerts()
    await waitFor(() => expect(screen.queryByLabelText(/chờ phản hồi/)).toBeNull())
  })
})
