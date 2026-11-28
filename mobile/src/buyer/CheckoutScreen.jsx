import { useCallback, useEffect, useRef, useState } from 'react'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import {
  checkoutOrders,
  getCart,
  listMyAddresses,
  newIdempotencyKey,
  PAYMENT_METHOD,
} from '../api/shopApi'
import { Body, Button, Caption, Card, Lines, Loading, Notice, Screen } from '../components/ui'
import BuyerNav from './BuyerNav'
import { formatAddress, pickAddress } from './CartScreen'
import { formatPrice, useShopLookup } from './catalog'
import { useCheckout } from './checkoutStore'

/**
 * Xac nhan dat hang (frame 3.36): dia chi giao, moi gian hang mot don rieng, tong can tra. COD xong
 * -> 3.37; VNPay -> thanh toan tung don (3.7, 3.30-3.35).
 */
export default function CheckoutScreen() {
  const checkout = useCheckout()
  const [state, setState] = useState({ loading: true, cart: null, addresses: [], error: '' })
  const [placing, setPlacing] = useState(false)
  const [error, setError] = useState('')
  const attempt = useRef(null)
  const groups = state.cart?.groups || []
  const shops = useShopLookup(groups.map((group) => group.shopId))
  const address = pickAddress(state.addresses, checkout.addressId)
  const cod = checkout.paymentMethod === PAYMENT_METHOD.cod
  const total = groups.reduce((sum, group) => sum + Number(group.subtotal || 0), 0)

  const load = useCallback(async () => {
    try {
      const [cart, addresses] = await Promise.all([getCart(), listMyAddresses()])
      setState({ loading: false, cart, addresses: addresses || [], error: '' })
    } catch (failure) {
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function place() {
    const items = groups.flatMap((group) =>
      group.items.map((item) => ({ productId: item.productId, variantId: item.variantId || null })),
    )
    const body = { addressId: address.id, paymentMethod: checkout.paymentMethod, items }
    // Gui lai cung yeu cau (mat mang) dung lai khoa cu de khong tao don trung.
    const signature = JSON.stringify(body)
    if (attempt.current?.signature !== signature)
      attempt.current = { signature, key: newIdempotencyKey() }
    setPlacing(true)
    setError('')
    try {
      const orders = await checkoutOrders(body, attempt.current.key)
      const ids = (orders || []).map((order) => order.id)
      attempt.current = null
      if (cod) router.replace({ pathname: '/order-success', params: { ids: ids.join(',') } })
      else
        router.replace({
          pathname: '/payment/[orderId]',
          params: { orderId: ids[0], queue: ids.join(',') },
        })
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setPlacing(false)
    }
  }

  return (
    <Screen title={cod ? 'Đặt hàng COD' : 'Đặt hàng · VNPay'} footer={<BuyerNav active="cart" />}>
      {state.loading && <Loading />}
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {!state.loading && !groups.length && !state.error ? (
        <Card title="Giỏ hàng trống">
          <Body>Không còn sản phẩm nào để đặt.</Body>
        </Card>
      ) : null}
      {groups.length > 0 && (
        <>
          <Caption>
            {cod
              ? 'Thanh toán tiền mặt khi nhận hàng'
              : 'Thanh toán VNPay cho từng đơn sau khi đặt'}
          </Caption>
          {address ? (
            <Card title="Giao đến">
              <Lines
                lines={[`${address.recipientName} · ${address.phone}`, formatAddress(address)]}
              />
            </Card>
          ) : (
            <Notice tone="warning">Chưa có địa chỉ nhận hàng.</Notice>
          )}
          <Card title={groups.length > 1 ? `${groups.length} đơn riêng` : '1 đơn'}>
            <Lines
              lines={[
                ...groups.map(
                  (group, index) =>
                    `Đơn ${index + 1}${shops[group.shopId]?.value?.name ? ` (${shops[group.shopId].value.name})` : ''}: ${formatPrice(group.subtotal)} + miễn phí giao = ${formatPrice(group.subtotal)}`,
                ),
                `Tổng cần trả: ${formatPrice(total)}`,
              ]}
            />
          </Card>
          {error ? <Notice tone="danger">{error}</Notice> : null}
          <Button
            title={
              cod ? `Đặt ${groups.length} đơn COD` : `Đặt ${groups.length} đơn và thanh toán VNPay`
            }
            loading={placing}
            disabled={!address}
            onPress={place}
          />
        </>
      )}
      <Button
        title="Quay lại giỏ hàng"
        variant="secondary"
        disabled={placing}
        onPress={() => (router.canGoBack() ? router.back() : router.navigate('/cart'))}
      />
    </Screen>
  )
}
