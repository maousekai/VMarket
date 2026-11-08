import type { Order, OrderItem } from '@/api/orders'
import { formatVnd } from '@/lib/format'

/** Backend has no human order code (backend gap): `#VM-` + last 8 chars of the ULID. */
export const orderCode = (orderId: string) => `#VM-${orderId.slice(-8).toUpperCase()}`

/** Return display code: `TH-` + last 4 chars of the ULID. */
export const returnCode = (returnId: string) => `TH-${returnId.slice(-4).toUpperCase()}`

export function orderSubtotal(order: Pick<Order, 'items'>): number {
  return order.items.reduce((sum, it) => sum + Number(it.lineTotal), 0)
}

/** Backend only exposes totalAmount; any gap to the line sum is shown as an adjustment. */
export function totalBreakdown(order: Pick<Order, 'items' | 'totalAmount'>): string {
  const total = Number(order.totalAmount)
  const sub = orderSubtotal(order)
  const diff = Math.round(total - sub)
  const plain = (n: number) => formatVnd(n).replace('đ', '')
  if (diff === 0) return `Tổng cộng: ${formatVnd(total)}`
  const sign = diff > 0 ? '+' : '−'
  return `${plain(sub)} ${sign} ${plain(Math.abs(diff))} = ${formatVnd(total)}`
}

export function addressLine(order: Order): string {
  return [order.streetAddress, order.ward, order.district, order.province]
    .filter(Boolean)
    .join(', ')
}

/** Stable key of an order line (orderItemId when the API sends it). */
export const itemKey = (i: Pick<OrderItem, 'id' | 'productId' | 'variantId'>) =>
  i.id ?? `${i.productId}:${i.variantId ?? ''}`

export interface ItemLabel {
  name: string
  variant: string
}

/** Two-line item format of the design: name / `variant ×qty · total`. */
export function itemDetailLine(item: OrderItem, variant: string): string {
  const head = variant ? `${variant} ×${item.quantity}` : `×${item.quantity}`
  return `${head} · ${formatVnd(item.lineTotal)}`
}

/** Single-line item format used by pickers: `Name / Variant · 249.000đ`. */
export function itemOneLine(item: OrderItem, label: ItemLabel): string {
  const name = label.variant ? `${label.name} / ${label.variant}` : label.name
  return `${name} · ${formatVnd(item.lineTotal)}`
}

const DAY_MS = 86_400_000

/** Whole days left in a window that started at `from` and lasts `days`; null when `from` is unknown. */
export function daysLeft(from: string | null | undefined, days: number, now = Date.now()) {
  if (!from) return null
  const start = new Date(from).getTime()
  if (Number.isNaN(start)) return null
  return Math.ceil((start + days * DAY_MS - now) / DAY_MS)
}

export const addDays = (from: string, days: number) =>
  new Date(new Date(from).getTime() + days * DAY_MS)

/** 'dd/MM' */
export function shortDate(value: string | number | Date | null | undefined): string {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** 'dd/MM HH:mm' */
export function shortDateTime(value: string | number | Date | null | undefined): string {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return `${shortDate(d)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
