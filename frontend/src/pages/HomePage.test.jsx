// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import HomePage from './HomePage'
import { browseProducts } from '../services/api/productApi'
import { getForYou } from '../services/api/recommendationApi'
import { getPublicShop } from '../services/api/shopApi'

vi.mock('../services/api/productApi', () => ({
  PRODUCT_SORT: { newest: 'NEWEST', bestSelling: 'BEST_SELLING' },
  browseProducts: vi.fn(),
  listCategories: vi.fn().mockResolvedValue([]),
}))
vi.mock('../services/api/recommendationApi', () => ({ getForYou: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))

const product = (id, shopId = 'shop-1') => ({
  id,
  shopId,
  name: `Sản phẩm ${id}`,
  imageUrls: [],
  minPrice: 100000,
  maxPrice: 100000,
  currency: 'VND',
  ratingAverage: 0,
  ratingCount: 0,
})

function renderHome(session = null) {
  const value = { session, bootstrapState: 'ready', refresh: vi.fn() }
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  browseProducts.mockResolvedValue({ content: [product('best-1'), product('best-2', 'shop-2')] })
  getPublicShop.mockImplementation(async (id) => ({ id, name: `Gian ${id}`, province: 'Đà Nẵng' }))
})

describe('HomePage', () => {
  it('shows best sellers to guests without calling recommendations', async () => {
    renderHome()
    expect(await screen.findByRole('heading', { name: 'Bán chạy tại Đà Nẵng' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Sản phẩm best-1' })).toBeInTheDocument()
    expect(screen.getByText('Gợi ý cho khách mới')).toBeInTheDocument()
    expect(getForYou).not.toHaveBeenCalled()
    expect(browseProducts).toHaveBeenCalledWith(expect.objectContaining({ sort: 'BEST_SELLING' }))
  })

  it('shows personal picks to signed-in users', async () => {
    getForYou.mockResolvedValue([product('mine-1')])
    renderHome({ userId: 'buyer-1', status: 'ACTIVE', roles: ['BUYER'] })
    expect(
      await screen.findByRole('heading', { name: 'Sản phẩm dành cho bạn' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Sản phẩm mine-1' })).toBeInTheDocument()
  })

  it('falls back to best sellers when recommendations fail or are empty', async () => {
    getForYou.mockRejectedValue(new Error('down'))
    renderHome({ userId: 'buyer-1', status: 'ACTIVE', roles: ['BUYER'] })
    expect(await screen.findByRole('link', { name: 'Sản phẩm best-1' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Bán chạy tại Đà Nẵng' })).toBeInTheDocument()
    expect(screen.getByText('Được mua nhiều nhất')).toBeInTheDocument()
  })

  it('builds featured shops from the best sellers’ shops', async () => {
    renderHome()
    expect(await screen.findByText('Gian shop-1')).toBeInTheDocument()
    expect(screen.getByText('Gian shop-2')).toBeInTheDocument()
    expect(getPublicShop).toHaveBeenCalledTimes(2)
  })

  it('keeps the page usable when the catalog is down and lets the user retry', async () => {
    browseProducts.mockRejectedValue({ response: { status: 503, data: {} }, message: 'x' })
    renderHome()
    const alerts = await screen.findAllByRole('alert')
    expect(alerts[0]).toHaveTextContent('Không tải được sản phẩm.')
    expect(screen.getByRole('region', { name: 'Nổi bật hôm nay' })).toBeInTheDocument()

    browseProducts.mockResolvedValue({ content: [product('best-1')] })
    fireEvent.click(screen.getAllByRole('button', { name: 'Thử lại' })[0])
    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Sản phẩm best-1' })).toBeInTheDocument(),
    )
  })

  it('shows a friendly message when nothing is listed yet', async () => {
    browseProducts.mockResolvedValue({ content: [] })
    renderHome()
    expect(await screen.findByText(/Các nhà bán đang lên kệ sản phẩm/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Cửa Hàng Nổi Bật/ })).toBeNull()
  })
})
