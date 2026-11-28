import { useCallback, useEffect, useState } from 'react'
import { Alert } from 'react-native'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { cancelOrder, getOrder, getPaymentByOrder } from '../api/shopApi'
import { Body, Button, Caption, Card, Lines, Loading, Notice, Screen } from '../components/ui'
import BuyerNav from './BuyerNav'
import {
  canCancelOrder,
  formatPrice,
  hasDelivery,
  lineName,
  ORDER_STATUS_LABELS,
  PAYMENT_METHOD_LABELS,
  PAYMENT_STATUS_LABELS,
  shortOrderId,
  useProductLookup,
} from './catalog'

const CANCEL_MESSAGES = {
  ORDER_ALREADY_CONFIRMED: 'Đơn đã được gian hàng xác nhận nên không huỷ được nữa.',
  INVALID_TRANSITION: 'Đơn đã được gian hàng xác nhận nên không huỷ được nữa.',
}

/** Chi tiet don (frame 3.38 / 3.75) va huy don (3.76-3.81). */
export default function OrderDetailScreen({ orderId }) {
  const [state, setState] = useState({ loading: true, order: null, payment: null, error: '' })
  const [cancelling, setCancelling] = useState(false)
  const [notice, setNotice] = useState(null)
  const order = state.order
  const products = useProductLookup((order?.items || []).map((item) => item.productId))

  const load = useCallback(async () => {
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const [loaded, payment] = await Promise.all([
        getOrder(orderId),
        getPaymentByOrder(orderId).catch(() => null),
      ])
      setState({ loading: false, order: loaded, payment, error: '' })
    } catch (failure) {
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [orderId])

  useEffect(() => {
    load()
  }, [load])

  async function cancel() {
    setCancelling(true)
    setNotice(null)
    try {
      const updated = await cancelOrder(orderId)
      setState((previous) => ({ ...previous, order: updated?.id ? updated : previous.order }))
      setNotice({ tone: 'success', message: `Đã huỷ đơn ${shortOrderId(orderId)}.` })
      if (!updated?.id) await load()
    } catch (failure) {
      const error = getApiError(failure)
      setNotice({
        tone: 'danger',
        title: error.status === 409 ? 'Đơn đã được xác nhận' : 'Chưa huỷ được đơn',
        message: CANCEL_MESSAGES[error.code] || error.message,
      })
    } finally {
      setCancelling(false)
    }
  }

  function askCancel() {
    Alert.alert(
      `Huỷ đơn ${shortOrderId(orderId)}?`,
      'Đơn sẽ bị huỷ và hàng được trả lại kho. Đơn đã thanh toán sẽ được hoàn tiền.',
      [
        { text: 'Giữ đơn', style: 'cancel' },
        { text: 'Huỷ đơn', style: 'destructive', onPress: cancel },
      ],
    )
  }

  const payment = state.payment
  const paidVnpay = order?.paymentMethod === 'VNPAY' && Boolean(payment?.paidAt)
  const waitingPayment = order?.status === 'WAITING_PAYMENT' && order?.paymentMethod === 'VNPAY'

  return (
    <Screen title="Chi tiết đơn hàng" footer={<BuyerNav active="cart" />}>
      {state.loading && !order ? <Loading /> : null}
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {order && (
        <>
          <Caption>
            {`${shortOrderId(order.id)} · ${ORDER_STATUS_LABELS[order.status] || order.status}`}
          </Caption>
          <Card title="Sản phẩm">
            <Lines
              lines={(order.items || []).map(
                (item) =>
                  `${lineName(products, item.productId, item.variantId)} · ${item.quantity} × ${formatPrice(item.unitPrice)}`,
              )}
            />
          </Card>
          <Card
            title={
              paidVnpay
                ? 'Đã thanh toán VNPay'
                : PAYMENT_METHOD_LABELS[order.paymentMethod] || order.paymentMethod
            }
          >
            <Lines
              lines={[
                `${formatPrice(order.totalAmount)} + phí giao miễn phí = ${formatPrice(order.totalAmount)}`,
                payment ? PAYMENT_STATUS_LABELS[payment.status] || payment.status : null,
              ]}
            />
          </Card>
          <Card title="Giao đến">
            <Lines
              lines={[
                `${order.recipientName} · ${order.phone}`,
                [order.streetAddress, order.ward, order.province].filter(Boolean).join(', '),
                order.note ? `Ghi chú: ${order.note}` : null,
              ]}
            />
          </Card>
          {notice ? (
            notice.title ? (
              <Card title={notice.title}>
                <Body>{notice.message}</Body>
              </Card>
            ) : (
              <Notice tone={notice.tone}>{notice.message}</Notice>
            )
          ) : null}
          {hasDelivery(order) && (
            <Button
              title="Theo dõi giao hàng"
              onPress={() =>
                router.push({ pathname: '/delivery/[orderId]', params: { orderId: order.id } })
              }
            />
          )}
          {waitingPayment && (
            <Button
              title="Thanh toán"
              onPress={() =>
                router.push({
                  pathname: '/payment/[orderId]',
                  params: { orderId: order.id, queue: order.id },
                })
              }
            />
          )}
          {canCancelOrder(order) && (
            <Button
              title="Huỷ đơn hàng"
              variant="secondary"
              loading={cancelling}
              onPress={askCancel}
            />
          )}
        </>
      )}
      <Button title="Về trang chủ" onPress={() => router.navigate('/')} />
    </Screen>
  )
}
