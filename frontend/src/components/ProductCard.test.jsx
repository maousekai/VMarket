// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import ProductCard from './ProductCard'
import ProductGrid from './ProductGrid'

const product = {
  id: 'p-1',
  name: 'Giày Thể Thao Nữ Pastel',
  imageUrls: ['https://cdn.example.com/p1.jpg'],
  minPrice: 459000,
  maxPrice: 459000,
  currency: 'VND',
  ratingAverage: 4.9,
  ratingCount: 203,
}

const renderInRouter = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>)

describe('ProductCard', () => {
  it('shows name, rating and VND price with a link to the product', () => {
    renderInRouter(<ProductCard product={product} />)
    const link = screen.getByRole('link', { name: product.name })
    expect(link).toHaveAttribute('href', '/products/p-1')
    expect(screen.getByText('4,9')).toBeInTheDocument()
    expect(screen.getByText('(203 đánh giá)')).toBeInTheDocument()
    expect(screen.getByText(/459\.000\s₫/)).toBeInTheDocument()
  })

  it('prefixes "Từ" for a price range and handles products without reviews', () => {
    renderInRouter(<ProductCard product={{ ...product, maxPrice: 600000, ratingCount: 0 }} />)
    expect(screen.getByText('Từ')).toBeInTheDocument()
    expect(screen.getByText('Chưa có đánh giá')).toBeInTheDocument()
  })

  it('falls back to a placeholder when the image is missing or broken', () => {
    const { container, rerender } = renderInRouter(<ProductCard product={product} />)
    fireEvent.error(container.querySelector('img'))
    expect(container.querySelector('.product-card__placeholder')).toBeInTheDocument()
    rerender(
      <MemoryRouter>
        <ProductCard product={{ ...product, id: 'p-2', imageUrls: [] }} />
      </MemoryRouter>,
    )
    expect(container.querySelector('img')).toBeNull()
  })
})

describe('ProductGrid', () => {
  it('renders skeletons while loading', () => {
    renderInRouter(<ProductGrid status="loading" skeletonCount={3} />)
    expect(screen.getByRole('status', { name: 'Đang tải sản phẩm' })).toBeInTheDocument()
  })

  it('renders the empty message', () => {
    renderInRouter(<ProductGrid status="ready" products={[]} emptyMessage="Trống" />)
    expect(screen.getByText('Trống')).toBeInTheDocument()
  })

  it('renders an error with a retry button', () => {
    let retried = false
    renderInRouter(
      <ProductGrid
        status="error"
        error={{ response: { status: 503, data: { error: { message: 'Dịch vụ bận' } } } }}
        onRetry={() => (retried = true)}
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Không tải được sản phẩm. Dịch vụ bận')
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(retried).toBe(true)
  })

  it('renders one list item per product', () => {
    renderInRouter(<ProductGrid status="ready" products={[product, { ...product, id: 'p-2' }]} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })
})
