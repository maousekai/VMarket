// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import FeaturedShops from './FeaturedShops'

const shops = [
  {
    id: 's1',
    name: 'Bánh Mì Cô Ba',
    province: 'Đà Nẵng',
    description: 'Bánh mì ngon',
    coverUrl: 'https://cdn.example.com/s1.jpg',
    approvedAt: '2025-03-01T00:00:00Z',
  },
  { id: 's2', name: 'Shop Thời Trang', province: 'Đà Nẵng' },
]

const renderShops = (props) =>
  render(
    <MemoryRouter>
      <FeaturedShops title="Cửa Hàng Nổi Bật" {...props} />
    </MemoryRouter>,
  )

describe('FeaturedShops', () => {
  it('lists shops with their area, year and a link to the shop page', () => {
    renderShops({ status: 'ready', shops })
    expect(screen.getByRole('heading', { name: 'Cửa Hàng Nổi Bật' })).toBeInTheDocument()
    expect(screen.getByText('Đà Nẵng · Trên VMarket từ 2025')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem gian hàng Bánh Mì Cô Ba' })).toHaveAttribute(
      'href',
      '/shops/s1',
    )
    expect(screen.getByRole('button', { name: 'Gian hàng tiếp theo' })).toBeInTheDocument()
  })

  it('shows skeletons while loading', () => {
    renderShops({ status: 'loading' })
    expect(screen.getByRole('status', { name: 'Đang tải gian hàng' })).toBeInTheDocument()
  })

  it('shows an error with retry', () => {
    const onRetry = vi.fn()
    renderShops({ status: 'error', error: { message: 'x' }, onRetry })
    expect(screen.getByRole('alert')).toHaveTextContent('Không tải được gian hàng.')
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('hides the whole section when there are no shops', () => {
    const { container } = renderShops({ status: 'ready', shops: [] })
    expect(container).toBeEmptyDOMElement()
  })
})
