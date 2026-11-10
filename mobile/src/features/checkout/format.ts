import type { Address, Order, OrderStatus } from '@/api/checkout'
import { formatPhone } from '@/lib/format'

const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  WAITING_PAYMENT: 'Chờ thanh toán',
  PENDING: 'Chờ xác nhận',
  PROCESSING: 'Đang chuẩn bị',
  SHIPPED: 'Đang giao',
  DELIVERED: 'Đã giao',
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã hủy',
}

export const orderStatusLabel = (status: OrderStatus) => ORDER_STATUS_LABEL[status] ?? status

/** The backend has no human-readable order code: use the tail of the ULID. */
export const orderCode = (orderId: string) => `#${orderId.slice(-8).toUpperCase()}`

export function variantLabel(attributes?: Record<string, string>): string {
  return attributes ? Object.values(attributes).join(' / ') : ''
}

export function addressLine(a: Pick<Address, 'streetAddress' | 'ward' | 'district' | 'province'>) {
  return [a.streetAddress, a.ward, a.district, a.province].filter(Boolean).join(', ')
}

export const recipientLine = (a: Pick<Address, 'recipientName' | 'phone'>) =>
  `${a.recipientName} · ${formatPhone(a.phone)}`

/** True once the payment window of a PayOS order is over. */
export function isPaymentWindowOver(order: Pick<Order, 'paymentExpiresAt'>, now = Date.now()) {
  return !!order.paymentExpiresAt && new Date(order.paymentExpiresAt).getTime() <= now
}

/** Splits ids from a route param (`a,b`). */
export const parseIds = (value: string | string[] | undefined): string[] =>
  (Array.isArray(value) ? value.join(',') : (value ?? '')).split(',').filter(Boolean)
