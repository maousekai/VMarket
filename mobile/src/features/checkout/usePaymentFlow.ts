import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useIsFocused } from 'expo-router'
import { useEffect } from 'react'
import { AppState } from 'react-native'

import { getApiError } from '@/api/client'
import { getOrder, type Order } from '@/api/checkout'
import { createPayment, getPayment, type Payment, type PaymentStatus } from '@/api/payments'

const CREATING: PaymentStatus[] = ['CREATED', 'CREATING']
const POLLING: PaymentStatus[] = ['CREATED', 'CREATING', 'PENDING', 'AWAITING_WEBHOOK']

const paymentQueryKey = (orderId: string) => ['payment', orderId]

export const isPaymentCreating = (p?: Payment) => !!p && CREATING.includes(p.status)

/**
 * Order + PayOS payment state for the pay screen.
 * - polls the order every second until stock is reserved (the payment link needs it);
 * - creates the link (idempotent POST), then polls GET /payments/{id} every 3 s while focused;
 * - a FAILED payment is re-created (same link) only when the user retries.
 */
export function usePaymentFlow(orderId: string) {
  const queryClient = useQueryClient()
  const focused = useIsFocused()

  const orderQuery = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => getOrder(orderId),
    refetchInterval: (query) => {
      const order = query.state.data as Order | undefined
      if (!focused || order?.status !== 'WAITING_PAYMENT') return false
      return order.stockReserved ? 5000 : 1000
    },
  })
  const order = orderQuery.data

  const paymentKey = paymentQueryKey(orderId)
  const paymentQuery = useQuery({
    queryKey: paymentKey,
    enabled: order?.status === 'WAITING_PAYMENT' && order.stockReserved,
    retry: false,
    staleTime: 0,
    queryFn: async () => {
      const cached = queryClient.getQueryData<Payment>(paymentKey)
      return cached && cached.status !== 'FAILED' && !isPaymentCreating(cached)
        ? getPayment(cached.id)
        : createPayment(orderId)
    },
    refetchInterval: (query) => {
      const status = (query.state.data as Payment | undefined)?.status
      if (!focused || !status || !POLLING.includes(status)) return false
      if (query.state.status === 'error') {
        // A transient failure (offline / 5xx) must not freeze the screen: keep polling with a
        // capped backoff. Client errors (4xx) are final.
        if (!getApiError(query.state.error).retryable) return false
        return Math.min(3000 * 2 ** Math.min(query.state.fetchFailureCount, 3), 30_000)
      }
      return CREATING.includes(status) ? 2000 : 3000
    },
  })

  const { refetch: refetchPayment } = paymentQuery
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active' && queryClient.getQueryData(paymentQueryKey(orderId))) {
        void refetchPayment()
      }
    })
    return () => sub.remove()
  }, [orderId, refetchPayment, queryClient])

  return { orderQuery, order, paymentQuery, payment: paymentQuery.data }
}
