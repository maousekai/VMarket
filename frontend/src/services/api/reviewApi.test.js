import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager } from './apiClient'
import {
  createReview,
  getProductReviews,
  getShopRatingSummary,
  hideReview,
  listMyReviews,
  listSellerReviews,
  replyToReview,
  searchReviews,
  uploadReviewImage,
} from './reviewApi'

const originalAdapter = apiClient.defaults.adapter
let adapter

beforeEach(() => {
  bindSessionManager({
    getSnapshot: () => ({ session: { userId: 'u1', accessToken: 'token-1' } }),
    getGeneration: () => 1,
  })
  adapter = vi.fn(async (config) => ({ data: {}, status: 200, config, headers: {} }))
  apiClient.defaults.adapter = adapter
})

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
  bindSessionManager(undefined)
})

const sent = () => adapter.mock.calls[0][0]

describe('review API', () => {
  it('reads public product reviews without a token and only sends set filters', async () => {
    await getProductReviews('p 1', { page: 2, size: 5 })
    expect(sent().url).toBe('/api/reviews/products/p%201')
    expect(sent().params).toEqual({ page: 2, size: 5 })
    expect(sent().headers.Authorization).toBeUndefined()

    adapter.mockClear()
    await getShopRatingSummary('s1')
    expect(sent().url).toBe('/api/reviews/shops/s1/summary')
    expect(sent().headers.Authorization).toBeUndefined()
  })

  it('creates a review with a trimmed comment and the buyer token', async () => {
    await createReview({ orderId: 'o1', productId: 'p1', rating: 5, comment: '  ', imageUrls: [] })
    expect(sent().method).toBe('post')
    expect(sent().url).toBe('/api/reviews')
    expect(sent().headers.Authorization).toBe('Bearer token-1')
    expect(JSON.parse(sent().data)).toEqual({
      orderId: 'o1',
      productId: 'p1',
      rating: 5,
      comment: null,
      imageUrls: [],
    })
  })

  it('lists my reviews of an order and uploads review images as multipart', async () => {
    await listMyReviews({ orderId: 'o1' })
    expect(sent().url).toBe('/api/reviews/me')
    expect(sent().params).toEqual({ orderId: 'o1', page: 0, size: 50 })

    adapter.mockClear()
    await uploadReviewImage(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))
    expect(sent().url).toBe('/api/reviews/media')
    expect(sent().data).toBeInstanceOf(FormData)
  })

  it('filters seller reviews by stars and reply state', async () => {
    await listSellerReviews({ replied: false })
    expect(sent().params).toEqual({ page: 0, size: 10, replied: false })

    adapter.mockClear()
    await listSellerReviews({ rating: 1 })
    expect(sent().params).toEqual({ page: 0, size: 10, rating: 1 })

    adapter.mockClear()
    await replyToReview('r1', '  Cảm ơn  ')
    expect(sent().method).toBe('put')
    expect(sent().url).toBe('/api/reviews/seller/r1/reply')
    expect(JSON.parse(sent().data)).toEqual({ content: 'Cảm ơn' })
  })

  it('moderates reviews as admin', async () => {
    await searchReviews({ status: 'HIDDEN', productId: ' p1 ' })
    expect(sent().params).toEqual({ page: 0, size: 20, status: 'HIDDEN', productId: 'p1' })

    adapter.mockClear()
    await hideReview('r1', ' Spam ')
    expect(sent().url).toBe('/api/reviews/admin/r1/hide')
    expect(JSON.parse(sent().data)).toEqual({ reason: 'Spam' })
  })
})
