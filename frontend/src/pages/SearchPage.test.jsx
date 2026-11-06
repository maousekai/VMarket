// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SearchPage from './SearchPage'
import { browseProducts, listCategories } from '../services/api/productApi'

vi.mock('../services/api/productApi', () => ({
  PRODUCT_SORT: { newest: 'NEWEST', bestSelling: 'BEST_SELLING' },
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
  listCategories.mockResolvedValue([{ id: 'c1', name: 'Thời trang', active: true }])
  browseProducts.mockResolvedValue({
    content: [{ id: 'p1', name: 'Áo dài lụa', minPrice: 1, maxPrice: 1, ratingCount: 0 }],
    totalElements: 1,
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

  it('ignores unknown sort values', async () => {
    renderAt('/search?sort=DROP')
    await screen.findByRole('link', { name: 'Áo dài lụa' })
    expect(browseProducts).toHaveBeenCalledWith(expect.objectContaining({ sort: 'NEWEST' }))
  })

  it('shows the empty state', async () => {
    browseProducts.mockResolvedValue({ content: [], totalElements: 0 })
    renderAt('/search?q=zzz')
    expect(await screen.findByText(/Không tìm thấy sản phẩm phù hợp/)).toBeInTheDocument()
  })
})
