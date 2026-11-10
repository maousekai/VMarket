import { useMutation, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'

import { getApiError } from '@/api/client'
import { cancelOrder } from '@/api/orders'
import { useAuth } from '@/auth/AuthProvider'
import { Button, ErrorState, InfoCard, Screen, Skeleton, useToast } from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { orderKeys, useOrder } from '@/features/orders/hooks'
import { ORDERS, backOr, orderPath, orderSubPath } from '@/features/orders/nav'
import { isCancellable, orderStatusInfo, paymentLine } from '@/features/orders/status'
import { orderCode } from '@/features/orders/text'
import { formatVnd } from '@/lib/format'

export default function CancelOrderScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const { status: auth } = useAuth()
  const toast = useToast()
  const qc = useQueryClient()
  const q = useOrder(id, auth === 'authenticated')
  const order = q.data

  const result = (outcome: 'cancelled' | 'confirmed' | 'unknown') =>
    router.replace(`${orderSubPath(id, 'cancel-result')}?outcome=${outcome}` as Href)

  const cancel = useMutation({
    mutationFn: () => cancelOrder(id),
    onSuccess: (next) => {
      qc.setQueryData(orderKeys.detail(id), next)
      qc.invalidateQueries({ queryKey: orderKeys.list })
      result('cancelled')
    },
    onError: (e) => {
      const err = getApiError(e)
      qc.invalidateQueries({ queryKey: orderKeys.detail(id) })
      qc.invalidateQueries({ queryKey: orderKeys.list })
      if (err.code === 'ORDER_NOT_CANCELLABLE') return result('confirmed')
      if (err.status === 404) {
        toast.show({ type: 'error', message: err.message })
        return router.replace(ORDERS)
      }
      // Timeout / network / 5xx: the outcome is unknown. Never retry automatically.
      return result('unknown')
    },
  })

  const shops = order ? new Set(order.items.map((i) => i.shopId)).size : 0
  const paid = !!order && order.paymentMethod === 'PAYOS' && order.status !== 'WAITING_PAYMENT'
  const refundNote = paid
    ? 'Nếu hủy thành công, khoản đã thanh toán chuyển sang Chờ hoàn; Admin hoàn tiền thủ công.'
    : 'Đơn chưa thanh toán nên không phát sinh hoàn tiền.'

  return (
    <Screen
      testID="order-cancel-screen"
      title={order ? `Hủy đơn ${orderCode(order.id)}?` : 'Hủy đơn?'}
      onBack={backOr(`/orders/${id}`)}
    >
      <AuthGate>
        {q.isPending ? (
          <>
            <Skeleton height={109} />
            <Skeleton height={130} />
          </>
        ) : q.isError || !order ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : !isCancellable(order.status) ? (
          <>
            <InfoCard
              title="Không thể hủy đơn"
              body={`Đơn đang ở trạng thái "${orderStatusInfo(order.status).label}". Nếu cần hỗ trợ, vui lòng liên hệ người bán.`}
            />
            <Button title="Xem đơn hàng" onPress={() => router.replace(orderPath(order.id))} />
          </>
        ) : (
          <>
            <InfoCard
              title={orderCode(order.id)}
              body={`${orderStatusInfo(order.status).label} · ${paymentLine(order)}\nSố tiền: ${formatVnd(order.totalAmount)}`}
            />
            <InfoCard
              title={shops > 1 ? 'Hủy toàn bộ đơn' : 'Hủy riêng đơn này'}
              body={
                shops > 1
                  ? `Đơn gồm sản phẩm của ${shops} gian hàng; hủy sẽ hủy toàn bộ sản phẩm trong đơn. ${refundNote}`
                  : refundNote
              }
            />
            <Button
              testID="cancel-confirm"
              title="Xác nhận hủy đơn"
              loading={cancel.isPending}
              onPress={() => cancel.mutate()}
            />
            <Button
              testID="cancel-keep"
              title="Giữ đơn hàng"
              kind="secondary"
              disabled={cancel.isPending}
              onPress={() => router.back()}
            />
          </>
        )}
      </AuthGate>
    </Screen>
  )
}
