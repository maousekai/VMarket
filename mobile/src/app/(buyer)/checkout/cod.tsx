import { router, useLocalSearchParams, type Href } from 'expo-router'

import { getApiError } from '@/api/client'
import { AppText, ErrorState, Screen, Skeleton, useToast } from '@/components/ui'
import { StatusScreen } from '@/features/checkout/components/StatusScreen'
import { addressLine, recipientLine } from '@/features/checkout/format'
import { useAddresses, useCartDetails, usePlaceOrder } from '@/features/checkout/hooks'
import { formatVnd } from '@/lib/format'

/** Figma 3.36 "Đặt hàng COD": confirmation before creating a cash-on-delivery order. */
export default function CheckoutCodScreen() {
  const { addressId } = useLocalSearchParams<{ addressId?: string }>()
  const toast = useToast()
  const { cartQuery, cart, groups } = useCartDetails()
  const addressesQuery = useAddresses(true)
  const placeOrder = usePlaceOrder('COD', addressId)
  const address = addressesQuery.data?.find((a) => a.id === addressId)

  const backToCart = () => router.replace('/cart' as Href)

  if (cartQuery.isPending || addressesQuery.isPending) {
    return (
      <Screen title="Đặt hàng COD" onBack={backToCart}>
        <Skeleton height={90} />
        <Skeleton height={90} />
      </Screen>
    )
  }
  if (cartQuery.isError || addressesQuery.isError) {
    return (
      <Screen title="Đặt hàng COD" onBack={backToCart}>
        <ErrorState
          error={cartQuery.error ?? addressesQuery.error}
          onRetry={() => {
            void cartQuery.refetch()
            void addressesQuery.refetch()
          }}
          secondaryLabel="Quay lại giỏ hàng"
          onSecondary={backToCart}
        />
      </Screen>
    )
  }
  if (!address || !cart || groups.length === 0) {
    return (
      <Screen title="Đặt hàng COD" onBack={backToCart}>
        <ErrorState
          heading="Chưa thể đặt hàng"
          title="Thiếu thông tin đơn hàng"
          body="Giỏ hàng trống hoặc chưa chọn địa chỉ nhận hàng."
          retryLabel="Quay lại giỏ hàng"
          onRetry={backToCart}
        />
      </Screen>
    )
  }

  const onPlace = async () => {
    try {
      const result = await placeOrder.submit({})
      if (result.kind === 'issues') {
        router.push({ pathname: '/checkout/review', params: { pm: 'COD', addressId } } as never)
      } else {
        router.replace({
          pathname: '/checkout/cod-success',
          params: { ids: result.order.id },
        } as never)
      }
    } catch (error) {
      toast.show({ type: 'error', message: getApiError(error).message })
    }
  }

  return (
    <StatusScreen
      title="Đặt hàng COD"
      onBack={() => router.back()}
      summary="Thanh toán tiền mặt khi nhận hàng"
      cards={[
        { title: 'Giao đến', body: `${recipientLine(address)}\n${addressLine(address)}` },
        {
          title: 'Đơn hàng',
          body: groups.map((g) => `${g.shopName}: ${formatVnd(g.subtotal)}`).join('\n'),
        },
      ]}
      primary={{
        label: 'Đặt đơn COD',
        onPress: () => void onPlace(),
        loading: placeOrder.isPending,
        testID: 'cod-place-order',
      }}
      secondary={{ label: 'Quay lại giỏ hàng', onPress: () => router.back(), testID: 'cod-back' }}
      testID="checkout-cod"
    >
      <AppText variant="h2">Tổng cần trả: {formatVnd(cart.totalAmount)}</AppText>
    </StatusScreen>
  )
}
