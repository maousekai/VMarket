// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import HeroCarousel from './HeroCarousel'

const slides = [
  { id: 'a', title: ['Slide một'], description: 'd1', cta: 'Mua A', to: '/a' },
  { id: 'b', title: ['Slide hai'], description: 'd2', cta: 'Mua B', to: '/b' },
  { id: 'c', title: ['Slide ba'], description: 'd3', cta: 'Mua C', to: '/c' },
]

const renderCarousel = (items = slides) =>
  render(
    <MemoryRouter>
      <HeroCarousel slides={items} />
    </MemoryRouter>,
  )

describe('HeroCarousel', () => {
  it('shows the first slide with its call to action', () => {
    renderCarousel()
    expect(screen.getByRole('heading', { name: 'Slide một' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mua A' })).toHaveAttribute('href', '/a')
    expect(screen.getByRole('group', { name: '1 / 3' })).toBeInTheDocument()
  })

  it('moves with the arrow buttons and wraps around', () => {
    renderCarousel()
    fireEvent.click(screen.getByRole('button', { name: 'Banner tiếp theo' }))
    expect(screen.getByRole('heading', { name: 'Slide hai' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Banner trước' }))
    fireEvent.click(screen.getByRole('button', { name: 'Banner trước' }))
    expect(screen.getByRole('heading', { name: 'Slide ba' })).toBeInTheDocument()
  })

  it('jumps with the dots and marks the current one', () => {
    renderCarousel()
    const dot = screen.getByRole('button', { name: 'Chuyển tới banner 3' })
    fireEvent.click(dot)
    expect(dot).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('heading', { name: 'Slide ba' })).toBeInTheDocument()
  })

  it('supports the arrow keys', () => {
    renderCarousel()
    const region = screen.getByRole('region', { name: 'Nổi bật hôm nay' })
    fireEvent.keyDown(region, { key: 'ArrowRight' })
    expect(screen.getByRole('heading', { name: 'Slide hai' })).toBeInTheDocument()
    fireEvent.keyDown(region, { key: 'ArrowLeft' })
    expect(screen.getByRole('heading', { name: 'Slide một' })).toBeInTheDocument()
  })

  it('hides navigation for a single slide and renders nothing without slides', () => {
    const { container, rerender } = renderCarousel([slides[0]])
    expect(screen.queryByRole('button')).toBeNull()
    rerender(
      <MemoryRouter>
        <HeroCarousel slides={[]} />
      </MemoryRouter>,
    )
    expect(container).toBeEmptyDOMElement()
  })
})
