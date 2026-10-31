// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import Modal from './Modal'

afterEach(cleanup)

describe('Modal keyboard handling', () => {
  it('focuses the dialog instead of the first destructive action by default', () => {
    render(
      <Modal title="Xóa địa chỉ?" onClose={vi.fn()}>
        <button type="button">Xóa địa chỉ</button>
        <button type="button">Hủy</button>
      </Modal>,
    )
    expect(document.activeElement).toBe(screen.getByRole('dialog'))
  })

  it('honours data-autofocus and traps Tab across visible tabbable controls', () => {
    render(
      <Modal title="Xóa địa chỉ?" onClose={vi.fn()}>
        <button type="button" data-autofocus>
          Hủy
        </button>
        <button type="button">Xóa địa chỉ</button>
        <div tabIndex={0}>Vùng tùy chỉnh</div>
        <button type="button" style={{ display: 'none' }}>
          Nút ẩn
        </button>
      </Modal>,
    )
    const dialog = screen.getByRole('dialog')
    const cancel = screen.getByRole('button', { name: 'Hủy' })
    const custom = screen.getByText('Vùng tùy chỉnh')

    expect(document.activeElement).toBe(cancel)

    custom.focus()
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(document.activeElement).toBe(cancel)

    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(custom)
  })

  it('closes on Escape', () => {
    const onClose = vi.fn()
    render(
      <Modal title="Xóa địa chỉ?" onClose={onClose}>
        <button type="button">Hủy</button>
      </Modal>,
    )
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
