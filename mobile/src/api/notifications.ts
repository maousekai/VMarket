import { api } from '@/api/client'

export type NotificationType =
  | 'ORDER_PLACED'
  | 'NEW_ORDER'
  | 'PAYMENT_SUCCEEDED'
  | 'PAYMENT_FAILED'
  | 'ORDER_STATUS_CHANGED'
  | 'ORDER_CANCELLED'
  | 'ORDER_DELIVERED'
  | 'DELIVERY_ASSIGNED'
  | 'DELIVERY_FAILED'
  | 'SHOP_APPROVED'
  | 'SHOP_REINSTATED'
  | 'SHOP_SUSPENDED'
  | 'PRODUCT_REMOVED'
  | 'PRODUCT_RESTORED'
  | 'RETURN_RESOLVED'

export interface AppNotification {
  id: string
  type: NotificationType
  title: string
  message: string
  link: string | null
  data: Record<string, string>
  read: boolean
  readAt: string | null
  createdAt: string
}

export interface NotificationPage {
  items: AppNotification[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export const NOTIFICATION_PAGE_SIZE = 20

export async function fetchNotifications(page: number) {
  return (
    await api.get<NotificationPage>('/api/notifications', {
      params: { page, size: NOTIFICATION_PAGE_SIZE },
    })
  ).data
}

export async function fetchUnreadCount() {
  return (await api.get<{ count: number }>('/api/notifications/unread-count')).data.count
}

export async function markNotificationRead(id: string) {
  return (await api.patch<AppNotification>(`/api/notifications/${id}/read`)).data
}

export async function markAllNotificationsRead() {
  return (await api.patch<{ updated: number }>('/api/notifications/read-all')).data.updated
}
