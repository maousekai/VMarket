import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { getApiError, newIdempotencyKey } from '@/api/client'
import {
  getProductInfo,
  getShopName,
  listAddresses,
  placeOrder,
  type Order,
  type PaymentMethod,
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
import { variantLabel } from './format'

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

export type PlaceResult = { kind: 'issues' } | { kind: 'order'; order: Order }

/**
 * The key of the last order attempt, kept at module level (not in the component) so that leaving
 * and re-entering checkout after a timeout/5xx reuses it. It is tied to user + address + payment
 * method + cart contents, and dropped on success or a non-retryable error.
 */
let placeAttempt: { key: string; signature: string } | null = null

const cartSignature = (cart: Cart | undefined) =>
  (cart?.groups ?? [])
    .flatMap((g) => g.items)
    .map((i) => `${i.productId}:${i.variantId ?? ''}:${i.quantity}:${i.unitPrice}`)
    .sort()
    .join(',')

/**
 * Validates the cart (unless skipped) and creates the order. The Idempotency-Key belongs to one
 * user action: it is kept while retrying after a network/5xx failure, so a lost response never
 * creates a second order.
 */
export function usePlaceOrder(paymentMethod: PaymentMethod, addressId: string | undefined) {
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const userId = user?.userId ?? ''

  const mutation = useMutation({
    mutationFn: async (options: { skipValidation?: boolean } = {}): Promise<PlaceResult> => {
      if (!addressId) throw new Error('NO_ADDRESS')
      if (!options.skipValidation && !(await validateCart()).ok) return { kind: 'issues' }
      const signature = [
        userId,
        addressId,
        paymentMethod,
        cartSignature(queryClient.getQueryData<Cart>(CART_KEY)),
      ].join('|')
      if (placeAttempt?.signature !== signature) {
        placeAttempt = { key: newIdempotencyKey(), signature }
      }
      const attemptKey = placeAttempt.key
      try {
        const order = await placeOrder({ addressId, paymentMethod, idempotencyKey: attemptKey })
        placeAttempt = null
        void queryClient.invalidateQueries({ queryKey: CART_KEY })
        return { kind: 'order', order }
      } catch (error) {
        if (!getApiError(error).retryable) placeAttempt = null
        throw error
      }
    },
  })
  return { submit: mutation.mutateAsync, isPending: mutation.isPending }
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
