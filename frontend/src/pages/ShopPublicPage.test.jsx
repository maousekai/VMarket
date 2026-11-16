// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ShopPublicPage from './ShopPublicPage'
import { listProducts } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'

vi.mock('../services/api/productApi', () => ({ listProducts: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))

const product = (index) => ({
  id: `p${index}`,
  name: `Sản phẩm ${index}`,
  imageUrls: [],
  minPrice: 100000,
  maxPrice: 100000,
  soldCount: 0,
  availableStock: 5,
})

const firstPage = Array.from({ length: 12 }, (_, index) => product(index + 1))

const renderAt = (path) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/shops/:shopId" element={<ShopPublicPage />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  getPublicShop.mockResolvedValue({
    id: 'shop-1',
    name: 'Gian hàng VMarket',
    province: 'Đà Nẵng',
    approvedAt: null,
  })
  listProducts.mockResolvedValue({
    content: firstPage,
    page: 0,
    size: 12,
    totalElements: 13,
    totalPages: 2,
  })
})

describe('ShopPublicPage', () => {
  it('renders the product page content and loads the next page', async () => {
    listProducts.mockImplementation(({ page }) =>
      Promise.resolve(
        page === 0
          ? { content: firstPage, page: 0, size: 12, totalElements: 13, totalPages: 2 }
          : { content: [product(13)], page: 1, size: 12, totalElements: 13, totalPages: 2 },
      ),
    )

    renderAt('/shops/shop-1')

    expect(await screen.findByRole('heading', { name: 'Sản phẩm 1' })).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(12)
    expect(screen.getByRole('link', { name: 'Sản phẩm 1' })).toHaveAttribute(
      'href',
      expect.stringMatching(/^\/products\//),
    )
    expect(screen.queryByRole('heading', { name: 'Sản phẩm 13' })).toBeNull()
    expect(listProducts).toHaveBeenCalledWith({ shopId: 'shop-1', page: 0, size: 12 })

    fireEvent.click(screen.getByRole('button', { name: 'Tải thêm sản phẩm' }))

    expect(await screen.findByRole('heading', { name: 'Sản phẩm 13' })).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(13)
    expect(listProducts).toHaveBeenLastCalledWith({ shopId: 'shop-1', page: 1, size: 12 })
    expect(screen.queryByRole('button', { name: 'Tải thêm sản phẩm' })).toBeNull()
  })

  it('keeps loaded products and allows retrying a failed next page', async () => {
    listProducts
      .mockResolvedValueOnce({
        content: firstPage,
        page: 0,
        size: 12,
        totalElements: 13,
        totalPages: 2,
      })
      .mockRejectedValueOnce(new Error('down'))
      .mockResolvedValueOnce({
        content: [product(13)],
        page: 1,
        size: 12,
        totalElements: 13,
        totalPages: 2,
      })

    renderAt('/shops/shop-1')
    await screen.findByRole('heading', { name: 'Sản phẩm 1' })

    fireEvent.click(screen.getByRole('button', { name: 'Tải thêm sản phẩm' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Không tải thêm được sản phẩm. Vui lòng thử lại.',
    )
    expect(screen.getAllByRole('article')).toHaveLength(12)

    fireEvent.click(screen.getByRole('button', { name: 'Tải thêm sản phẩm' }))
    expect(await screen.findByRole('heading', { name: 'Sản phẩm 13' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
