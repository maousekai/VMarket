import { api, getApiError } from '@/api/client'

/** review-service: POST /api/reviews, GET /api/reviews/order/{id}/pending (BUYER, order COMPLETED). */
export const MAX_REVIEW_IMAGES = 5

export interface CreateReviewInput {
  orderId: string
  orderItemId?: string
  productId: string
  rating: number
  comment: string
  imageUrls: string[]
}

export interface PendingReviewItem {
  orderItemId: string
  productId: string
}

const UNAVAILABLE_CODES = new Set([
  'ORDER_NOT_REVIEWABLE',
  'REVIEW_ALREADY_EXISTS',
  'ORDER_NOT_FOUND',
])

/**
 * True when the buyer cannot review this order right now: the order is not COMPLETED yet / already
 * reviewed / not theirs (review-service codes), or the endpoint is not deployed (404/405/501).
 */
export function isReviewApiMissing(error: unknown): boolean {
  const { status, code } = getApiError(error)
  return UNAVAILABLE_CODES.has(code) || status === 404 || status === 405 || status === 501
}

export async function createReview(input: CreateReviewInput) {
  return (await api.post('/api/reviews', input)).data
}

/** Order items not reviewed yet (409 ORDER_NOT_REVIEWABLE until the order is COMPLETED). */
export async function listPendingReviews(orderId: string): Promise<PendingReviewItem[]> {
  return (await api.get<PendingReviewItem[]>(`/api/reviews/order/${orderId}/pending`)).data
}
