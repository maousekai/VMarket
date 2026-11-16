import apiClient from './apiClient'

// Notification Service: thong bao trong ung dung cua nguoi dung dang dang nhap.
const baseUrl = '/api/notifications'

/** Trang thong bao moi nhat truoc: {items, page, size, totalElements, totalPages}. */
export const listNotifications = async ({ page = 0, size = 10, unreadOnly = false } = {}) =>
  (await apiClient.get(baseUrl, { params: { page, size, unreadOnly } })).data

export const getUnreadCount = async () =>
  (await apiClient.get(`${baseUrl}/unread-count`)).data?.count ?? 0

export const markNotificationRead = async (id) =>
  (await apiClient.patch(`${baseUrl}/${encodeURIComponent(id)}/read`)).data

export const markAllNotificationsRead = async () =>
  (await apiClient.patch(`${baseUrl}/read-all`)).data

/** Tuy chon email / push theo nhom: {categories: [{key, label, email, push}]}. */
export const getNotificationPreferences = async () =>
  (await apiClient.get(`${baseUrl}/preferences`)).data

export const updateNotificationPreferences = async (categories) =>
  (
    await apiClient.put(`${baseUrl}/preferences`, {
      categories: categories.map(({ key, email, push }) => ({ key, email, push })),
    })
  ).data

/**
 * Link do Notification Service dung (dung chung cho email/push) -> route cua web.
 * Tra '' khi web chua co trang tuong ung.
 */
export function notificationRoute(link) {
  if (!link || typeof link !== 'string' || !link.startsWith('/')) return ''
  const [, first, second, third] = link.split('/')
  if (first === 'orders' && second) return `/profile/orders/${second}`
  if (first === 'seller' && second === 'orders') return '/seller/orders'
  if (first === 'seller' && second === 'products' && third) return `/seller/products/${third}/edit`
  if (first === 'seller') return '/seller'
  return ''
}
