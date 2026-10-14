// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import { getMyShop } from '../services/api/shopApi'
import SellerPage from './SellerPage'

vi.mock('../services/api/shopApi', () => ({ getMyShop: vi.fn() }))

const buyer = { userId: 'buyer-1', roles: ['BUYER'] }
const shop = { status: 'ACTIVE', name: 'Gian hàng của tôi', province: 'Đà Nẵng' }

function sellerView(session, refresh) {
  return (
    <AuthContext.Provider value={{ session, refresh }}>
      <MemoryRouter>
        <SellerPage />
      </MemoryRouter>
    </AuthContext.Provider>
  )
}

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('seller role refresh', () => {
  it('refreshes once per load, does not loop on unchanged roles, and allows explicit retry', async () => {
    getMyShop.mockResolvedValue(shop)
    const refresh = vi.fn().mockResolvedValue(buyer)
    const view = render(sellerView(buyer, refresh))
    await screen.findByRole('heading', { name: 'Quyền người bán đang được cập nhật' })
    expect(refresh).toHaveBeenCalledOnce()
    view.rerender(sellerView({ ...buyer, accessToken: 'new-token', roles: ['BUYER'] }, refresh))
    expect(refresh).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: 'Tải lại trạng thái' }))
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(2))
    await screen.findByRole('heading', { name: 'Quyền người bán đang được cập nhật' })
    expect(getMyShop).toHaveBeenCalledTimes(2)
  })

  it('does not rotate the refresh token when SELLER is already present', async () => {
    getMyShop.mockResolvedValue(shop)
    const refresh = vi.fn()
    render(sellerView({ ...buyer, roles: ['BUYER', 'SELLER'] }, refresh))
    await screen.findByRole('heading', { name: 'Chào mừng đến kênh người bán' })
    expect(refresh).not.toHaveBeenCalled()
  })

  it('does not grant seller access when refresh returns a different account', async () => {
    getMyShop.mockResolvedValue(shop)
    const refresh = vi.fn().mockResolvedValue({ userId: 'other-user', roles: ['SELLER'] })
    render(sellerView(buyer, refresh))
    await screen.findByRole('heading', { name: 'Quyền người bán đang được cập nhật' })
    expect(refresh).toHaveBeenCalledOnce()
  })
})
