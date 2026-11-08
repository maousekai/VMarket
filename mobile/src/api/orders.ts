import { api } from '@/api/client'

export type OrderStatus =
  'WAITING_PAYMENT' | 'PENDING' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED' | 'COMPLETED' | 'CANCELLED'

export interface OrderItem {
  /** orderItemId, needed to create a return. */
  id?: string
  productId: string
  variantId: string | null
  shopId: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface Order {
  id: string
  status: OrderStatus
  recipientName: string
  phone: string
  province: string | null
  district: string | null
  ward: string | null
  streetAddress: string
  note: string | null
  totalAmount: number
  createdAt: string
  items: OrderItem[]
  paymentMethod: 'COD' | 'PAYOS'
  paymentExpiresAt: string | null
  stockReserved: boolean
  /** Not returned yet (backend gap); used for the return window when present. */
  deliveredAt?: string | null
}

export interface PaymentSummary {
  orderId: string
  status: string
  amount: number
}

export async function listOrders(): Promise<Order[]> {
  return (await api.get<Order[]>('/api/orders')).data
}

export async function getOrder(orderId: string): Promise<Order> {
  return (await api.get<Order>(`/api/orders/${orderId}`)).data
}

/** PUT /api/orders/{id}/cancel: 409 ORDER_NOT_CANCELLABLE once the shop confirmed. */
export async function cancelOrder(orderId: string): Promise<Order> {
  return (await api.put<Order>(`/api/orders/${orderId}/cancel`)).data
}

/** POST /api/orders/{id}/complete ("Đã nhận được hàng"). */
export async function completeOrder(orderId: string): Promise<Order> {
  return (await api.post<Order>(`/api/orders/${orderId}/complete`)).data
}

/** My payment transactions (no per-order endpoint exists yet, backend gap G6). */
export async function listPayments(): Promise<PaymentSummary[]> {
  return (await api.get<PaymentSummary[]>('/api/payments', { params: { page: 0, size: 100 } })).data
}
