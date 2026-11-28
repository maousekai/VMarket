import { useCallback, useState } from 'react'
import { router, useFocusEffect } from 'expo-router'
import { getApiError } from '../api/client'
import { listMyOrders } from '../api/shopApi'
import { Body, Button, Card, Lines, Loading, Notice, Screen } from '../components/ui'
import BuyerNav from './BuyerNav'
import {
  formatPrice,
  lineName,
  ORDER_STATUS_LABELS,
  PAYMENT_METHOD_LABELS,
  shortOrderId,
  useProductLookup,
} from './catalog'

/** "Don hang cua toi" (frame 3.82): moi don co ten san pham, so tien, trang thai. */
export default function OrdersScreen() {
  const [state, setState] = useState({ loading: true, orders: [], error: '' })
  const products = useProductLookup(
    state.orders.flatMap((order) => (order.items || []).map((item) => item.productId)),
  )

  const load = useCallback(async () => {
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const orders = await listMyOrders()
      const list = Array.isArray(orders) ? orders : orders?.content || []
      setState({ loading: false, orders: list, error: '' })
    } catch (failure) {
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [])

  useFocusEffect(
    useCallback(() => {
      load()
    }, [load]),
  )

  return (
    <Screen
      title="Đơn hàng của tôi"
      refreshing={state.loading && !!state.orders.length}
      onRefresh={load}
      footer={<BuyerNav active="cart" />}
    >
      {state.loading && !state.orders.length ? <Loading /> : null}
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {!state.loading && !state.orders.length && !state.error ? (
        <Card title="Bạn chưa có đơn hàng">
          <Body>Đơn đã đặt sẽ hiện ở đây.</Body>
        </Card>
      ) : null}
      {state.orders.map((order, index) => {
        const items = order.items || []
        return (
          <Card key={order.id} title={shortOrderId(order.id)}>
            <Lines
              lines={[
                ...items
                  .slice(0, 2)
                  .map(
                    (item) =>
                      `${lineName(products, item.productId, item.variantId)} × ${item.quantity}`,
                  ),
                items.length > 2 ? `và ${items.length - 2} sản phẩm khác` : null,
                `${formatPrice(order.totalAmount)} · ${PAYMENT_METHOD_LABELS[order.paymentMethod] || order.paymentMethod}`,
                ORDER_STATUS_LABELS[order.status] || order.status,
              ]}
            />
            <Button
              title={`Xem đơn ${index + 1}`}
              variant="secondary"
              onPress={() => router.push({ pathname: '/orders/[id]', params: { id: order.id } })}
            />
          </Card>
        )
      })}
    </Screen>
  )
}
