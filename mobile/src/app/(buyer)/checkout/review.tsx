import { useQuery, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'
import { useState } from 'react'

import { validateCart, type CartIssue } from '@/api/cart'
import type { PaymentMethod } from '@/api/checkout'
import { ErrorState, Screen, Skeleton, useToast } from '@/components/ui'
import { StatusScreen, type StatusCard } from '@/features/checkout/components/StatusScreen'
import {
  CART_KEY,
  applyIssues,
  findLine,
  goAfterPlace,
  usePlaceOrder,
  useCartDetails,
} from '@/features/checkout/hooks'
import { formatVnd } from '@/lib/format'

const BLOCKING: CartIssue['type'][] = ['CART_EMPTY', 'SERVICE_UNAVAILABLE', 'PRODUCT_DATA_INVALID']

/** Figma "Giá đã thay đổi": pre-checkout validation result (prices / stock). */
export default function CheckoutReviewScreen() {
  const params = useLocalSearchParams<{ pm?: string; addressId?: string }>()
  const method: PaymentMethod = params.pm === 'COD' ? 'COD' : 'PAYOS'
  const queryClient = useQueryClient()
  const toast = useToast()
  const { cart, groups } = useCartDetails()
  const placeOrder = usePlaceOrder(method, params.addressId)
  const [applying, setApplying] = useState(false)

  const validation = useQuery({
    queryKey: ['cart-validation'],
    queryFn: validateCart,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  })

  const goBackToCart = () => router.replace('/cart' as Href)

  if (validation.isPending || !cart) {
    return (
      <Screen title="Kiểm tra trước thanh toán" onBack={goBackToCart}>
        <Skeleton height={90} />
        <Skeleton height={90} />
      </Screen>
    )
  }
  if (validation.isError) {
    return (
      <Screen title="Kiểm tra trước thanh toán" onBack={goBackToCart}>
        <ErrorState
          error={validation.error}
          onRetry={() => void validation.refetch()}
          secondaryLabel="Quay lại giỏ hàng"
          onSecondary={goBackToCart}
        />
      </Screen>
    )
  }

  const issues = validation.data.issues
  const nameOf = (issue: CartIssue) => {
    const line = findLine(groups, issue)
    return line ? [line.name, line.variant].filter(Boolean).join(' / ') : 'Sản phẩm'
  }
  const ofType = (...types: CartIssue['type'][]) => issues.filter((i) => types.includes(i.type))

  const priceChanged = ofType('PRICE_CHANGED')
  const outOfStock = ofType('OUT_OF_STOCK')
  const removed = ofType('NOT_FOUND', 'SHOP_CHANGED')
  const blocking = issues.filter((i) => BLOCKING.includes(i.type))

  const cards: StatusCard[] = []
  if (priceChanged.length) {
    cards.push({
      title: 'Giá đã thay đổi',
      body: priceChanged
        .map((i) => `${nameOf(i)}: ${formatVnd(i.cartPrice)} → ${formatVnd(i.currentPrice)}`)
        .join('\n'),
    })
  }
  if (outOfStock.length) {
    cards.push({
      title: 'Hết hàng',
      body: outOfStock
        .map((i) =>
          (i.availableStock ?? 0) > 0
            ? `${nameOf(i)}: chỉ còn ${i.availableStock}, số lượng sẽ được giảm xuống.`
            : `${nameOf(i)} đã được bỏ khỏi đơn.`,
        )
        .join('\n'),
    })
  }
  if (removed.length) {
    cards.push({
      title: 'Sản phẩm không còn bán',
      body: removed.map((i) => `${nameOf(i)} đã được bỏ khỏi đơn.`).join('\n'),
    })
  }
  if (blocking.length) {
    cards.push({
      title: 'Chưa kiểm tra được',
      body: blocking.map((i) => i.message ?? 'Vui lòng thử lại sau.').join('\n'),
    })
  }

  // Subtotal after the fixes that "Tiếp tục thanh toán" applies to the cart.
  const newSubtotal = cart.groups
    .flatMap((g) => g.items)
    .reduce((sum, item) => {
      const issue = issues.find(
        (i) => i.productId === item.productId && (i.variantId ?? null) === (item.variantId ?? null),
      )
      let quantity = item.quantity
      let price = item.unitPrice
      if (issue?.type === 'PRICE_CHANGED') price = issue.currentPrice ?? price
      if (issue?.type === 'OUT_OF_STOCK') quantity = issue.availableStock ?? 0
      if (issue?.type === 'NOT_FOUND' || issue?.type === 'SHOP_CHANGED') quantity = 0
      return sum + price * quantity
    }, 0)
  cards.push({ title: 'Tạm tính mới', body: formatVnd(newSubtotal) })

  const onContinue = async () => {
    if (blocking.length) {
      void validation.refetch()
      return
    }
    setApplying(true)
    try {
      await applyIssues(cart, issues)
      await queryClient.invalidateQueries({ queryKey: CART_KEY })
      const result = await placeOrder.submit({})
      if (result.kind === 'issues') {
        toast.show({
          type: 'info',
          message: 'Giá hoặc tồn kho vừa thay đổi tiếp, vui lòng xem lại.',
        })
        void validation.refetch()
      } else {
        goAfterPlace(result.result, method)
      }
    } catch (error) {
      const e = placeOrder.handleError(error)
      if (e.code !== 'CART_CHANGED') void queryClient.invalidateQueries({ queryKey: CART_KEY })
    } finally {
      setApplying(false)
    }
  }

  return (
    <StatusScreen
      title="Kiểm tra trước thanh toán"
      onBack={goBackToCart}
      cards={cards}
      primary={{
        label: blocking.length ? 'Kiểm tra lại' : 'Tiếp tục thanh toán',
        onPress: () => void onContinue(),
        loading: applying || placeOrder.isPending,
        testID: 'review-continue',
      }}
      secondary={{ label: 'Quay lại giỏ hàng', onPress: goBackToCart, testID: 'review-back' }}
      testID="checkout-review"
    />
  )
}
