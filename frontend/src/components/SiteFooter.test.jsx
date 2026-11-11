// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import SiteFooter from './SiteFooter'

describe('SiteFooter', () => {
  it('renders working links, payment methods and the six districts', () => {
    render(
      <MemoryRouter>
        <SiteFooter />
      </MemoryRouter>,
    )
    const footer = screen.getByRole('contentinfo')
    expect(within(footer).getByRole('link', { name: 'Về chúng tôi' })).toHaveAttribute(
      'href',
      '/about',
    )
    expect(within(footer).getByRole('link', { name: 'Trở thành nhà bán' })).toHaveAttribute(
      'href',
      '/seller/register',
    )
    expect(within(footer).getByText('VNPay')).toBeInTheDocument()
    expect(within(footer).getByText('COD')).toBeInTheDocument()
    const districts = within(footer).getByRole('heading', {
      name: 'Khu vực phục vụ',
    }).nextElementSibling
    expect(within(districts).getAllByRole('listitem')).toHaveLength(6)
    expect(footer).toHaveTextContent(String(new Date().getFullYear()))
  })
})
