import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'

import { getApiError } from '@/api/client'
import { completeOrder } from '@/api/orders'
import { RETURN_WINDOW_DAYS } from '@/api/returns'
import { listPendingReviews } from '@/api/reviews'
import { useAuth } from '@/auth/AuthProvider'
import {
  AppText,
  Button,
  ConfirmDialog,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
  useToast,
} from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { OrderItemsCard } from '@/features/orders/components/OrderItemsCard'
import { OrderTimeline } from '@/features/orders/components/OrderTimeline'
import {
  orderKeys,
  useDelivery,
  useDeliveredAt,
  useItemLabels,
  useOrder,
  useOrderReturns,
  useWasPaid,
} from '@/features/orders/hooks'
import {
  ORDERS,
  backOr,
  goHome,
  orderSubPath,
  paymentPath,
  returnPath,
  reviewPath,
} from '@/features/orders/nav'
import {
  isCancellable,
  orderStatusInfo,
  paymentLine,
  returnStatusInfo,
} from '@/features/orders/status'
import {
  addDays,
  addressLine,
  daysLeft,
  itemKey,
  orderCode,
  returnCode,
  shortDate,
  totalBreakdown,
} from '@/features/orders/text'
import { formatPhone, formatVnd } from '@/lib/format'

export default function OrderDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const { status: auth } = useAuth()
  const authed = auth === 'authenticated'
  const toast = useToast()
  const qc = useQueryClient()
  const [confirmReceived, setConfirmReceived] = useState(false)

  const q = useOrder(id, authed)
  const order = q.data
  const { label, shopName } = useItemLabels(order?.items ?? [])
  const afterDelivery = order?.status === 'DELIVERED' || order?.status === 'COMPLETED'
  const timelineStatus = !!order && ['SHIPPED', 'DELIVERED', 'COMPLETED'].includes(order.status)
  const delivery = useDelivery(id, timelineStatus)
  const deliveredAt = useDeliveredAt(order)
  const returns = useOrderReturns(id, authed && afterDelivery)
  const paid = useWasPaid(order)
  const returnedIds = new Set(returns.data?.flatMap((r) => r.items.map((i) => i.orderItemId)))
  const everyItemReturned =
    !!order?.items.length && order.items.every((i) => !!i.id && returnedIds.has(i.id))
  const pendingReviews = useQuery({
    queryKey: ['reviews', 'pending', id],
    queryFn: () => listPendingReviews(order?.items ?? [], id),
    enabled: authed && order?.status === 'COMPLETED',
    retry: false,
  })

  const complete = useMutation({
    mutationFn: () => completeOrder(id),
    onSuccess: (next) => {
      qc.setQueryData(orderKeys.detail(id), next)
      qc.invalidateQueries({ queryKey: orderKeys.list })
      setConfirmReceived(false)
      toast.show({ type: 'success', message: 'Đã xác nhận nhận hàng.' })
    },
    onError: (e) => {
      setConfirmReceived(false)
      toast.show({ type: 'error', message: getApiError(e).message })
      qc.invalidateQueries({ queryKey: orderKeys.detail(id) })
    },
  })

  const refresh = () => {
    q.refetch()
    returns.refetch()
  }

  return (
    <Screen
      testID="order-detail-screen"
      title="Chi tiết đơn hàng"
      onBack={backOr('/orders')}
      refreshing={q.isRefetching}
      onRefresh={authed ? refresh : undefined}
    >
      <AuthGate>
        {q.isPending ? (
          <>
            <Skeleton height={21} />
            <Skeleton height={109} />
            <Skeleton height={88} />
          </>
        ) : q.isError || !order ? (
          <ErrorState
            error={q.error}
            heading={getApiError(q.error).status === 404 ? 'Không tìm thấy đơn' : undefined}
            onRetry={() => q.refetch()}
            secondaryLabel="Về danh sách đơn"
            onSecondary={() => router.replace(ORDERS)}
          />
        ) : (
          <>
            <AppText testID="order-summary">
              {`${orderCode(order.id)} · ${orderStatusInfo(order.status).label}${
                order.status === 'DELIVERED' && deliveredAt ? ` ${shortDate(deliveredAt)}` : ''
              }`}
            </AppText>

            <OrderItemsCard order={order} label={label} shopName={shopName} />

            <InfoCard
              title={paymentLine(order)}
              body={totalBreakdown(order)}
              testID="order-payment-card"
            />

            {order.status === 'CANCELLED' && paid ? (
              <InfoCard
                testID="order-refund-card"
                title={`Chờ hoàn · ${formatVnd(order.totalAmount)}`}
                body="Đã thanh toán PayOS. Admin sẽ xử lý hoàn tiền thủ công; chưa có xác nhận đã hoàn."
              />
            ) : null}

            <InfoCard title="Giao đến">
              <AppText>{`${order.recipientName} · ${formatPhone(order.phone)}`}</AppText>
              <AppText>{addressLine(order)}</AppText>
            </InfoCard>

            <OrderTimeline order={order} delivery={delivery.data} />

            {order.status === 'DELIVERED' ? <ReturnWindowCard deliveredAt={deliveredAt} /> : null}

            {returns.data?.map((r) => (
              <InfoCard
                key={r.id}
                testID={`order-return-${r.id}`}
                title={`Yêu cầu trả hàng ${returnCode(r.id)}`}
                body={returnStatusInfo(r.status).label}
                onPress={() => router.push(returnPath(r.id))}
              />
            ))}

            {order.status === 'COMPLETED' ? (
              <InfoCard
                title="Đánh giá"
                testID="order-review-card"
                body={
                  pendingReviews.data
                    ? pendingReviews.data.length > 0
                      ? `Còn ${pendingReviews.data.length} sản phẩm chưa đánh giá`
                      : 'Bạn đã đánh giá tất cả sản phẩm'
                    : 'Chia sẻ cảm nhận của bạn về sản phẩm trong đơn.'
                }
              />
            ) : null}

            {order.paymentMethod === 'PAYOS' && order.status === 'WAITING_PAYMENT' ? (
              <Button
                testID="order-pay"
                title="Tiếp tục thanh toán"
                onPress={() => router.push(paymentPath(order.id))}
              />
            ) : null}

            {isCancellable(order.status) ? (
              <Button
                testID="order-cancel"
                title="Hủy đơn hàng"
                kind="secondary"
                onPress={() => router.push(orderSubPath(order.id, 'cancel'))}
              />
            ) : null}

            {order.status === 'SHIPPED' ? (
              <Button
                testID="order-track"
                title="Theo dõi đơn hàng"
                onPress={() => router.push(orderSubPath(order.id, 'tracking'))}
              />
            ) : null}

            {order.status === 'DELIVERED' ? (
              <>
                <Button
                  testID="order-confirm-received"
                  title="Đã nhận được hàng"
                  onPress={() => setConfirmReceived(true)}
                />
                {everyItemReturned ? null : (
                  <Button
                    testID="order-return"
                    title="Yêu cầu trả hàng"
                    kind="secondary"
                    onPress={() => router.push(orderSubPath(order.id, 'return'))}
                  />
                )}
              </>
            ) : null}

            {order.status === 'COMPLETED' && pendingReviews.data?.length !== 0 ? (
              <Button
                testID="order-review"
                title="Đánh giá sản phẩm"
                onPress={() =>
                  router.push(
                    reviewPath(
                      order.id,
                      pendingReviews.data?.[0] && itemKey(pendingReviews.data[0]),
                    ),
                  )
                }
              />
            ) : null}

            {afterDelivery ? (
              <Button title="Về đơn hàng" kind="secondary" onPress={() => router.replace(ORDERS)} />
            ) : (
              <Button testID="order-home" title="Về trang chủ" onPress={goHome} />
            )}
          </>
        )}
      </AuthGate>

      <ConfirmDialog
        visible={confirmReceived}
        title="Xác nhận đã nhận hàng?"
        message="Đơn sẽ chuyển sang Hoàn thành và bạn có thể đánh giá sản phẩm. Sau đó không thể yêu cầu trả hàng."
        confirmLabel="Đã nhận được hàng"
        cancelLabel="Chưa"
        loading={complete.isPending}
        onConfirm={() => complete.mutate()}
        onCancel={() => setConfirmReceived(false)}
      />
    </Screen>
  )
}

function ReturnWindowCard({ deliveredAt }: { deliveredAt: string | null }) {
  const left = daysLeft(deliveredAt, RETURN_WINDOW_DAYS)
  const lead =
    left === null || !deliveredAt
      ? `Bạn có ${RETURN_WINDOW_DAYS} ngày kể từ khi nhận hàng để yêu cầu trả hàng.`
      : left > 0
        ? `Còn ${left} ngày để yêu cầu trả hàng (hạn ${shortDate(addDays(deliveredAt, RETURN_WINDOW_DAYS))}).`
        : `Đã quá ${RETURN_WINDOW_DAYS} ngày cho phép trả hàng.`
  return (
    <InfoCard title="Trả hàng – hoàn tiền" testID="order-return-window">
      <AppText>{lead}</AppText>
      <AppText>Đánh giá mở khi đơn chuyển Hoàn thành.</AppText>
    </InfoCard>
  )
}
