// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ProductDetailPage from './ProductDetailPage'
import { AuthContext } from '../auth/useAuth'
import { getProduct, listCategories } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'
import { getProductRatingSummary, getProductReviews } from '../services/api/reviewApi'
import { getSimilar, trackView } from '../services/api/recommendationApi'

vi.mock('../services/api/productApi', () => ({
  getProduct: vi.fn(),
  listCategories: vi.fn(),
}))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))
vi.mock('../services/api/recommendationApi', () => ({
  getSimilar: vi.fn(),
  trackView: vi.fn(),
}))
vi.mock('../services/api/reviewApi', () => ({
  getProductRatingSummary: vi.fn(),
  getProductReviews: vi.fn(),
}))

const emptySummary = {
  targetId: 'p1',
  average: 0,
  count: 0,
  distribution: { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 },
}
const reviewPage = (items) => ({
  items,
  page: 0,
  size: 5,
  totalElements: items.length,
  totalPages: 1,
})

const product = {
  id: 'p1',
  shopId: 's1',
  name: 'Áo Polo Cotton',
  description: 'Vải cotton thoáng mát',
  imageUrls: ['https://cdn.test/1.jpg', 'https://cdn.test/2.jpg'],
  categoryId: 'c1a',
  currency: 'VND',
  minPrice: 249000,
  maxPrice: 259000,
  ratingAverage: 4.8,
  ratingCount: 12,
  soldCount: 30,
  variants: [
    { id: 'v1', sku: 'P-M', attributes: { Size: 'M' }, price: 249000, availableStock: 0 },
    { id: 'v2', sku: 'P-L', attributes: { Size: 'L' }, price: 259000, availableStock: 3 },
  ],
}

const renderPage = (props = {}, session = null) =>
  render(
    <AuthContext.Provider value={{ session, bootstrapState: 'ready' }}>
      <MemoryRouter initialEntries={['/products/p1']}>
        <Routes>
          <Route path="/products/:productId" element={<ProductDetailPage {...props} />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )

const card = (id) => ({
  id,
  name: `Tương tự ${id}`,
  imageUrls: [],
  minPrice: 1,
  maxPrice: 1,
  currency: 'VND',
  ratingCount: 0,
})

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  getSimilar.mockResolvedValue([])
  trackView.mockResolvedValue(undefined)
  listCategories.mockResolvedValue([
    { id: 'c1', name: 'Thời trang', children: [{ id: 'c1a', name: 'Áo polo', children: [] }] },
  ])
  getPublicShop.mockResolvedValue({ id: 's1', name: 'Danang Apparel', province: 'Đà Nẵng' })
  getProduct.mockResolvedValue({ product, similarProducts: [] })
  getProductRatingSummary.mockResolvedValue(emptySummary)
  getProductReviews.mockResolvedValue(reviewPage([]))
})

describe('ProductDetailPage', () => {
  it('shows product info, category path and shop', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Áo Polo Cotton' })).toBeInTheDocument()
    expect(await screen.findByText('Danang Apparel')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Đường dẫn' })).toHaveTextContent('Áo polo')
    expect(screen.getByText('Vải cotton thoáng mát')).toBeInTheDocument()
  })

  it('preselects the first variant in stock and caps quantity at its stock', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Áo Polo Cotton' })
    expect(screen.getByRole('button', { name: 'L', pressed: true })).toBeInTheDocument()
    expect(screen.getByText('Còn 3 sản phẩm')).toBeInTheDocument()
    const increase = screen.getByRole('button', { name: 'Tăng số lượng' })
    fireEvent.click(increase)
    fireEvent.click(increase)
    expect(screen.getByRole('textbox', { name: 'Số lượng' })).toHaveValue('3')
    expect(increase).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Thêm vào giỏ hàng' })).toBeEnabled()
  })

  it('tells the buyer when the chosen variant is sold out', async () => {
    const renderActions = vi.fn(() => null)
    renderPage({ renderActions })
    await screen.findByRole('heading', { name: 'Áo Polo Cotton' })
    fireEvent.click(screen.getByRole('button', { name: 'M' }))
    expect(screen.getByText('Phân loại này đã hết hàng')).toBeInTheDocument()
    expect(renderActions).toHaveBeenLastCalledWith(
      expect.objectContaining({ soldOut: true, variant: expect.objectContaining({ id: 'v1' }) }),
    )
  })

  it('switches the main image from the thumbnails', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Áo Polo Cotton' })
    const thumbs = screen.getByRole('group', { name: 'Ảnh sản phẩm' })
    fireEvent.click(within(thumbs).getByRole('button', { name: 'Xem ảnh 2' }))
    expect(screen.getByRole('img', { name: 'Áo Polo Cotton' })).toHaveAttribute(
      'src',
      'https://cdn.test/2.jpg',
    )
  })

  it('shows rating summary, reviews with seller replies and filters by stars', async () => {
    getProductRatingSummary.mockResolvedValue({
      targetId: 'p1',
      average: 4.5,
      count: 2,
      distribution: { 1: 0, 2: 0, 3: 0, 4: 1, 5: 1 },
    })
    getProductReviews.mockResolvedValue(
      reviewPage([
        {
          id: 'r1',
          productId: 'p1',
          rating: 5,
          comment: 'Vải mát, giao nhanh',
          imageUrls: ['https://cdn.test/r1.jpg'],
          createdAt: '2026-11-10T03:00:00Z',
          reply: { content: 'Cảm ơn bạn!', createdAt: '2026-11-11T03:00:00Z' },
        },
      ]),
    )
    renderPage()
    const section = await screen.findByRole('region', { name: 'Đánh giá khách hàng' })
    expect(await within(section).findByText('Vải mát, giao nhanh')).toBeInTheDocument()
    expect(within(section).getByText('Cảm ơn bạn!')).toBeInTheDocument()
    expect(within(section).getByText('Người mua đã xác minh')).toBeInTheDocument()
    expect(within(section).getByText('2 đánh giá')).toBeInTheDocument()

    fireEvent.click(within(section).getByRole('button', { name: '4 sao' }))
    await waitFor(() =>
      expect(getProductReviews).toHaveBeenLastCalledWith('p1', { rating: 4, page: 0, size: 5 }),
    )
  })

  it('invites the first review when the product has none', async () => {
    renderPage()
    expect(await screen.findByText(/Sản phẩm chưa có đánh giá/)).toBeInTheDocument()
  })

  it('explains a product that is no longer for sale', async () => {
    getProduct.mockRejectedValue({
      response: { status: 404, data: { error: { code: 'PRODUCT_NOT_FOUND', message: 'x' } } },
    })
    renderPage()
    expect(
      await screen.findByRole('heading', { name: 'Sản phẩm không còn được bán' }),
    ).toBeInTheDocument()
  })

  it('shows similar products from Recommendation and loads more on demand', async () => {
    getSimilar.mockImplementation(async (id, { limit }) =>
      Array.from({ length: limit }, (_, index) => card(`r${index + 1}`)),
    )
    renderPage()
    expect(await screen.findByRole('link', { name: 'Tương tự r10' })).toBeInTheDocument()
    expect(getSimilar).toHaveBeenCalledWith('p1', { limit: 10 })
    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm sản phẩm tương tự' }))
    expect(await screen.findByRole('link', { name: 'Tương tự r20' })).toBeInTheDocument()
    expect(getSimilar).toHaveBeenLastCalledWith('p1', { limit: 20 })
    expect(screen.queryByRole('button', { name: 'Xem thêm sản phẩm tương tự' })).toBeNull()
  })

  it('falls back to the catalog list when Recommendation does not know the product', async () => {
    getSimilar.mockRejectedValue({ response: { status: 404 } })
    getProduct.mockResolvedValue({ product, similarProducts: [card('c1')] })
    renderPage()
    expect(await screen.findByRole('link', { name: 'Tương tự c1' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xem thêm sản phẩm tương tự' })).toBeNull()
  })

  it('hides the section when nothing is similar', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Áo Polo Cotton' })
    await waitFor(() => expect(getSimilar).toHaveBeenCalled())
    expect(screen.queryByRole('heading', { name: 'Sản phẩm tương tự' })).toBeNull()
  })

  it('records one view per product per session for signed-in buyers', async () => {
    const { unmount } = renderPage({}, { userId: 'buyer-1' })
    await waitFor(() => expect(trackView).toHaveBeenCalledWith('p1'))
    unmount()
    renderPage({}, { userId: 'buyer-1' })
    await screen.findByRole('heading', { name: 'Áo Polo Cotton' })
    expect(trackView).toHaveBeenCalledTimes(1)
  })

  it('does not track guests but remembers the category they viewed', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Áo Polo Cotton' })
    expect(trackView).not.toHaveBeenCalled()
    expect(JSON.parse(sessionStorage.getItem('vmarket.lastViewedCategory'))).toBe('c1a')
  })

  it('retries a view that could not be recorded on the next visit', async () => {
    trackView.mockRejectedValueOnce(new Error('down'))
    const { unmount } = renderPage({}, { userId: 'buyer-1' })
    await waitFor(() => expect(trackView).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(sessionStorage.getItem('vmarket.viewed.buyer-1')).toBe('[]'))
    unmount()
    renderPage({}, { userId: 'buyer-1' })
    await waitFor(() => expect(trackView).toHaveBeenCalledTimes(2))
  })
})
