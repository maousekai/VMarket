import { router, useFocusEffect, type Href } from 'expo-router'
import { useCallback, useState } from 'react'

import { getApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { useRequireAuth } from '@/auth/useRequireAuth'
import {
  AppText,
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorState,
  Screen,
  Skeleton,
  TextField,
  useToast,
} from '@/components/ui'
import { AddressSection } from '@/features/checkout/components/AddressSection'
import { ShopGroupCard } from '@/features/checkout/components/ShopGroupCard'
import {
  useAddresses,
  useCartActions,
  goAfterPlace,
  useCartDetails,
  usePlaceOrder,
  type CartLineView,
} from '@/features/checkout/hooks'
import { formatVnd } from '@/lib/format'

const HOME = '/(buyer)' as Href

export default function CartScreen() {
  const { status } = useAuth()
  const requireAuth = useRequireAuth()
  const toast = useToast()
  const { cartQuery, cart, groups } = useCartDetails()
  const { quantity, remove } = useCartActions()
  const addressesQuery = useAddresses(status === 'authenticated')
  const [pickedAddressId, setPickedAddressId] = useState<string>()
  const [toRemove, setToRemove] = useState<CartLineView | null>(null)

  const addresses = addressesQuery.data
  const addressId =
    addresses?.find((a) => a.id === pickedAddressId)?.id ??
    addresses?.find((a) => a.isDefault)?.id ??
    addresses?.[0]?.id
  const placeOrder = usePlaceOrder('PAYOS', addressId)

  const { refetch: refetchCart } = cartQuery
  const { refetch: refetchAddresses } = addressesQuery
  useFocusEffect(
    useCallback(() => {
      if (status === 'authenticated') {
        void refetchCart()
        void refetchAddresses()
      }
    }, [status, refetchCart, refetchAddresses]),
  )

  const onOrder = async () => {
    if (!addressId) {
      toast.show({ type: 'error', message: 'Vui lòng chọn địa chỉ nhận hàng.' })
      return
    }
    try {
      const result = await placeOrder.submit({})
      if (result.kind === 'issues') {
        router.push({ pathname: '/checkout/review', params: { pm: 'PAYOS', addressId } } as never)
      } else {
        goAfterPlace(result.result, 'PAYOS')
      }
    } catch (error) {
      const e = placeOrder.handleError(error)
      if (e.code === 'CART_EMPTY') void refetchCart()
      if (e.code === 'ADDRESS_NOT_FOUND') void refetchAddresses()
    }
  }

  const onChooseCod = () => {
    if (!addressId) {
      toast.show({ type: 'error', message: 'Vui lòng chọn địa chỉ nhận hàng.' })
      return
    }
    router.push({ pathname: '/checkout/cod', params: { addressId } } as never)
  }

  const onRemove = () => {
    if (!toRemove) return
    remove.mutate(toRemove.item, {
      onError: (error) => toast.show({ type: 'error', message: getApiError(error).message }),
      onSettled: () => setToRemove(null),
    })
  }

  if (status === 'guest') {
    return (
      <Screen title="Giỏ hàng">
        <EmptyState
          heading="Đăng nhập để xem giỏ hàng"
          title="Bạn chưa đăng nhập"
          body="Đăng nhập để thêm sản phẩm và đặt hàng."
          primaryLabel="Đăng nhập"
          onPrimary={() => requireAuth('/cart')}
          testID="cart-guest"
        />
      </Screen>
    )
  }

  if (status === 'loading' || cartQuery.isPending) {
    return (
      <Screen title="Giỏ hàng">
        <Skeleton height={140} />
        <Skeleton height={140} />
      </Screen>
    )
  }

  if (cartQuery.isError || !cart) {
    return (
      <Screen title="Giỏ hàng">
        <ErrorState error={cartQuery.error} onRetry={() => void refetchCart()} />
      </Screen>
    )
  }

  if (groups.length === 0) {
    return (
      <Screen
        title="Giỏ hàng"
        refreshing={cartQuery.isRefetching}
        onRefresh={() => void refetchCart()}
      >
        <EmptyState
          heading="Giỏ hàng đang trống"
          title="Giỏ hàng đang trống"
          body="Thêm sản phẩm để đặt hàng."
          primaryLabel="Tiếp tục mua sắm"
          onPrimary={() => router.navigate(HOME)}
          testID="cart-empty"
        />
      </Screen>
    )
  }

  return (
    <Screen
      title={`Giỏ hàng · ${groups.length} gian hàng`}
      refreshing={cartQuery.isRefetching}
      onRefresh={() => void refetchCart()}
      testID="cart-screen"
    >
      {groups.map((group) => (
        <ShopGroupCard
          key={group.shopId}
          group={group}
          busy={quantity.isPending || remove.isPending}
          onQuantity={(item, q) =>
            quantity.mutate(
              { item, quantity: q },
              {
                onError: (error) =>
                  toast.show({ type: 'error', message: getApiError(error).message }),
              },
            )
          }
          onRemove={setToRemove}
        />
      ))}
      {groups.length > 1 ? (
        <AppText variant="caption" color="textSecondary">
          Mỗi gian hàng sẽ được tách thành một đơn hàng riêng.
        </AppText>
      ) : null}

      <AddressSection
        addresses={addresses}
        loading={addressesQuery.isPending}
        selectedId={addressId}
        onSelect={setPickedAddressId}
      />

      <AppText variant="label">Phương thức thanh toán</AppText>
      <TextField
        picker
        value="PayOS · quét mã VietQR"
        onChangeText={() => undefined}
        disabled
        testID="checkout-method"
      />
      <Button
        title="Chọn thanh toán COD"
        kind="secondary"
        onPress={onChooseCod}
        testID="checkout-choose-cod"
      />

      <AppText variant="h2">Tổng: {formatVnd(cart.totalAmount)}</AppText>
      <AppText variant="caption" color="textSecondary">
        Giá và tồn kho được kiểm tra lại trước khi tạo đơn.
      </AppText>
      <Button
        title={`Đặt hàng · ${formatVnd(cart.totalAmount)}`}
        onPress={() => void onOrder()}
        loading={placeOrder.isPending}
        disabled={!addressId}
        testID="checkout-place-order"
      />

      <ConfirmDialog
        visible={!!toRemove}
        title="Xóa sản phẩm?"
        message={toRemove ? `Xóa "${toRemove.name}" khỏi giỏ hàng?` : undefined}
        confirmLabel="Xóa"
        destructive
        loading={remove.isPending}
        onConfirm={onRemove}
        onCancel={() => setToRemove(null)}
      />
    </Screen>
  )
}
