// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import CategoryNav from './CategoryNav'
import HeaderSearch from './HeaderSearch'
import { ImageSearchContext } from './imageSearchContext'
import { AuthContext } from '../auth/useAuth'
import { listCategories } from '../services/api/productApi'
import { suggestKeywords } from '../services/api/searchApi'

vi.mock('../services/api/productApi', () => ({ listCategories: vi.fn() }))
vi.mock('../services/api/searchApi', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, suggestKeywords: vi.fn() }
})

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

const box = () => screen.getByRole('combobox', { name: 'Tìm kiếm sản phẩm' })
const type = (value) => fireEvent.change(box(), { target: { value } })

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  suggestKeywords.mockResolvedValue([
    { id: 'p1', name: 'Áo polo nam' },
    { id: 'p2', name: 'Áo thun unisex' },
  ])
})

describe('HeaderSearch', () => {
  it('navigates to the search page with the trimmed keyword and remembers it', () => {
    renderAt(<HeaderSearch />)
    type('  áo dài  ')
    fireEvent.click(screen.getByRole('button', { name: 'Tìm' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/search?q=%C3%A1o%20d%C3%A0i')
    expect(JSON.parse(localStorage.getItem('vmarket.recentSearches.guest'))).toEqual(['áo dài'])
  })

  it('pre-fills the keyword on the search page', () => {
    renderAt(<HeaderSearch />, '/search?q=b%C3%A1nh%20m%C3%AC')
    expect(box()).toHaveValue('bánh mì')
  })

  it('debounces suggestions and asks only for the last keyword', async () => {
    renderAt(<HeaderSearch />)
    fireEvent.focus(box())
    type('á')
    type('áo')
    type('áo p')
    expect(suggestKeywords).not.toHaveBeenCalled()
    expect(await screen.findByRole('option', { name: /Áo polo nam/ })).toBeInTheDocument()
    expect(suggestKeywords).toHaveBeenCalledTimes(1)
    expect(suggestKeywords).toHaveBeenCalledWith(
      'áo p',
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(box()).toHaveAttribute('aria-expanded', 'true')
  })

  it('does not ask for suggestions under two characters', async () => {
    renderAt(<HeaderSearch />)
    fireEvent.focus(box())
    type('á')
    await act(() => new Promise((resolve) => setTimeout(resolve, 400)))
    expect(suggestKeywords).not.toHaveBeenCalled()
  })

  it('opens a suggested product', async () => {
    renderAt(<HeaderSearch />)
    fireEvent.focus(box())
    type('áo')
    fireEvent.click(await screen.findByRole('option', { name: /Áo thun unisex/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/products/p2')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('chooses a suggestion with the keyboard and closes on Escape', async () => {
    renderAt(<HeaderSearch />)
    fireEvent.focus(box())
    type('áo')
    await screen.findByRole('option', { name: /Áo polo nam/ })
    fireEvent.keyDown(box(), { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    fireEvent.keyDown(box(), { key: 'ArrowDown' })
    await screen.findByRole('listbox')
    fireEvent.keyDown(box(), { key: 'ArrowDown' })
    fireEvent.keyDown(box(), { key: 'ArrowDown' })
    expect(screen.getByRole('option', { name: /Áo thun unisex/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    fireEvent.keyDown(box(), { key: 'Enter' })
    expect(screen.getByTestId('where')).toHaveTextContent('/products/p2')
  })

  it('offers a direct search when there is no suggestion or the service fails', async () => {
    suggestKeywords.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('down'))
    renderAt(<HeaderSearch />)
    fireEvent.focus(box())
    type('zzz')
    expect(await screen.findByText('Chưa có gợi ý phù hợp.')).toBeInTheDocument()
    type('yyy')
    fireEvent.click(await screen.findByRole('button', { name: 'Xem kết quả tìm kiếm cho “yyy”' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/search?q=yyy')
  })

  it('shows recent searches per account and lets the buyer remove one', () => {
    localStorage.setItem('vmarket.recentSearches.u1', JSON.stringify(['cà phê', 'quạt mini']))
    localStorage.setItem('vmarket.recentSearches.guest', JSON.stringify(['khác']))
    renderAt(
      <AuthContext.Provider value={{ session: { userId: 'u1' } }}>
        <HeaderSearch />
      </AuthContext.Provider>,
    )
    fireEvent.focus(box())
    expect(screen.getByText('Tìm kiếm gần đây')).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /khác/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Xóa “cà phê” khỏi tìm kiếm gần đây' }))
    expect(screen.queryByRole('option', { name: /cà phê/ })).toBeNull()
    fireEvent.click(screen.getByRole('option', { name: /quạt mini/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/search?q=qu%E1%BA%A1t%20mini')
    expect(JSON.parse(localStorage.getItem('vmarket.recentSearches.u1'))).toEqual(['quạt mini'])
  })

  it('shows the camera button only where image search is available', () => {
    const open = vi.fn()
    const { unmount } = renderAt(<HeaderSearch />)
    expect(screen.queryByRole('button', { name: 'Tìm bằng ảnh' })).toBeNull()
    unmount()
    renderAt(
      <ImageSearchContext.Provider value={{ open }}>
        <HeaderSearch />
      </ImageSearchContext.Provider>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Tìm bằng ảnh' }))
    expect(open).toHaveBeenCalled()
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
