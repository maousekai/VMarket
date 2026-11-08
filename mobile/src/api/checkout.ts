import { api } from '@/api/client'

export type PaymentMethod = 'COD' | 'PAYOS'

export type OrderStatus =
  'WAITING_PAYMENT' | 'PENDING' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED' | 'COMPLETED' | 'CANCELLED'

export interface OrderItem {
  productId: string
  variantId: string | null
  shopId: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface Order {
  id: string
  status: OrderStatus
  recipientName: string
  phone: string
  province: string
  district: string | null
  ward: string
  streetAddress: string
  note: string | null
  totalAmount: number
  createdAt: string
  items: OrderItem[]
  paymentMethod: PaymentMethod
  paymentExpiresAt: string | null
  stockReserved: boolean
  /** Set by the split-order backend (one order per shop); absent on old orders. */
  shopId?: string | null
  checkoutId?: string | null
}

export interface PlaceOrderResult {
  checkoutId: string
  totalAmount: number
  orders: Order[]
}

export interface Address {
  id: string
  recipientName: string
  phone: string
  province: string
  district: string | null
  ward: string
  streetAddress: string
  isDefault: boolean
}

export interface ProductVariantInfo {
  id: string
  attributes: Record<string, string>
  price: number
  availableStock: number
}

export interface ProductInfo {
  id: string
  name: string
  imageUrl: string | null
  availableStock: number
  variants: ProductVariantInfo[]
}

interface ProductDto {
  id: string
  name: string
  imageUrls?: string[]
  availableStock?: number
  variants?: {
    id: string
    attributes?: Record<string, string>
    price: number
    availableStock?: number
  }[]
}

/** Checkout always takes the whole cart; `idempotencyKey` must be reused only when retrying. */
export async function placeOrder(input: {
  addressId: string
  paymentMethod: PaymentMethod
  idempotencyKey: string
}): Promise<PlaceOrderResult> {
  const { idempotencyKey, ...body } = input
  return (
    await api.post<PlaceOrderResult>('/api/orders', body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  ).data
}

export async function getOrder(orderId: string): Promise<Order> {
  return (await api.get<Order>(`/api/orders/${orderId}`)).data
}

export async function listAddresses(): Promise<Address[]> {
  return (await api.get<Address[]>('/api/users/me/addresses')).data
}

export async function getProductInfo(productId: string): Promise<ProductInfo> {
  const { product } = (
    await api.get<{ product: ProductDto }>(`/api/products/${productId}`, { skipAuth: true })
  ).data
  return {
    id: product.id,
    name: product.name,
    imageUrl: product.imageUrls?.[0] ?? null,
    availableStock: product.availableStock ?? 0,
    variants: (product.variants ?? []).map((v) => ({
      id: v.id,
      attributes: v.attributes ?? {},
      price: v.price,
      availableStock: v.availableStock ?? 0,
    })),
  }
}

export async function getShopName(shopId: string): Promise<string> {
  return (await api.get<{ name: string }>(`/api/shops/${shopId}`, { skipAuth: true })).data.name
}
