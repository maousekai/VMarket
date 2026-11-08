import type { Assignment, PickupRow } from '@/api/delivery'
import { formatVnd } from '@/lib/format'

export interface OrderItem {
  productId: string
  variantId: string
  shopId: string
  quantity: number
}

export interface Receiver {
  name: string
  phone: string
  /** Full address: street, ward, district, province. */
  address: string
  /** Short address: street, district. */
  shortAddress: string
  district: string
}

export interface ParsedOrder {
  receiver: Receiver
  items: OrderItem[]
}

export interface PickupShop {
  shopId: string
  name: string
  address: string
  phone: string
}

const str = (v: unknown) => (typeof v === 'string' ? v.trim() : '')
const join = (parts: unknown[]) => parts.map(str).filter(Boolean).join(', ')

function parseJson(raw: unknown): Record<string, unknown> {
  if (raw && typeof raw === 'object') return raw as Record<string, unknown>
  if (typeof raw !== 'string') return {}
  try {
    const value: unknown = JSON.parse(raw)
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** Reads the Order-service snapshot (JSON string or object) defensively. */
export function parseOrder(raw: unknown): ParsedOrder {
  const o = parseJson(raw)
  const items = Array.isArray(o.items) ? (o.items as Record<string, unknown>[]) : []
  return {
    receiver: {
      name: str(o.recipientName),
      phone: str(o.phone),
      address: join([o.streetAddress, o.ward, o.district, o.province]),
      shortAddress: join([o.streetAddress, o.district]),
      district: str(o.district),
    },
    items: items.map((i) => ({
      productId: str(i.productId),
      variantId: str(i.variantId),
      shopId: str(i.shopId),
      quantity: Number(i.quantity) || 1,
    })),
  }
}

export function parsePickup(row: PickupRow): PickupShop {
  const s = parseJson(row.pickup_snapshot)
  return {
    shopId: row.shop_id,
    name: str(s.name) || 'Gian hàng',
    address: join([s.streetAddress, s.ward, s.district, s.province]),
    phone: str(s.contactPhone),
  }
}

/** The backend has no display code for an order; use the tail of its ULID. */
export function orderCode(orderId: string): string {
  return `#VM-${orderId.slice(-8).toUpperCase()}`
}

export const isPrepaid = (a: Pick<Assignment, 'codAmount' | 'paymentMethod'>) =>
  a.paymentMethod !== 'COD' || a.codAmount === 0

/** "#VM-... · COD 135.000đ" or "#VM-... · Đã thanh toán". */
export function orderLine(a: Assignment): string {
  return `${orderCode(a.orderId)} · ${isPrepaid(a) ? 'Đã thanh toán' : `COD ${formatVnd(a.codAmount)}`}`
}
