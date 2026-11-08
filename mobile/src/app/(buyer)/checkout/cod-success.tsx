import { useQueries } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'

import { getOrder } from '@/api/checkout'
import { ErrorState, Screen, Skeleton } from '@/components/ui'
import { StatusScreen } from '@/features/checkout/components/StatusScreen'
import { orderCode, orderStatusLabel, parseIds } from '@/features/checkout/format'
import { formatVnd } from '@/lib/format'

const HOME = '/(buyer)' as Href

/** Figma 3.37 "Đặt COD thành công". */
export default function CodSuccessScreen() {
  const ids = parseIds(useLocalSearchParams<{ ids?: string }>().ids)
  const queries = useQueries({
    queries: ids.map((id) => ({ queryKey: ['order', id], queryFn: () => getOrder(id) })),
  })

  if (queries.some((q) => q.isPending)) {
    return (
      <Screen title="Đặt COD thành công">
        <Skeleton height={120} />
      </Screen>
    )
  }
  const orders = queries.flatMap((q) => (q.data ? [q.data] : []))
  if (!orders.length) {
    return (
      <Screen title="Đặt COD thành công">
        <ErrorState
          error={queries.find((q) => q.error)?.error}
          onRetry={() => queries.forEach((q) => void q.refetch())}
          secondaryLabel="Về trang chủ"
          onSecondary={() => router.navigate(HOME)}
        />
      </Screen>
    )
  }

  const total = orders.reduce((sum, o) => sum + o.totalAmount, 0)
  const toOrders = () =>
    router.replace((orders.length === 1 ? `/orders/${orders[0].id}` : '/orders') as Href)

  return (
    <StatusScreen
      title="Đặt COD thành công"
      summary={`Tổng cần trả khi nhận: ${formatVnd(total)}`}
      cards={[
        {
          title: orders.length > 1 ? `${orders.length} đơn chờ xác nhận` : 'Đơn chờ xác nhận',
          body: [
            ...orders.map(
              (o) =>
                `${orderCode(o.id)} · COD ${formatVnd(o.totalAmount)}${
                  o.status === 'PENDING' ? '' : ` · ${orderStatusLabel(o.status)}`
                }`,
            ),
            'Chưa thanh toán; trả tiền khi nhận hàng.',
          ].join('\n'),
        },
      ]}
      primary={{ label: 'Về trang chủ', onPress: () => router.navigate(HOME), testID: 'cod-home' }}
      secondary={{ label: 'Xem đơn hàng', onPress: toOrders, testID: 'cod-view-orders' }}
      testID="cod-success"
    />
  )
}
