// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import CategoryNav from './CategoryNav'
import HeaderSearch from './HeaderSearch'
import { listCategories } from '../services/api/productApi'

vi.mock('../services/api/productApi', () => ({ listCategories: vi.fn() }))

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

const renderAt = (ui, path = '/') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      {ui}
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => vi.clearAllMocks())

describe('HeaderSearch', () => {
  it('navigates to the search page with the trimmed keyword', () => {
    renderAt(<HeaderSearch />)
    fireEvent.change(screen.getByRole('searchbox', { name: 'Tìm kiếm sản phẩm' }), {
      target: { value: '  áo dài  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/search?q=%C3%A1o%20d%C3%A0i')
  })

  it('pre-fills the keyword on the search page', () => {
    renderAt(<HeaderSearch />, '/search?q=b%C3%A1nh%20m%C3%AC')
    expect(screen.getByRole('searchbox')).toHaveValue('bánh mì')
  })
})

describe('CategoryNav', () => {
  it('lists active top-level categories in admin order and marks the current one', async () => {
    listCategories.mockResolvedValue([
      { id: 'c2', name: 'Điện máy', sortOrder: 2, active: true, parentId: null },
      { id: 'c1', name: 'Thời trang', sortOrder: 1, active: true, parentId: null },
      { id: 'c3', name: 'Ẩn', sortOrder: 0, active: false, parentId: null },
    ])
    renderAt(<CategoryNav />, '/search?categoryId=c2')
    const links = await screen.findAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(['Thời trang', 'Điện máy'])
    expect(links[1]).toHaveAttribute('aria-current', 'page')
    expect(links[0]).toHaveAttribute('href', '/search?categoryId=c1')
  })

  it('renders nothing when categories cannot be loaded', async () => {
    listCategories.mockRejectedValue(new Error('down'))
    renderAt(<CategoryNav />)
    await vi.waitFor(() => expect(screen.queryByRole('navigation')).toBeNull())
  })
})
