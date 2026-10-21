// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import AccountField from './AccountField'

afterEach(cleanup)

describe('AccountField password toggle', () => {
  it('shows exactly one show/hide button on a password field and toggles the input type', () => {
    render(<AccountField label="Mật khẩu mới" type="password" defaultValue="Secret@123" />)
    const input = screen.getByLabelText(/^Mật khẩu mới/)
    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(1)
    expect(buttons[0].getAttribute('aria-label')).toBe('Hiện mật khẩu mới')
    expect(input.type).toBe('password')
    fireEvent.click(buttons[0])
    expect(input.type).toBe('text')
    expect(buttons[0].getAttribute('aria-label')).toBe('Ẩn mật khẩu mới')
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(buttons[0])
    expect(input.type).toBe('password')
  })

  it('has no toggle on other kinds of fields', () => {
    render(
      <>
        <AccountField label="Họ tên" />
        <AccountField label="Giới tính" as="select">
          <option value="">Chưa chọn</option>
        </AccountField>
      </>,
    )
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByLabelText('Họ tên').type).toBe('text')
  })

  it('keeps the error and hint wiring', () => {
    render(<AccountField label="Mật khẩu" type="password" error="Sai rồi" hint="Gợi ý" />)
    const input = screen.getByLabelText('Mật khẩu')
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(input.getAttribute('aria-describedby')).toBe(screen.getByText('Sai rồi').id)
    expect(screen.queryByText('Gợi ý')).toBeNull()
  })
})
