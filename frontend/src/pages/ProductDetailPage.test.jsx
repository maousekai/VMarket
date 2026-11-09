// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ProductDetailPage from './ProductDetailPage'
import { getProduct, listCategories } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'

vi.mock('../services/api/productApi', () => ({
  getProduct: vi.fn(),
  listCategories: vi.fn(),
}))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))

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

const renderPage = (props = {}) =>
  render(
    <MemoryRouter initialEntries={['/products/p1']}>
      <Routes>
        <Route path="/products/:productId" element={<ProductDetailPage {...props} />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  listCategories.mockResolvedValue([
    { id: 'c1', name: 'Thời trang', children: [{ id: 'c1a', name: 'Áo polo', children: [] }] },
  ])
  getPublicShop.mockResolvedValue({ id: 's1', name: 'Danang Apparel', province: 'Đà Nẵng' })
  getProduct.mockResolvedValue({ product, similarProducts: [] })
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

  it('explains a product that is no longer for sale', async () => {
    getProduct.mockRejectedValue({
      response: { status: 404, data: { error: { code: 'PRODUCT_NOT_FOUND', message: 'x' } } },
    })
    renderPage()
    expect(
      await screen.findByRole('heading', { name: 'Sản phẩm không còn được bán' }),
    ).toBeInTheDocument()
  })
})
