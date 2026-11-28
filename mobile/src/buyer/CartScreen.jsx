import { useCallback, useState } from 'react'
import { View } from 'react-native'
import { router, useFocusEffect } from 'expo-router'
import { getApiError } from '../api/client'
import {
  getCart,
  listMyAddresses,
  removeCartItem,
  updateCartItem,
  validateCart,
} from '../api/shopApi'
import {
  Body,
  Button,
  Caption,
  Card,
  Choice,
  Heading,
  Label,
  Lines,
  Loading,
  Notice,
  Screen,
} from '../components/ui'
import { makeStyles, spacing } from '../theme'
import { formatPrice, lineName, useProductLookup, useShopLookup } from './catalog'
import { setCheckout, useCheckout } from './checkoutStore'

export const formatAddress = (address) =>
  address ? [address.streetAddress, address.ward, address.province].filter(Boolean).join(', ') : ''

/** Dia chi dang chon: theo lua chon truoc do, khong thi mac dinh, khong thi dia chi dau tien. */
export function pickAddress(addresses, selectedId) {
  return (
    addresses.find((address) => address.id === selectedId) ||
    addresses.find((address) => address.isDefault) ||
    addresses[0] ||
    null
  )
}

function CartLine({ item, products, busy, onQuantity, onRemove }) {
  const styles = useStyles()
  return (
    <View style={styles.line}>
      <Body>{`${lineName(products, item.productId, item.variantId)} · ${item.quantity} × ${formatPrice(item.unitPrice)}`}</Body>
      <View style={styles.row}>
        <Button
          title="−"
          variant="secondary"
          style={styles.flex}
          disabled={busy || item.quantity <= 1}
          onPress={() => onQuantity(item, item.quantity - 1)}
        />
        <Button
          title="+"
          variant="secondary"
          style={styles.flex}
          disabled={busy}
          onPress={() => onQuantity(item, item.quantity + 1)}
        />
        <Button
          title="Xoá"
          variant="secondary"
          style={styles.flex}
          disabled={busy}
          onPress={() => onRemove(item)}
        />
      </View>
    </View>
  )
}

/** Gio hang va checkout (frame 3.6, "Gio hang trong", "Gia da thay doi"). Mien phi van chuyen. */
export default function CartScreen() {
  const [state, setState] = useState({ loading: true, cart: null, addresses: [], error: '' })
  const [busy, setBusy] = useState(false)
  const [issues, setIssues] = useState([])
  const [notice, setNotice] = useState('')
  const checkout = useCheckout()
  const groups = state.cart?.groups || []
  const items = groups.flatMap((group) => group.items)
  const products = useProductLookup(items.map((item) => item.productId))
  const shops = useShopLookup(groups.map((group) => group.shopId))
  const address = pickAddress(state.addresses, checkout.addressId)
  const total = items.reduce((sum, item) => sum + Number(item.lineTotal || 0), 0)

  const load = useCallback(async () => {
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const [cart, addresses] = await Promise.all([getCart(), listMyAddresses().catch(() => [])])
      setState({ loading: false, cart, addresses: addresses || [], error: '' })
    } catch (failure) {
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [])

  useFocusEffect(
    useCallback(() => {
      load()
    }, [load]),
  )

  async function change(action) {
    setBusy(true)
    setNotice('')
    try {
      const cart = await action()
      setState((previous) => ({ ...previous, cart: cart?.groups ? cart : previous.cart }))
      if (!cart?.groups) await load()
      setIssues([])
    } catch (failure) {
      setNotice(getApiError(failure).message)
    } finally {
      setBusy(false)
    }
  }

  async function placeOrder() {
    setBusy(true)
    setNotice('')
    try {
      const check = await validateCart()
      if (!check?.ok) {
        setIssues(check?.issues || [])
        return
      }
      setIssues([])
      setCheckout({ addressId: address.id })
      router.push('/checkout')
    } catch (failure) {
      setNotice(getApiError(failure).message)
    } finally {
      setBusy(false)
    }
  }

  const title = groups.length ? `Giỏ hàng · ${groups.length} gian hàng` : 'Giỏ hàng'

  return (
    <Screen title={title} refreshing={state.loading && !!state.cart} onRefresh={load}>
      {state.loading && !state.cart ? <Loading /> : null}
      {state.error ? (
        <>
          <Notice tone="danger">{state.error}</Notice>
          <Button title="Thử lại" variant="secondary" onPress={load} />
        </>
      ) : null}
      {state.cart && !items.length ? (
        <>
          <Card title="Giỏ hàng trống">
            <Body>Thêm sản phẩm từ trang chủ hoặc tìm kiếm để đặt hàng.</Body>
          </Card>
          <Button title="Tiếp tục mua sắm" onPress={() => router.navigate('/')} />
        </>
      ) : null}
      {groups.map((group, index) => (
        <Card
          key={group.shopId}
          title={shops[group.shopId]?.value?.name || `Gian hàng ${index + 1}`}
        >
          {group.items.map((item) => (
            <CartLine
              key={`${item.productId}::${item.variantId || ''}`}
              item={item}
              products={products}
              busy={busy}
              onQuantity={(line, quantity) =>
                change(() => updateCartItem(line.productId, line.variantId, quantity))
              }
              onRemove={(line) => change(() => removeCartItem(line.productId, line.variantId))}
            />
          ))}
          <Lines
            lines={[
              `Tiền hàng: ${formatPrice(group.subtotal)}`,
              'Phí giao: Miễn phí',
              `Đơn ${index + 1}: ${formatPrice(group.subtotal)}`,
            ]}
          />
        </Card>
      ))}
      {issues.length > 0 && (
        <Card title="Giá đã thay đổi">
          <Lines
            lines={issues.map((issue) =>
              [
                issue.productId ? lineName(products, issue.productId, issue.variantId) : null,
                issue.message,
              ]
                .filter(Boolean)
                .join(': '),
            )}
          />
          <Button title="Cập nhật giỏ hàng" variant="secondary" onPress={load} />
        </Card>
      )}
      {items.length > 0 && (
        <>
          <Label>Địa chỉ nhận hàng</Label>
          {address ? (
            <Choice value={formatAddress(address)} />
          ) : (
            <Notice tone="warning">Chưa có địa chỉ nhận hàng. Thêm địa chỉ để đặt hàng.</Notice>
          )}
          <Button
            title={address ? 'Sửa địa chỉ' : 'Thêm địa chỉ'}
            variant="secondary"
            onPress={() =>
              router.push(
                address
                  ? { pathname: '/addresses', params: { pick: '1' } }
                  : { pathname: '/addresses/[id]', params: { id: 'new' } },
              )
            }
          />
          <Label>Phương thức thanh toán</Label>
          <Choice
            value="VNPay · thanh toán từng đơn"
            selected={checkout.paymentMethod === 'VNPAY'}
            onPress={() => setCheckout({ paymentMethod: 'VNPAY' })}
          />
          <Choice
            value="Thanh toán khi nhận hàng (COD)"
            selected={checkout.paymentMethod === 'COD'}
            onPress={() => setCheckout({ paymentMethod: 'COD' })}
          />
          <Heading>{`Tổng: ${formatPrice(total)}`}</Heading>
          <Caption>
            Giá và tồn kho được kiểm tra lại trước khi tạo đơn. Miễn phí vận chuyển.
          </Caption>
          {notice ? <Notice tone="danger">{notice}</Notice> : null}
          <Button
            title={`Đặt hàng · ${formatPrice(total)}`}
            loading={busy}
            disabled={!address}
            onPress={placeOrder}
          />
        </>
      )}
      <Button title="Đơn hàng của tôi" variant="secondary" onPress={() => router.push('/orders')} />
    </Screen>
  )
}

const useStyles = makeStyles(() => ({
  line: { gap: spacing(2) },
  row: { flexDirection: 'row', gap: spacing(2) },
  flex: { flex: 1 },
}))
