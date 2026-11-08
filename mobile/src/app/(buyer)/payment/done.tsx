import { useQuery } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'

import { getOrder } from '@/api/checkout'
import { ErrorState, Screen, Skeleton } from '@/components/ui'
import { StatusScreen } from '@/features/checkout/components/StatusScreen'
import { orderCode } from '@/features/checkout/format'
import { formatVnd } from '@/lib/format'

const HOME = '/(buyer)' as Href

/** Figma 3.31 / 3.33 for a single order: PayOS payment confirmed. */
export default function PaymentDoneScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  const orderQuery = useQuery({ queryKey: ['order', orderId], queryFn: () => getOrder(orderId) })

  if (orderQuery.isPending) {
    return (
      <Screen title="Thanh toán thành công">
        <Skeleton height={120} />
      </Screen>
    )
  }
  if (orderQuery.isError) {
    return (
      <Screen title="Thanh toán thành công">
        <ErrorState
          error={orderQuery.error}
          onRetry={() => void orderQuery.refetch()}
          secondaryLabel="Về trang chủ"
          onSecondary={() => router.navigate(HOME)}
        />
      </Screen>
    )
  }

  const order = orderQuery.data
  return (
    <StatusScreen
      title="Thanh toán thành công"
      summary={`${orderCode(order.id)} · ${formatVnd(order.totalAmount)}`}
      cards={[
        {
          title: 'Chờ xác nhận',
          body: 'Giao dịch PayOS đã được xác nhận. Đơn đang chờ gian hàng xác nhận.',
        },
      ]}
      primary={{
        label: 'Xem đơn hàng',
        onPress: () => router.replace(`/orders/${order.id}` as Href),
        testID: 'pay-done-orders',
      }}
      secondary={{
        label: 'Về trang chủ',
        onPress: () => router.navigate(HOME),
        testID: 'pay-done-home',
      }}
      testID="payment-done"
    />
  )
}
