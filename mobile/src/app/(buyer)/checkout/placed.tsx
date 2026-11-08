import { useQueries, useQueryClient } from '@tanstack/react-query'
import { router, useFocusEffect, useLocalSearchParams, type Href } from 'expo-router'
import { useCallback } from 'react'

import { getOrder, getShopName } from '@/api/checkout'
import { AppText, Button, Card, ErrorState, Screen, Skeleton, StatusBadge } from '@/components/ui'
import { orderCode, parseIds } from '@/features/checkout/format'
import { orderStatusInfo } from '@/features/orders/status'
import { formatVnd } from '@/lib/format'

const HOME = '/(buyer)' as Href

/** Checkout that was split into one order per shop: list every order with its own actions. */
export default function CheckoutPlacedScreen() {
  const queryClient = useQueryClient()
  const ids = parseIds(useLocalSearchParams<{ ids?: string }>().ids)
  const queries = useQueries({
    queries: ids.map((id) => ({ queryKey: ['order', id], queryFn: () => getOrder(id) })),
  })
  const orders = queries.flatMap((q) => (q.data ? [q.data] : []))
  const shopIds = orders.map((o) => o.shopId ?? o.items[0]?.shopId)
  const shops = useQueries({
    queries: shopIds.map((shopId) => ({
      queryKey: ['cart-shop', shopId],
      queryFn: () => getShopName(shopId as string),
      enabled: !!shopId,
      staleTime: 5 * 60_000,
      retry: false,
    })),
  })

  // Coming back from a PayOS payment: refresh so paid orders drop their "Thanh toán" button.
  useFocusEffect(
    useCallback(() => {
      void queryClient.invalidateQueries({ queryKey: ['order'] })
    }, [queryClient]),
  )

  if (queries.some((q) => q.isPending)) {
    return (
      <Screen title="Đặt hàng thành công">
        <Skeleton height={120} />
        <Skeleton height={120} />
      </Screen>
    )
  }
  if (!orders.length) {
    return (
      <Screen title="Đặt hàng thành công">
        <ErrorState
          error={queries.find((q) => q.error)?.error}
          onRetry={() => queries.forEach((q) => void q.refetch())}
          secondaryLabel="Xem đơn hàng"
          onSecondary={() => router.replace('/orders' as Href)}
        />
      </Screen>
    )
  }

  const total = orders.reduce((sum, o) => sum + o.totalAmount, 0)
  const unpaid = orders.filter((o) => o.paymentMethod === 'PAYOS' && o.status === 'WAITING_PAYMENT')

  return (
    <Screen title="Đặt hàng thành công" testID="checkout-placed">
      <AppText>{`Đã tạo ${orders.length} đơn hàng, mỗi gian hàng một đơn.`}</AppText>
      <AppText variant="h2">Tổng: {formatVnd(total)}</AppText>
      {unpaid.length ? (
        <AppText variant="caption" color="textSecondary">
          Mỗi đơn được thanh toán riêng qua PayOS. Đơn chưa thanh toán sẽ tự hủy khi hết hạn.
        </AppText>
      ) : null}
      {orders.map((order, index) => {
        const info = orderStatusInfo(order.status)
        const needsPayment = order.paymentMethod === 'PAYOS' && order.status === 'WAITING_PAYMENT'
        return (
          <Card key={order.id} testID={`placed-order-${order.id}`}>
            <AppText variant="label">{shops[index]?.data ?? `Gian hàng ${index + 1}`}</AppText>
            <AppText color="textSecondary">
              {orderCode(order.id)} · {formatVnd(order.totalAmount)}
            </AppText>
            <StatusBadge label={info.label} tone={info.tone} />
            {needsPayment ? (
              <Button
                title="Thanh toán"
                onPress={() => router.push(`/payment/${order.id}` as Href)}
                testID={`placed-pay-${order.id}`}
              />
            ) : null}
            <Button
              title="Xem đơn"
              kind="secondary"
              onPress={() => router.push(`/orders/${order.id}` as Href)}
              testID={`placed-view-${order.id}`}
            />
          </Card>
        )
      })}
      <Button title="Về trang chủ" onPress={() => router.navigate(HOME)} testID="placed-home" />
      <Button
        title="Xem tất cả đơn hàng"
        kind="secondary"
        onPress={() => router.replace('/orders' as Href)}
        testID="placed-orders"
      />
    </Screen>
  )
}
