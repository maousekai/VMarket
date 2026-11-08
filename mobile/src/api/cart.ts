import { api } from '@/api/client'

export interface CartItem {
  productId: string
  variantId: string | null
  shopId: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface CartGroup {
  shopId: string
  items: CartItem[]
  subtotal: number
}

export interface Cart {
  groups: CartGroup[]
  totalQuantity: number
  totalAmount: number
}

export type CartIssueType =
  | 'CART_EMPTY'
  | 'NOT_FOUND'
  | 'SHOP_CHANGED'
  | 'PRICE_CHANGED'
  | 'OUT_OF_STOCK'
  | 'PRODUCT_DATA_INVALID'
  | 'SERVICE_UNAVAILABLE'

export interface CartIssue {
  productId?: string
  variantId?: string | null
  type: CartIssueType
  message?: string
  cartPrice?: number
  currentPrice?: number
  requestedQuantity?: number
  availableStock?: number
}

export interface CartValidation {
  ok: boolean
  issues: CartIssue[]
}

export interface AddCartItemInput {
  productId: string
  variantId?: string | null
  quantity: number
}

interface ProductVariantDto {
  id: string
  price: number
}

interface ProductDto {
  id: string
  shopId: string
  minPrice: number | null
  variants?: ProductVariantDto[]
}

const variantParams = (variantId?: string | null) => (variantId ? { variantId } : undefined)

export async function getCart(): Promise<Cart> {
  return (await api.get<Cart>('/api/cart')).data
}

/**
 * Adds a product (merges with an existing line). The cart API wants the shop id and a price
 * snapshot, so both are read from the catalog; the server re-validates the price at checkout.
 */
export async function addCartItem(input: AddCartItemInput): Promise<void> {
  const { product } = (
    await api.get<{ product: ProductDto }>(`/api/products/${input.productId}`, { skipAuth: true })
  ).data
  const variant = input.variantId
    ? product.variants?.find((v) => v.id === input.variantId)
    : product.variants?.length === 1
      ? product.variants[0]
      : undefined
  const unitPrice = variant?.price ?? product.minPrice
  if (unitPrice === null || unitPrice === undefined) {
    throw new Error('Sản phẩm chưa có giá bán.')
  }
  await api.post('/api/cart/items', {
    productId: input.productId,
    variantId: input.variantId ?? variant?.id ?? null,
    shopId: product.shopId,
    quantity: input.quantity,
    unitPrice,
  })
}

export async function updateQuantity(item: CartItem, quantity: number): Promise<Cart> {
  return (
    await api.patch<Cart>(
      `/api/cart/items/${item.productId}`,
      { quantity },
      { params: variantParams(item.variantId) },
    )
  ).data
}

export async function removeItem(item: Pick<CartItem, 'productId' | 'variantId'>): Promise<Cart> {
  return (
    await api.delete<Cart>(`/api/cart/items/${item.productId}`, {
      params: variantParams(item.variantId),
    })
  ).data
}

export async function validateCart(): Promise<CartValidation> {
  const data = (await api.post<Partial<CartValidation>>('/api/cart/validate')).data
  return { ok: !!data.ok, issues: data.issues ?? [] }
}

/** No "accept new price" endpoint exists: delete the line, then add it again at the new price. */
export async function repriceItem(item: CartItem, currentPrice: number): Promise<void> {
  await removeItem(item)
  await api.post('/api/cart/items', {
    productId: item.productId,
    variantId: item.variantId,
    shopId: item.shopId,
    quantity: item.quantity,
    unitPrice: currentPrice,
  })
}
