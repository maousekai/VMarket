import { api, getApiError } from '@/api/client'
import type { OrderItem } from '@/api/orders'

/** review-service via the gateway: /api/reviews/** (buyer: POST /, GET /me; public: products/shops). */
export const MAX_REVIEW_COMMENT = 2000

export interface CreateReviewInput {
  orderId: string
  productId: string
  /** 1..5 */
  rating: number
  comment?: string
  /** http(s) URLs of the VMarket image store only; the app has no upload API so it sends none. */
  imageUrls?: string[]
}

export interface Review {
  id: string
  productId: string
  shopId: string
  orderId?: string
  variantId?: string
  buyerId?: string
  rating: number
  comment?: string
  imageUrls?: string[]
  status: string
  createdAt: string
  updatedAt?: string
}

export interface ReviewPage {
  items: Review[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface RatingSummary {
  targetId: string
  average: number
  count: number
  /** Count per star, keys "1".."5". */
  distribution: Record<string, number>
}

const NOT_REVIEWABLE_CODES = new Set([
  'ORDER_NOT_COMPLETED',
  'ORDER_NOT_FOUND',
  'PRODUCT_NOT_IN_ORDER',
])

/** The order cannot be reviewed (not completed yet / not mine / the product is not in it). */
export function isNotReviewable(error: unknown): boolean {
  return NOT_REVIEWABLE_CODES.has(getApiError(error).code)
}

/** 409 REVIEW_ALREADY_EXISTS: this product of the order was already reviewed. */
export function isAlreadyReviewed(error: unknown): boolean {
  return getApiError(error).code === 'REVIEW_ALREADY_EXISTS'
}

/** 503 DEPENDENCY_UNAVAILABLE: order/product service down, safe to retry later. */
export function isReviewDependencyDown(error: unknown): boolean {
  return getApiError(error).code === 'DEPENDENCY_UNAVAILABLE'
}

export async function createReview(input: CreateReviewInput): Promise<Review> {
  return (await api.post<Review>('/api/reviews', input)).data
}

/** My reviews, optionally limited to one order. */
export async function listMyReviews(params: {
  orderId?: string
  page?: number
  size?: number
}): Promise<ReviewPage> {
  return (await api.get<ReviewPage>('/api/reviews/me', { params })).data
}

/** Product ids of the order that the buyer has already reviewed. */
export async function listReviewedProductIds(orderId: string): Promise<Set<string>> {
  const ids = new Set<string>()
  for (let page = 0; page < 10; page++) {
    const res = await listMyReviews({ orderId, page, size: 50 })
    res.items.forEach((r) => ids.add(r.productId))
    if (page + 1 >= res.totalPages) break
  }
  return ids
}

/** Order items still to review = items whose product has no review of mine in this order yet. */
export async function listPendingReviews(
  items: OrderItem[],
  orderId: string,
): Promise<OrderItem[]> {
  const reviewed = await listReviewedProductIds(orderId)
  return items.filter((i) => !reviewed.has(i.productId))
}

export async function listProductReviews(
  productId: string,
  params: { rating?: number; page?: number; size?: number } = {},
): Promise<ReviewPage> {
  return (await api.get<ReviewPage>(`/api/reviews/products/${productId}`, { params })).data
}

export async function getProductRatingSummary(productId: string): Promise<RatingSummary> {
  return (await api.get<RatingSummary>(`/api/reviews/products/${productId}/summary`)).data
}
