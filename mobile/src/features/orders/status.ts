import type { Order, OrderStatus } from '@/api/orders'
import type { ReturnStatus } from '@/api/returns'
import type { DeliveryStatus } from '@/api/tracking'
import type { Tone } from '@/theme'

export interface StatusInfo {
  label: string
  tone: Tone
}

const ORDER_STATUS: Record<OrderStatus, StatusInfo> = {
  WAITING_PAYMENT: { label: 'Chờ thanh toán', tone: 'warning' },
  PENDING: { label: 'Chờ xác nhận', tone: 'warning' },
  PROCESSING: { label: 'Đã xác nhận', tone: 'info' },
  SHIPPED: { label: 'Đang giao', tone: 'transit' },
  DELIVERED: { label: 'Đã giao', tone: 'success' },
  COMPLETED: { label: 'Hoàn thành', tone: 'success' },
  CANCELLED: { label: 'Đã hủy', tone: 'danger' },
}

export function orderStatusInfo(status: string): StatusInfo {
  return ORDER_STATUS[status as OrderStatus] ?? { label: status, tone: 'neutral' }
}

export const CANCELLABLE: OrderStatus[] = ['WAITING_PAYMENT', 'PENDING']

export const isCancellable = (status: string) => CANCELLABLE.includes(status as OrderStatus)

const RETURN_STATUS: Record<ReturnStatus, StatusInfo> = {
  PENDING: { label: 'Chờ người bán xử lý', tone: 'warning' },
  APPROVED: { label: 'Người bán đã chấp nhận', tone: 'info' },
  REJECTED: { label: 'Người bán từ chối', tone: 'danger' },
  DISPUTED: { label: 'Chờ Admin xử lý', tone: 'warning' },
  RESOLVED: { label: 'Đã hoàn tiền', tone: 'success' },
  CANCELLED: { label: 'Đã hủy yêu cầu', tone: 'neutral' },
  ADMIN_REJECTED: { label: 'Admin từ chối', tone: 'danger' },
}

export function returnStatusInfo(status: string): StatusInfo {
  return RETURN_STATUS[status as ReturnStatus] ?? { label: 'Đang xử lý', tone: 'neutral' }
}

const DELIVERY_STATUS: Partial<Record<DeliveryStatus, string>> = {
  WAITING_HANDOFF: 'Chờ người bán bàn giao',
  WAITING_ASSIGNMENT: 'Chờ phân công shipper',
  ASSIGNED: 'Đã phân công shipper',
  PICKED_UP: 'Shipper đã lấy hàng',
  IN_TRANSIT: 'Đang giao',
  FAILED: 'Giao không thành công',
  RETURNING: 'Đang hoàn hàng',
  RETURNED: 'Đã hoàn hàng',
  DELIVERED: 'Giao thành công',
}

export const deliveryStatusLabel = (status: string) =>
  DELIVERY_STATUS[status as DeliveryStatus] ?? status

/** Filter chips of the order list. `statuses: null` = all. */
export const ORDER_FILTERS: { key: string; label: string; statuses: OrderStatus[] | null }[] = [
  { key: 'all', label: 'Tất cả', statuses: null },
  { key: 'pay', label: 'Chờ thanh toán', statuses: ['WAITING_PAYMENT'] },
  { key: 'pending', label: 'Chờ xác nhận', statuses: ['PENDING'] },
  { key: 'processing', label: 'Đã xác nhận', statuses: ['PROCESSING'] },
  { key: 'shipped', label: 'Đang giao', statuses: ['SHIPPED'] },
  { key: 'delivered', label: 'Đã giao', statuses: ['DELIVERED'] },
  { key: 'completed', label: 'Hoàn thành', statuses: ['COMPLETED'] },
  { key: 'cancelled', label: 'Đã hủy', statuses: ['CANCELLED'] },
]

/** PAYOS orders are paid from PENDING on; WAITING_PAYMENT is the only unpaid PAYOS state. */
export function paymentLine(order: Pick<Order, 'paymentMethod' | 'status'>): string {
  if (order.paymentMethod !== 'PAYOS') return 'Thanh toán khi nhận hàng'
  return order.status === 'WAITING_PAYMENT' ? 'Chờ thanh toán PayOS' : 'Đã thanh toán PayOS'
}
