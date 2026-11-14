import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager } from './apiClient'
import { addCartItem, removeCartItem, updateCartItemQuantity, validateCart } from './cartApi'

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

describe('cart API', () => {
  it('adds an item with the buyer token', async () => {
    await addCartItem({ productId: 'p1', shopId: 's1', quantity: 2, unitPrice: 1000 })
    expect(sent().url).toBe('/api/cart/items')
    expect(JSON.parse(sent().data)).toEqual({
      productId: 'p1',
      variantId: null,
      shopId: 's1',
      quantity: 2,
      unitPrice: 1000,
    })
    expect(sent().headers.get('Authorization')).toBe('Bearer token-1')
  })

  it('addresses a line by product id and variant query', async () => {
    await updateCartItemQuantity('p/1', 'v1', 3)
    expect(sent().method).toBe('patch')
    expect(sent().url).toBe('/api/cart/items/p%2F1')
    expect(sent().params).toEqual({ variantId: 'v1' })
    expect(JSON.parse(sent().data)).toEqual({ quantity: 3 })
  })

  it('omits the variant query for items without a variant', async () => {
    await removeCartItem('p1', null)
    expect(sent().method).toBe('delete')
    expect(sent().params).toBeUndefined()
  })

  it('validates the cart before checkout', async () => {
    await validateCart()
    expect(sent().method).toBe('post')
    expect(sent().url).toBe('/api/cart/validate')
  })
})
