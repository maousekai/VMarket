// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import NotificationBell from './NotificationBell'
import {
  getUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationRoute,
} from '../services/api/notificationApi'

vi.mock('../services/api/notificationApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getUnreadCount: vi.fn(),
  listNotifications: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  markNotificationRead: vi.fn(),
}))

const items = [
  {
    id: 'n1',
    type: 'ORDER_STATUS_CHANGED',
    title: 'Đơn đang được giao',
    message: 'Đơn #A1B2C3D4 đã bàn giao cho đơn vị vận chuyển.',
    link: '/orders/o1',
    read: false,
    createdAt: '2026-11-09T08:00:00Z',
  },
  {
    id: 'n2',
    type: 'NEW_ORDER',
    title: 'Bạn có đơn hàng mới',
    link: '/seller/orders/o2',
    read: true,
    createdAt: '2026-11-08T08:00:00Z',
  },
]

const renderBell = () =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<NotificationBell />} />
        <Route path="/profile/orders/:orderId" element={<p>Chi tiết đơn</p>} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  getUnreadCount.mockResolvedValue(1)
  listNotifications.mockResolvedValue({ items, totalElements: 2 })
  markNotificationRead.mockResolvedValue({})
  markAllNotificationsRead.mockResolvedValue({ updated: 1 })
})

describe('notificationRoute', () => {
  it('maps service links to web routes', () => {
    expect(notificationRoute('/orders/o1')).toBe('/profile/orders/o1')
    expect(notificationRoute('/seller/orders/o2')).toBe('/seller/orders')
    expect(notificationRoute('/seller/products/p1')).toBe('/seller/products/p1/edit')
    expect(notificationRoute('/seller')).toBe('/seller')
    expect(notificationRoute('/shipper/assignments/a1')).toBe('')
    expect(notificationRoute('https://evil.example')).toBe('')
  })
})

describe('NotificationBell', () => {
  it('shows the unread count and lists the latest notifications', async () => {
    renderBell()
    const trigger = await screen.findByRole('button', { name: 'Thông báo, 1 chưa đọc' })
    fireEvent.click(trigger)
    const panel = screen.getByRole('dialog', { name: 'Thông báo' })
    expect(await within(panel).findByText('Đơn đang được giao')).toBeInTheDocument()
    expect(within(panel).getByText('Bạn có đơn hàng mới')).toBeInTheDocument()
    expect(listNotifications).toHaveBeenCalledWith({ size: 10 })
  })

  it('marks a notification read and opens the related order', async () => {
    renderBell()
    fireEvent.click(await screen.findByRole('button', { name: 'Thông báo, 1 chưa đọc' }))
    fireEvent.click(await screen.findByRole('button', { name: /Đơn đang được giao/ }))
    expect(await screen.findByText('Chi tiết đơn')).toBeInTheDocument()
    expect(markNotificationRead).toHaveBeenCalledWith('n1')
  })

  it('marks everything as read', async () => {
    renderBell()
    fireEvent.click(await screen.findByRole('button', { name: 'Thông báo, 1 chưa đọc' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Đánh dấu đã đọc tất cả' }))
    await waitFor(() => expect(markAllNotificationsRead).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: 'Thông báo' })).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    getUnreadCount.mockResolvedValue(0)
    listNotifications.mockResolvedValue({ items: [] })
    renderBell()
    fireEvent.click(screen.getByRole('button', { name: 'Thông báo' }))
    expect(await screen.findByText('Bạn chưa có thông báo nào.')).toBeInTheDocument()
  })
})
