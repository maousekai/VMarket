import type { Href } from 'expo-router'

import type { NotificationType } from '@/api/notifications'

export type NotificationRole = 'buyer' | 'shipper'

/** Order-related notifications a buyer can open (seller types have no mobile screen). */
const BUYER_ORDER_TYPES: ReadonlySet<string> = new Set<NotificationType>([
  'ORDER_PLACED',
  'PAYMENT_SUCCEEDED',
  'PAYMENT_FAILED',
  'ORDER_STATUS_CHANGED',
  'DELIVERY_FAILED',
  'RETURN_RESOLVED',
])

const SAFE_ID = /^[A-Za-z0-9-]{1,64}$/

/** Push payloads are untrusted input: only plain ids may reach a route path. */
const safeId = (id: string | undefined): string | null =>
  id && SAFE_ID.test(id) ? encodeURIComponent(id) : null

/**
 * Where a notification leads. `data` is the notification payload (the same keys arrive in the push
 * `data`). Returns null when the notification has no screen in the mobile app.
 */
export function notificationHref(
  role: NotificationRole,
  type: string | undefined,
  data: Record<string, string | undefined>,
): Href | null {
  if (role === 'shipper') {
    const id = safeId(data.orderId)
    return type === 'DELIVERY_ASSIGNED' && id ? (`/(shipper)/tasks/${id}` as Href) : null
  }
  const id = safeId(data.orderId)
  if (type && BUYER_ORDER_TYPES.has(type) && id) {
    return `/(buyer)/orders/${id}` as Href
  }
  return null
}
