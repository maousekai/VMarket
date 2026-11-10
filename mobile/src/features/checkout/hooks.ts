import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { router, type Href } from 'expo-router'
import { getApiError, newIdempotencyKey } from '@/api/client'
import {
  getProductInfo,
  getShopName,
  listAddresses,
  placeOrder,
  type PaymentMethod,
  type PlaceOrderResult,
  type ProductInfo,
} from '@/api/checkout'
import {
  getCart,
  removeItem,
  repriceItem,
  updateQuantity,
  validateCart,
  type Cart,
  type CartIssue,
  type CartItem,
} from '@/api/cart'
import { useAuth } from '@/auth/AuthProvider'
import { useToast } from '@/components/ui'
import { variantLabel } from './format'
import { attemptSignature, resolveAttempt, type PlaceAttempt } from './placeAttempt'

export const CART_KEY = ['cart'] as const
const DISPLAY_STALE_MS = 5 * 60_000

export interface CartLineView {
  item: CartItem
  name: string
  variant: string
  imageUrl: string | null
  /** Stock available for this line (999 while the product is still loading). */
  maxQuantity: number
}

export interface CartGroupView {
  shopId: string
  shopName: string
  lines: CartLineView[]
  subtotal: number
}

/** Cart + product and shop display data (the cart API only stores ids and prices). */
export function useCartDetails() {
  const { status } = useAuth()
  const cartQuery = useQuery({
    queryKey: CART_KEY,
    queryFn: getCart,
    enabled: status === 'authenticated',
  })
  const cart = cartQuery.data

  const productIds = [...new Set(cart?.groups.flatMap((g) => g.items.map((i) => i.productId)))]
  const shopIds = cart?.groups.map((g) => g.shopId) ?? []
  const products = useQueries({
    queries: productIds.map((id) => ({
      queryKey: ['cart-product', id],
      queryFn: () => getProductInfo(id),
      staleTime: DISPLAY_STALE_MS,
      retry: false,
    })),
  })
  const shops = useQueries({
    queries: shopIds.map((id) => ({
      queryKey: ['cart-shop', id],
      queryFn: () => getShopName(id),
      staleTime: DISPLAY_STALE_MS,
      retry: false,
    })),
  })

  const productById = new Map<string, ProductInfo>()
  products.forEach((q, i) => q.data && productById.set(productIds[i], q.data))

  const groups: CartGroupView[] = (cart?.groups ?? []).map((group, index) => ({
    shopId: group.shopId,
    shopName: shops[index]?.data ?? `Gian hàng ${index + 1}`,
    subtotal: group.subtotal,
    lines: group.items.map((item) => {
      const product = productById.get(item.productId)
      const variant = product?.variants.find((v) => v.id === item.variantId)
      return {
        item,
        name: product?.name ?? 'Sản phẩm',
        variant: variantLabel(variant?.attributes),
        imageUrl: product?.imageUrl ?? null,
        maxQuantity: Math.min(999, variant?.availableStock ?? product?.availableStock ?? 999),
      }
    }),
  }))

  return { cartQuery, cart, groups }
}

const sameLine = (
  a: { productId?: string; variantId?: string | null },
  b: { productId?: string; variantId?: string | null },
) => a.productId === b.productId && (a.variantId ?? null) === (b.variantId ?? null)

/** Finds the display line an issue refers to. */
export const findLine = (groups: CartGroupView[], issue: CartIssue) =>
  groups.flatMap((g) => g.lines).find((l) => sameLine(l.item, issue))

export function useCartActions() {
  const queryClient = useQueryClient()
  const setCart = (cart: Cart) => queryClient.setQueryData(CART_KEY, cart)
  const quantity = useMutation({
    mutationFn: ({ item, quantity: q }: { item: CartItem; quantity: number }) =>
      updateQuantity(item, q),
    onSuccess: setCart,
  })
  const remove = useMutation({ mutationFn: removeItem, onSuccess: setCart })
  return { quantity, remove }
}

export const useAddresses = (enabled: boolean) =>
  useQuery({ queryKey: ['addresses'], queryFn: listAddresses, enabled })

export type PlaceResult = { kind: 'issues' } | { kind: 'placed'; result: PlaceOrderResult }

/**
 * The last order attempt, kept at module level (not in the component) so that leaving and
 * re-entering checkout after a timeout/5xx reuses its key. It is tied to user + address + payment
 * method (see placeAttempt.ts) and dropped on success or a non-retryable error.
 */
let placeAttempt: PlaceAttempt | null = null

/**
 * Validates the cart (unless skipped) and creates the order. The Idempotency-Key belongs to one
 * user action: it is kept while retrying after a network/5xx failure, so a lost response never
 * creates a second order.
 */
export function usePlaceOrder(paymentMethod: PaymentMethod, addressId: string | undefined) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const userId = user?.userId ?? ''
  const toast = useToast()

  const mutation = useMutation({
    mutationFn: async (options: { skipValidation?: boolean } = {}): Promise<PlaceResult> => {
      if (!addressId) throw new Error('NO_ADDRESS')
      const { attempt, retry } = resolveAttempt(
        placeAttempt,
        attemptSignature(userId, addressId, paymentMethod),
        Date.now(),
        newIdempotencyKey,
      )
      // Retrying an attempt whose result never arrived: the server may already have created the
      // orders and emptied the cart, so go straight to POST with the same key (it replays them,
      // or validates the cart itself when nothing was created) instead of judging the cart first.
      if (!retry && !options.skipValidation && !(await validateCart()).ok) {
        return { kind: 'issues' }
      }
      placeAttempt = attempt
      try {
        const result = await placeOrder({ addressId, paymentMethod, idempotencyKey: attempt.key })
        placeAttempt = null
        void queryClient.invalidateQueries({ queryKey: CART_KEY })
        return { kind: 'placed', result }
      } catch (error) {
        if (!getApiError(error).retryable) placeAttempt = null
        throw error
      }
    },
  })

  /** Shows the error; a changed cart is refreshed and the user is sent back to review it. */
  const handleError = (error: unknown) => {
    const e = getApiError(error)
    if (e.code === 'CART_CHANGED') {
      void queryClient.invalidateQueries({ queryKey: CART_KEY })
      toast.show({ type: 'error', message: e.message })
      router.replace('/cart' as Href)
    } else if (e.code === 'CART_VALIDATION_UNAVAILABLE') {
      toast.show({
        type: 'error',
        message: 'Chưa kiểm tra được giỏ hàng lúc này, vui lòng thử lại sau.',
      })
    } else {
      toast.show({ type: 'error', message: e.message })
    }
    return e
  }

  return { submit: mutation.mutateAsync, isPending: mutation.isPending, handleError }
}

/** One order keeps the old flow; several orders (one per shop) go to the checkout summary. */
export function goAfterPlace(result: PlaceOrderResult, paymentMethod: PaymentMethod) {
  if (result.orders.length > 1) {
    router.replace({
      pathname: '/checkout/placed',
      params: { ids: result.orders.map((o) => o.id).join(',') },
    } as never)
  } else if (paymentMethod === 'COD') {
    router.replace({
      pathname: '/checkout/cod-success',
      params: { ids: result.orders[0].id },
    } as never)
  } else {
    router.replace(`/payment/${result.orders[0].id}` as Href)
  }
}

/** Writes validation fixes into the cart: the order snapshots cart prices and quantities. */
export async function applyIssues(cart: Cart, issues: CartIssue[]) {
  const items = cart.groups.flatMap((g) => g.items)
  for (const issue of issues) {
    const item = items.find((i) => sameLine(i, issue))
    if (!item) continue
    if (issue.type === 'PRICE_CHANGED' && issue.currentPrice !== undefined) {
      await repriceItem(item, issue.currentPrice)
    } else if (issue.type === 'OUT_OF_STOCK' && (issue.availableStock ?? 0) > 0) {
      await updateQuantity(item, issue.availableStock as number)
    } else if (['NOT_FOUND', 'SHOP_CHANGED', 'OUT_OF_STOCK'].includes(issue.type)) {
      await removeItem(item)
    }
  }
}
