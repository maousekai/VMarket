import { useMutation, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'
import * as WebBrowser from 'expo-web-browser'
import { useEffect, useState } from 'react'
import { View } from 'react-native'

import { addCartItem } from '@/api/cart'
import type { Order } from '@/api/checkout'
import { getApiError } from '@/api/client'
import type { Payment } from '@/api/payments'
import { AppText, Button, ErrorState, Screen, Skeleton, useToast } from '@/components/ui'
import { QrCode } from '@/features/checkout/components/QrCode'
import { StatusScreen } from '@/features/checkout/components/StatusScreen'
import { isPaymentWindowOver, orderCode } from '@/features/checkout/format'
import { useCountdown } from '@/features/checkout/useCountdown'
import { isPaymentCreating, usePaymentFlow } from '@/features/checkout/usePaymentFlow'
import { formatCountdown, formatVnd } from '@/lib/format'
import { colors } from '@/theme'

const HOME = '/(buyer)' as Href

type PayView = 'qr' | 'pending' | 'check-failed'

const PENDING_STATUSES: Payment['status'][] = ['PENDING', 'AWAITING_WEBHOOK', 'RECONCILING']

/**
 * PayOS payment (Figma 3.7, 3.30, 3.31, 3.34, 3.35). One route, several states:
 * QR -> "Kiểm tra thanh toán" -> pending / check-failed; terminal: success (replace to
 * /payment/done) and expired/cancelled.
 */
export default function PaymentScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  const toast = useToast()
  const queryClient = useQueryClient()
  const { orderQuery, order, paymentQuery, payment } = usePaymentFlow(orderId)
  const [view, setView] = useState<PayView>('qr')
  const [checking, setChecking] = useState(false)
  const secondsLeft = useCountdown(payment?.linkExpiresAt ?? order?.paymentExpiresAt)

  const paid = !!order && order.status !== 'WAITING_PAYMENT' && order.status !== 'CANCELLED'
  useEffect(() => {
    if (paid) router.replace({ pathname: '/payment/done', params: { orderId } } as never)
  }, [paid, orderId])

  const reorder = useMutation({
    mutationFn: async (items: Order['items']) => {
      for (const item of items) {
        await addCartItem({
          productId: item.productId,
          variantId: item.variantId,
          quantity: item.quantity,
        })
      }
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['cart'] })
      router.replace('/cart' as Href)
    },
    onError: (error) => toast.show({ type: 'error', message: getApiError(error).message }),
  })

  const toOrder = () => router.replace(`/orders/${orderId}` as Href)

  if (orderQuery.isPending) {
    return (
      <Screen title="Thanh toán đơn hàng">
        <Skeleton height={40} />
        <Skeleton height={260} />
      </Screen>
    )
  }
  if (orderQuery.isError || !order) {
    return (
      <Screen title="Thanh toán đơn hàng" onBack={() => router.back()}>
        <ErrorState error={orderQuery.error} onRetry={() => void orderQuery.refetch()} />
      </Screen>
    )
  }
  if (paid) return <Screen title="Thanh toán thành công" />

  const summary = `${orderCode(order.id)} · ${formatVnd(order.totalAmount)}`
  const reserving = order.status === 'WAITING_PAYMENT' && !order.stockReserved
  const windowOver = isPaymentWindowOver(order)
  const expired =
    order.status === 'CANCELLED' ||
    payment?.status === 'EXPIRED' ||
    payment?.status === 'CANCELLED' ||
    (windowOver && !payment && !paymentQuery.isFetching)

  // 3.34: the payment window ended (or the stock was not available) and the order is cancelled.
  if (expired) {
    const outOfStock = order.status === 'CANCELLED' && !order.stockReserved && !windowOver
    return (
      <StatusScreen
        title={outOfStock ? 'Đơn đã hủy' : 'Thanh toán đã hết hạn'}
        summary={orderCode(order.id)}
        cards={[
          {
            title: 'Đơn đã hủy',
            body: outOfStock
              ? 'Sản phẩm đã hết hàng nên đơn không được giữ. Giá và tồn kho sẽ được kiểm tra khi đặt lại.'
              : 'Đã hết thời hạn thanh toán. Số lượng giữ cho đơn đã được giải phóng. Giá và tồn kho sẽ được kiểm tra khi đặt lại.' +
                (payment?.status === 'SUCCEEDED'
                  ? ' Nếu bạn đã chuyển tiền, khoản tiền sẽ được hoàn lại.'
                  : ''),
          },
        ]}
        primary={{
          label: 'Đặt lại đơn hàng',
          onPress: () => reorder.mutate(order.items),
          loading: reorder.isPending,
          testID: 'pay-reorder',
        }}
        secondary={{ label: 'Về trang chủ', onPress: () => router.navigate(HOME) }}
        testID="payment-expired"
      />
    )
  }

  if (reserving || (!payment && paymentQuery.isFetching) || isPaymentCreating(payment)) {
    return (
      <Screen title="Thanh toán đơn hàng" onBack={() => router.back()}>
        <AppText>{summary}</AppText>
        <AppText color="textSecondary">Đang chuẩn bị mã thanh toán...</AppText>
        <Skeleton height={260} />
      </Screen>
    )
  }

  // The payment link could not be created (e.g. PayOS is not configured on the server).
  if (!payment) {
    return (
      <Screen title="Thanh toán đơn hàng" onBack={() => router.back()}>
        <AppText>{summary}</AppText>
        <ErrorState
          heading="Chưa tạo được mã thanh toán"
          title="Không mở được thanh toán PayOS"
          error={paymentQuery.error}
          onRetry={() => void paymentQuery.refetch()}
          secondaryLabel="Xem đơn hàng"
          onSecondary={toOrder}
        />
      </Screen>
    )
  }

  const checkNow = async () => {
    setChecking(true)
    const result = await paymentQuery.refetch()
    void orderQuery.refetch()
    setChecking(false)
    if (result.isError) setView('check-failed')
    else if (result.data && PENDING_STATUSES.includes(result.data.status)) setView('pending')
    else setView('qr')
  }

  if (payment.status === 'FAILED') {
    return (
      <StatusScreen
        title="Thanh toán thất bại"
        summary={summary}
        cards={[
          {
            title: 'Giao dịch chưa thành công',
            body: 'Bạn có thể thử thanh toán lại bằng mã QR của đơn này.',
          },
        ]}
        primary={{
          label: 'Thử lại',
          onPress: () => void checkNow(),
          loading: checking,
          testID: 'pay-retry',
        }}
        secondary={{ label: 'Xem đơn hàng', onPress: toOrder }}
        testID="payment-failed"
      />
    )
  }

  // 3.30: not confirmed yet (also while the bank confirmation is late after the link closed).
  const linkClosed = secondsLeft === 0 && payment.status !== 'SUCCEEDED'
  if (view === 'pending' || linkClosed) {
    return (
      <StatusScreen
        title="Chờ xác nhận thanh toán"
        summary={summary}
        cards={[
          {
            title: 'Chưa nhận xác nhận',
            body: 'Nếu đã chuyển tiền, chờ hệ thống đối soát. Không chuyển lại để tránh thanh toán trùng.',
          },
        ]}
        primary={{
          label: 'Kiểm tra lại',
          onPress: () => void checkNow(),
          loading: checking,
          testID: 'pay-recheck',
        }}
        secondary={
          linkClosed
            ? { label: 'Xem đơn hàng', onPress: toOrder }
            : { label: 'Quay lại mã QR', onPress: () => setView('qr') }
        }
        testID="payment-pending"
      />
    )
  }

  // 3.35: the check itself failed (network / 5xx), the payment state is unknown.
  if (view === 'check-failed') {
    return (
      <StatusScreen
        title="Chưa kiểm tra được"
        summary="Không nhận được kết quả thanh toán"
        cards={[
          {
            title: 'Thử lại khi có mạng',
            body: 'Không chuyển tiền lần nữa. Nếu đã thanh toán, giao dịch chỉ được ghi nhận sau khi hệ thống xác nhận.',
          },
        ]}
        primary={{
          label: 'Thử kiểm tra lại',
          onPress: () => void checkNow(),
          loading: checking,
          testID: 'pay-check-retry',
        }}
        secondary={{ label: 'Quay lại mã QR', onPress: () => setView('qr') }}
        testID="payment-check-failed"
      />
    )
  }

  // 3.7: QR payment.
  const openCheckout = async () => {
    if (!payment.checkoutUrl) return
    await WebBrowser.openBrowserAsync(payment.checkoutUrl)
    void checkNow()
  }

  return (
    <Screen title="Thanh toán đơn hàng" onBack={() => router.back()} testID="payment-qr">
      <AppText variant="label">{orderCode(order.id)}</AppText>
      <AppText variant="display" color="primary700">
        {formatVnd(payment.amount)}
      </AppText>
      <AppText testID="pay-countdown">Thời hạn thanh toán: {formatCountdown(secondsLeft)}</AppText>
      {payment.qrAvailable && payment.qrCode ? (
        <View
          style={{
            backgroundColor: colors.surface,
            padding: 16,
            gap: 12,
            alignItems: 'center',
          }}
        >
          <QrCode value={payment.qrCode} testID="pay-qr" />
          {__DEV__ ? (
            <AppText variant="caption" color="textSecondary" style={{ alignSelf: 'stretch' }}>
              QR minh họa — không chuyển tiền
            </AppText>
          ) : null}
        </View>
      ) : null}
      <AppText>Chờ thanh toán · chỉ cập nhật sau khi giao dịch được xác nhận.</AppText>
      <Button
        title="Kiểm tra thanh toán"
        onPress={() => void checkNow()}
        loading={checking}
        testID="pay-check"
      />
      {payment.checkoutUrl ? (
        <Button
          title="Mở trang thanh toán PayOS"
          kind="secondary"
          onPress={() => void openCheckout()}
          testID="pay-open-payos"
        />
      ) : null}
      <Button title="Xem đơn hàng" kind="neutral" onPress={toOrder} testID="pay-view-order" />
    </Screen>
  )
}
