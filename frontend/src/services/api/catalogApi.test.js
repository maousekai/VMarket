import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager } from './apiClient'
import { browseProducts, listCategories, PRODUCT_SORT } from './productApi'
import { getForYou } from './recommendationApi'
import { getPublicShop } from './shopApi'

const originalAdapter = apiClient.defaults.adapter
let adapter
let response

const session = { userId: 'buyer-1', accessToken: 'token-123' }

beforeEach(() => {
  response = { ok: true }
  bindSessionManager({ getSnapshot: () => ({ session }), getGeneration: () => 1 })
  adapter = vi.fn(async (config) => ({ data: response, status: 200, config, headers: {} }))
  apiClient.defaults.adapter = adapter
})

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
  bindSessionManager(undefined)
})

const sent = () => adapter.mock.calls[0][0]

describe('product catalog API', () => {
  it('lists categories without sending the session token', async () => {
    response = [{ id: 'c1', name: 'Thời trang' }]
    await expect(listCategories()).resolves.toEqual(response)
    expect(sent().url).toBe('/api/products/categories')
    expect(sent().headers.get('Authorization')).toBeFalsy()
  })

  it('browses with sort and size and omits empty filters', async () => {
    await browseProducts({ sort: PRODUCT_SORT.bestSelling, size: 8, keyword: '  ' })
    expect(sent().url).toBe('/api/products')
    expect(sent().params).toEqual({ sort: 'BEST_SELLING', page: 0, size: 8 })
    expect(sent().headers.get('Authorization')).toBeFalsy()
  })

  it('passes a trimmed keyword and category filter', async () => {
    await browseProducts({ keyword: ' áo thun ', categoryId: 'cat-1' })
    expect(sent().params).toMatchObject({ q: 'áo thun', categoryId: 'cat-1', sort: 'NEWEST' })
  })
})

describe('recommendation API', () => {
  it('requests personal picks with the bearer token and returns the items', async () => {
    response = { user_id: 'buyer-1', items: [{ id: 'p1' }] }
    await expect(getForYou({ limit: 8 })).resolves.toEqual([{ id: 'p1' }])
    expect(sent().url).toBe('/api/ai/recommendations/for-you')
    expect(sent().params).toEqual({ limit: 8 })
    expect(sent().headers.get('Authorization')).toBe('Bearer token-123')
  })

  it('returns an empty list when the payload has no items', async () => {
    response = {}
    await expect(getForYou()).resolves.toEqual([])
  })
})

describe('public shop API', () => {
  it('reads a public shop by encoded id without the token', async () => {
    response = { id: 'shop/1', name: 'Shop' }
    await getPublicShop('shop/1')
    expect(sent().url).toBe('/api/shops/shop%2F1')
    expect(sent().headers.get('Authorization')).toBeFalsy()
  })
})
