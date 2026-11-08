import { api } from '@/api/client'

export type PaymentStatus =
  | 'CREATED'
  | 'CREATING'
  | 'PENDING'
  | 'FAILED'
  | 'AWAITING_WEBHOOK'
  | 'RECONCILING'
  | 'SUCCEEDED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'COD_PENDING'

export interface Payment {
  id: number
  orderId: string
  amount: number
  method: 'COD' | 'PAYOS'
  status: PaymentStatus
  checkoutUrl: string | null
  qrCode: string | null
  qrAvailable: boolean
  reconciliationRequired: boolean
  expiresAt: string | null
  linkExpiresAt: string | null
  settlementExpiresAt: string | null
  paidAt: string | null
}

/** Idempotent per order: creates the PayOS link the first time, then returns the same payment. */
export async function createPayment(orderId: string): Promise<Payment> {
  return (await api.post<Payment>('/api/payments', { orderId })).data
}

export async function getPayment(paymentId: number): Promise<Payment> {
  return (await api.get<Payment>(`/api/payments/${paymentId}`)).data
}
