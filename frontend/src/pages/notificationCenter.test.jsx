// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import NotificationsPage from './NotificationsPage'
import NotificationSettingsPage from './NotificationSettingsPage'
import NotificationBell from '../components/NotificationBell'
import { NOTIFICATION_ARRIVED_EVENT, NOTIFICATIONS_CHANGED_EVENT } from '../notifications/events'
import {
  getNotificationPreferences,
  getUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationPreferences,
} from '../services/api/notificationApi'

vi.mock('../services/api/notificationApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getNotificationPreferences: vi.fn(),
  getUnreadCount: vi.fn(),
  listNotifications: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  markNotificationRead: vi.fn(),
  updateNotificationPreferences: vi.fn(),
}))

const auth = {
  session: {
    userId: 'u1',
    username: 'an',
    email: 'an@test.vn',
    status: 'ACTIVE',
    roles: ['BUYER'],
  },
  bootstrapState: 'ready',
  userProfile: { fullName: 'An' },
}

const items = [
  {
    id: 'n1',
    title: 'Đơn đã giao thành công',
    message: 'Đơn #A1 đã được giao.',
    link: '/orders/o1',
    read: false,
    createdAt: '2026-11-09T08:00:00Z',
  },
  {
    id: 'n2',
    title: 'Thanh toán thành công',
    link: '/orders/o2',
    read: true,
    createdAt: '2026-11-08T08:00:00Z',
  },
]
const page = (content, totalPages = 1) => ({ items: content, page: 0, size: 15, totalPages })
const preferences = {
  categories: [
    { key: 'ORDER', label: 'Đơn hàng & giao hàng', email: true, push: true },
    { key: 'PAYMENT', label: 'Thanh toán & hoàn tiền', email: true, push: true },
    { key: 'SHOP', label: 'Gian hàng & sản phẩm', email: true, push: true },
  ],
}

const renderAt = (path, extra = null) =>
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[path]}>
        {extra}
        <Routes>
          <Route path="/profile/notifications" element={<NotificationsPage />} />
          <Route path="/profile/notifications/settings" element={<NotificationSettingsPage />} />
          <Route path="/profile/orders/:orderId" element={<p>Chi tiết đơn</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  listNotifications.mockResolvedValue(page(items, 3))
  markNotificationRead.mockResolvedValue({})
  markAllNotificationsRead.mockResolvedValue({ updated: 1 })
  getUnreadCount.mockResolvedValue(0)
  getNotificationPreferences.mockResolvedValue(preferences)
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('NotificationsPage', () => {
  it('lists notifications, filters unread and paginates', async () => {
    renderAt('/profile/notifications')
    expect(await screen.findByText('Đơn đã giao thành công')).toBeInTheDocument()
    expect(screen.getByText(/· Chưa đọc/)).toBeInTheDocument()
    expect(listNotifications).toHaveBeenLastCalledWith({ page: 0, size: 15, unreadOnly: false })
    fireEvent.click(screen.getByRole('button', { name: 'Chưa đọc' }))
    await waitFor(() =>
      expect(listNotifications).toHaveBeenLastCalledWith({ page: 0, size: 15, unreadOnly: true }),
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Trang 2' }))
    await waitFor(() =>
      expect(listNotifications).toHaveBeenLastCalledWith({ page: 1, size: 15, unreadOnly: true }),
    )
  })

  it('marks a notification read, tells the bell and opens the order', async () => {
    const changed = vi.fn()
    window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, changed)
    renderAt('/profile/notifications')
    fireEvent.click(await screen.findByRole('button', { name: /Đơn đã giao thành công/ }))
    expect(await screen.findByText('Chi tiết đơn')).toBeInTheDocument()
    expect(markNotificationRead).toHaveBeenCalledWith('n1')
    expect(changed).toHaveBeenCalled()
    window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, changed)
  })

  it('marks everything read and reloads when a new notification arrives', async () => {
    renderAt('/profile/notifications')
    await screen.findByText('Đơn đã giao thành công')
    fireEvent.click(screen.getByRole('button', { name: 'Đánh dấu đã đọc tất cả' }))
    await waitFor(() => expect(markAllNotificationsRead).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByText(/· Chưa đọc/)).not.toBeInTheDocument())
    const calls = listNotifications.mock.calls.length
    act(() => {
      window.dispatchEvent(new Event(NOTIFICATION_ARRIVED_EVENT))
    })
    await waitFor(() => expect(listNotifications.mock.calls.length).toBe(calls + 1))
  })

  it('shows the empty state', async () => {
    listNotifications.mockResolvedValue(page([]))
    renderAt('/profile/notifications')
    expect(await screen.findByText('Bạn chưa có thông báo nào.')).toBeInTheDocument()
  })
})

describe('NotificationSettingsPage', () => {
  it('shows only email switches on a computer', async () => {
    renderAt('/profile/notifications/settings')
    expect(
      await screen.findByRole('switch', { name: 'Email – Đơn hàng & giao hàng' }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('switch')).toHaveLength(3)
    expect(screen.queryByRole('switch', { name: /Thông báo đẩy/ })).not.toBeInTheDocument()
  })

  it('saves email and push switches per category on a phone', async () => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36',
    )
    updateNotificationPreferences.mockImplementation(async (categories) => ({ categories }))
    renderAt('/profile/notifications/settings')
    const save = await screen.findByRole('button', { name: 'Lưu cài đặt' })
    expect(save).toBeDisabled()
    const emailOrders = screen.getByRole('switch', { name: 'Email – Đơn hàng & giao hàng' })
    expect(emailOrders).toBeChecked()
    fireEvent.click(emailOrders)
    fireEvent.click(screen.getByRole('switch', { name: 'Thông báo đẩy – Gian hàng & sản phẩm' }))
    expect(save).toBeEnabled()
    fireEvent.click(save)
    expect(await screen.findByText('Đã lưu cài đặt thông báo.')).toBeInTheDocument()
    expect(updateNotificationPreferences).toHaveBeenCalledWith([
      expect.objectContaining({ key: 'ORDER', email: false, push: true }),
      expect.objectContaining({ key: 'PAYMENT', email: true, push: true }),
      expect.objectContaining({ key: 'SHOP', email: true, push: false }),
    ])
    expect(screen.getByRole('button', { name: 'Lưu cài đặt' })).toBeDisabled()
  })

  it('reports a failed save', async () => {
    updateNotificationPreferences.mockRejectedValue({
      response: { status: 500, data: { error: { code: 'X', message: 'Không lưu được' } } },
    })
    renderAt('/profile/notifications/settings')
    fireEvent.click(await screen.findByRole('switch', { name: 'Email – Thanh toán & hoàn tiền' }))
    fireEvent.click(screen.getByRole('button', { name: 'Lưu cài đặt' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Không lưu được')
  })
})

describe('NotificationBell polling', () => {
  it('polls every 30 seconds and announces new notifications', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const arrived = vi.fn()
    window.addEventListener(NOTIFICATION_ARRIVED_EVENT, arrived)
    getUnreadCount.mockResolvedValueOnce(1).mockResolvedValue(2)
    renderAt('/profile/notifications/settings', <NotificationBell />)
    expect(await screen.findByRole('button', { name: 'Thông báo, 1 chưa đọc' })).toBeInTheDocument()
    expect(arrived).not.toHaveBeenCalled()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_100)
    })
    expect(await screen.findByRole('button', { name: 'Thông báo, 2 chưa đọc' })).toBeInTheDocument()
    expect(arrived).toHaveBeenCalledTimes(1)
    window.removeEventListener(NOTIFICATION_ARRIVED_EVENT, arrived)
  })

  it('links to the notification center', async () => {
    listNotifications.mockResolvedValue(page(items))
    renderAt('/profile/notifications/settings', <NotificationBell />)
    fireEvent.click(await screen.findByRole('button', { name: 'Thông báo' }))
    const panel = screen.getByRole('dialog', { name: 'Thông báo' })
    expect(within(panel).getByRole('link', { name: 'Xem tất cả thông báo' })).toHaveAttribute(
      'href',
      '/profile/notifications',
    )
  })
})
