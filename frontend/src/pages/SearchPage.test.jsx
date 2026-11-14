// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SearchPage from './SearchPage'
import { browseProducts, listCategories } from '../services/api/productApi'

vi.mock('../services/api/productApi', () => ({
  PRODUCT_SORT: {
    newest: 'NEWEST',
    bestSelling: 'BEST_SELLING',
    priceAsc: 'PRICE_ASC',
    priceDesc: 'PRICE_DESC',
  },
  browseProducts: vi.fn(),
  listCategories: vi.fn(),
}))

const renderAt = (path) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/search" element={<SearchPage />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  listCategories.mockResolvedValue([
    {
      id: 'c1',
      name: 'Thời trang',
      active: true,
      children: [{ id: 'c1a', name: 'Áo polo', parentId: 'c1', children: [] }],
    },
  ])
  browseProducts.mockResolvedValue({
    content: [{ id: 'p1', name: 'Áo dài lụa', minPrice: 1, maxPrice: 1, ratingCount: 0 }],
    totalElements: 1,
    totalPages: 1,
    page: 0,
  })
})

describe('SearchPage', () => {
  it('searches by keyword and shows the result count', async () => {
    renderAt('/search?q=%C3%A1o')
    expect(screen.getByRole('heading', { name: 'Kết quả cho “áo”' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Áo dài lụa' })).toBeInTheDocument()
    expect(screen.getByText('1 sản phẩm')).toBeInTheDocument()
    expect(browseProducts).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'áo', sort: 'NEWEST' }),
    )
  })

  it('titles a category listing with the category name', async () => {
    renderAt('/search?categoryId=c1')
    expect(await screen.findByRole('heading', { name: 'Thời trang' })).toBeInTheDocument()
    expect(browseProducts).toHaveBeenCalledWith(expect.objectContaining({ categoryId: 'c1' }))
  })

  it('shows subcategories and a breadcrumb for the current category', async () => {
    renderAt('/search?categoryId=c1')
    expect(await screen.findByRole('button', { name: 'Áo polo' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Đường dẫn' })).toHaveTextContent('Thời trang')
  })

  it('ignores unknown sort values', async () => {
    renderAt('/search?sort=DROP')
    await screen.findByRole('link', { name: 'Áo dài lụa' })
    expect(browseProducts).toHaveBeenCalledWith(expect.objectContaining({ sort: 'NEWEST' }))
  })

  it('passes price, rating, sort and page filters from the URL', async () => {
    renderAt('/search?minPrice=100000&maxPrice=500000&rating=4&sort=PRICE_ASC&page=3')
    await screen.findByRole('link', { name: 'Áo dài lụa' })
    expect(browseProducts).toHaveBeenCalledWith(
      expect.objectContaining({
        minPrice: 100000,
        maxPrice: 500000,
        minRating: 4,
        sort: 'PRICE_ASC',
        page: 2,
      }),
    )
  })

  it('applies a sort option and a rating filter', async () => {
    renderAt('/search')
    await screen.findByRole('link', { name: 'Áo dài lụa' })
    fireEvent.click(screen.getByRole('button', { name: 'Giá giảm dần' }))
    await screen.findByRole('button', { name: 'Giá giảm dần', pressed: true })
    expect(browseProducts).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'PRICE_DESC' }))
    fireEvent.click(screen.getByRole('button', { name: /Từ 4 sao/ }))
    await screen.findByRole('button', { name: /Từ 4 sao/, pressed: true })
    expect(browseProducts).toHaveBeenLastCalledWith(expect.objectContaining({ minRating: 4 }))
  })

  it('rejects an inverted price range', async () => {
    renderAt('/search')
    await screen.findByRole('link', { name: 'Áo dài lụa' })
    fireEvent.change(screen.getByLabelText('Từ'), { target: { value: '500000' } })
    fireEvent.change(screen.getByLabelText('Đến'), { target: { value: '1000' } })
    expect(screen.getByRole('alert')).toHaveTextContent('Giá tối thiểu phải nhỏ hơn giá tối đa')
  })

  it('shows the empty state', async () => {
    browseProducts.mockResolvedValue({ content: [], totalElements: 0 })
    renderAt('/search?q=zzz')
    expect(await screen.findByText(/Không tìm thấy sản phẩm phù hợp/)).toBeInTheDocument()
  })
})
