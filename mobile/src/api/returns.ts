import { api } from '@/api/client'

export type ReturnStatus =
  'PENDING' | 'APPROVED' | 'REJECTED' | 'DISPUTED' | 'RESOLVED' | 'CANCELLED' | 'ADMIN_REJECTED'

export interface ReturnItem {
  id: string
  orderItemId: string
  productId: string
  variantId: string | null
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface OrderReturn {
  id: string
  orderId: string
  shopId: string
  status: ReturnStatus
  reason: string
  evidenceUrls: string[]
  sellerNote: string | null
  adminNote: string | null
  refundAmount: number
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  items: ReturnItem[]
}

export interface CreateReturnInput {
  reason: string
  evidenceUrls: string[]
  items: { orderItemId: string; productId: string; variantId: string | null; quantity: number }[]
}

export const MAX_EVIDENCE = 5
export const RETURN_WINDOW_DAYS = 7
export const SELLER_RESPONSE_HOURS = 48

export async function createReturn(orderId: string, input: CreateReturnInput) {
  return (await api.post<OrderReturn>(`/api/orders/${orderId}/returns`, input)).data
}

export async function listOrderReturns(orderId: string) {
  return (await api.get<OrderReturn[]>(`/api/orders/${orderId}/returns`)).data
}

export async function getReturn(returnId: string) {
  return (await api.get<OrderReturn>(`/api/orders/returns/${returnId}`)).data
}

/** Backend requires a non-blank reason (<= 500). */
export async function disputeReturn(returnId: string, reason: string) {
  return (await api.post<OrderReturn>(`/api/orders/returns/${returnId}/dispute`, { reason })).data
}
