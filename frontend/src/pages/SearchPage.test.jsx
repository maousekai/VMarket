// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SearchPage from './SearchPage'
import { browseProducts, listCategories } from '../services/api/productApi'
import { searchProducts } from '../services/api/searchApi'

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

vi.mock('../services/api/searchApi', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, searchProducts: vi.fn() }
})

const unavailable = Object.assign(new Error('down'), { response: { status: 503 } })

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
  searchProducts.mockResolvedValue({
    content: [{ id: 's1', name: 'Áo polo AI', minPrice: 1, maxPrice: 1, ratingCount: 0 }],
    totalElements: 1,
    totalPages: 1,
    page: 0,
  })
})

describe('SearchPage', () => {
  it('searches a keyword with AI Search, most relevant first', async () => {
    renderAt('/search?q=%C3%A1o')
    expect(screen.getByRole('heading', { name: 'Kết quả cho “áo”' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Áo polo AI' })).toBeInTheDocument()
    expect(screen.getByText('Tìm thấy 1 sản phẩm phù hợp')).toBeInTheDocument()
    expect(searchProducts).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'áo', sort: 'RELEVANCE', page: 0, size: 20 }),
    )
    expect(browseProducts).not.toHaveBeenCalled()
    expect(
      screen.getByRole('button', { name: 'Liên quan nhất', pressed: true }),
    ).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Đường dẫn' })).toHaveTextContent('“áo”')
  })

  it('hides the rating filter and ignores rating while searching a keyword', async () => {
    renderAt('/search?q=%C3%A1o&rating=4&minPrice=1000&categoryId=c1')
    await screen.findByRole('link', { name: 'Áo polo AI' })
    expect(screen.queryByRole('button', { name: /Từ 4 sao/ })).toBeNull()
    const call = searchProducts.mock.calls[0][0]
    expect(call).toMatchObject({ keyword: 'áo', minPrice: 1000, categoryId: 'c1' })
    expect(call).not.toHaveProperty('minRating')
  })

  it('switches a keyword search to another sort and back to relevance', async () => {
    renderAt('/search?q=%C3%A1o')
    await screen.findByRole('link', { name: 'Áo polo AI' })
    fireEvent.click(screen.getByRole('button', { name: 'Bán chạy' }))
    await screen.findByRole('button', { name: 'Bán chạy', pressed: true })
    expect(searchProducts).toHaveBeenLastCalledWith(
      expect.objectContaining({ sort: 'BEST_SELLING' }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Liên quan nhất' }))
    await screen.findByRole('button', { name: 'Liên quan nhất', pressed: true })
    expect(searchProducts).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'RELEVANCE' }))
  })

  it('falls back to a basic name search when Search Service is down', async () => {
    searchProducts.mockRejectedValue(unavailable)
    renderAt('/search?q=%C3%A1o%20polo')
    expect(await screen.findByRole('link', { name: 'Áo dài lụa' })).toBeInTheDocument()
    expect(screen.getByText(/Tìm kiếm cơ bản đang hoạt động/)).toBeInTheDocument()
    expect(browseProducts).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'áo polo', sort: 'BEST_SELLING' }),
    )
  })

  it('shows an error, not the fallback, for a rejected query', async () => {
    searchProducts.mockRejectedValue(
      Object.assign(new Error('bad'), {
        response: { status: 400, data: { error: { code: 'INVALID_QUERY' } } },
      }),
    )
    renderAt('/search?q=%C3%A1o')
    expect(await screen.findByRole('button', { name: /Thử lại/ })).toBeInTheDocument()
    expect(browseProducts).not.toHaveBeenCalled()
  })

  it('browses without a keyword through Product Service', async () => {
    renderAt('/search')
    expect(await screen.findByRole('link', { name: 'Áo dài lụa' })).toBeInTheDocument()
    expect(searchProducts).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Liên quan nhất' })).toBeNull()
    expect(screen.getByText('1 sản phẩm')).toBeInTheDocument()
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

  it('keeps the filters and suggests best sellers when nothing matches', async () => {
    searchProducts.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, page: 0 })
    renderAt('/search?q=zzz&maxPrice=500000')
    expect(
      await screen.findByRole('heading', { name: 'Chưa tìm thấy sản phẩm' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Áo dài lụa' })).toBeInTheDocument()
    expect(browseProducts).toHaveBeenCalledWith(
      expect.objectContaining({ sort: 'BEST_SELLING', size: 8 }),
    )
    const clear = screen.getAllByRole('button', { name: /Xo[áa] bộ lọc/ })
    fireEvent.click(clear[clear.length - 1])
    await vi.waitFor(() =>
      expect(searchProducts).toHaveBeenLastCalledWith(
        expect.not.objectContaining({ maxPrice: 500000 }),
      ),
    )
  })
})
