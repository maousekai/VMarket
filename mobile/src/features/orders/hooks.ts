import { useQueries, useQuery } from '@tanstack/react-query'

import { api } from '@/api/client'
import { getOrder, listOrders, listPayments, type Order, type OrderItem } from '@/api/orders'
import { listOrderReturns } from '@/api/returns'
import { getDelivery } from '@/api/tracking'
import type { ItemLabel } from '@/features/orders/text'

export const orderKeys = {
  list: ['orders'] as const,
  detail: (id: string) => ['orders', id] as const,
  returns: (id: string) => ['orders', id, 'returns'] as const,
  delivery: (id: string) => ['orders', id, 'delivery'] as const,
}

export const useOrders = (enabled = true) =>
  useQuery({ queryKey: orderKeys.list, queryFn: listOrders, enabled })

export const useOrder = (id: string, enabled = true) =>
  useQuery({ queryKey: orderKeys.detail(id), queryFn: () => getOrder(id), enabled })

export const useOrderReturns = (id: string, enabled = true) =>
  useQuery({ queryKey: orderKeys.returns(id), queryFn: () => listOrderReturns(id), enabled })

/** Delivery assignment + status history. Resolves to null while there is no assignment (404). */
export function useDelivery(orderId: string, enabled: boolean) {
  return useQuery({
    queryKey: orderKeys.delivery(orderId),
    enabled,
    retry: false,
    queryFn: async () => {
      try {
        return await getDelivery(orderId)
      } catch (e) {
        if ((e as { response?: { status?: number } }).response?.status === 404) return null
        throw e
      }
    },
  })
}

/** Time the order was delivered: order.deliveredAt when the API sends it, else the delivery history. */
export function useDeliveredAt(order: Order | undefined): string | null {
  const needsHistory =
    !!order && !order.deliveredAt && ['DELIVERED', 'COMPLETED'].includes(order.status)
  const delivery = useDelivery(order?.id ?? '', needsHistory)
  if (!order) return null
  if (order.deliveredAt) return order.deliveredAt
  const entry = delivery.data?.history.find((h) => h.status === 'DELIVERED')
  return entry ? new Date(entry.occurred_at).toISOString() : null
}

/** For cancelled PAYOS orders: did the buyer actually pay (so a refund is due)? */
export function useWasPaid(order: Order | undefined): boolean {
  const check = !!order && order.paymentMethod === 'PAYOS' && order.status === 'CANCELLED'
  const payments = useQuery({
    queryKey: ['payments', 'mine'],
    queryFn: listPayments,
    enabled: check,
  })
  if (!order || order.paymentMethod !== 'PAYOS') return false
  if (order.status !== 'CANCELLED') return order.status !== 'WAITING_PAYMENT'
  return !!payments.data?.some((p) => p.orderId === order.id && p.status === 'SUCCEEDED')
}

interface ProductBrief {
  name: string
  variants?: { id: string; attributes?: Record<string, string> }[]
}

const STALE = 10 * 60_000

/** Resolves product / variant / shop names for order items (order-service stores none). */
export function useItemLabels(items: OrderItem[]) {
  const productIds = [...new Set(items.map((i) => i.productId))]
  const shopIds = [...new Set(items.map((i) => i.shopId))]
  const products = useQueries({
    queries: productIds.map((id) => ({
      queryKey: ['product-brief', id],
      staleTime: STALE,
      retry: false,
      queryFn: async () =>
        (await api.get<{ product: ProductBrief }>(`/api/products/${id}`, { skipAuth: true })).data
          .product,
    })),
  })
  const shops = useQueries({
    queries: shopIds.map((id) => ({
      queryKey: ['shop-brief', id],
      staleTime: STALE,
      retry: false,
      queryFn: async () =>
        (await api.get<{ name: string }>(`/api/shops/${id}`, { skipAuth: true })).data,
    })),
  })

  const label = (item: OrderItem): ItemLabel => {
    const product = products[productIds.indexOf(item.productId)]?.data
    const variant = product?.variants?.find((v) => v.id === item.variantId)
    const attrs = variant?.attributes ? Object.values(variant.attributes).join(' / ') : ''
    return { name: product?.name ?? 'Sản phẩm', variant: attrs }
  }
  const shopName = (shopId: string) => shops[shopIds.indexOf(shopId)]?.data?.name ?? 'Gian hàng'
  return { label, shopName }
}
