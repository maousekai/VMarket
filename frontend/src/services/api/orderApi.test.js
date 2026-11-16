import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager } from './apiClient'
import { cancelOrder, createReturn, normalizePage, placeOrder } from './orderApi'

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

describe('order API', () => {
  it('places an order from the cart with an Idempotency-Key', async () => {
    await placeOrder({ addressId: 'a1', note: '  ', paymentMethod: 'COD' }, 'key-9')
    expect(sent().url).toBe('/api/orders')
    expect(JSON.parse(sent().data)).toEqual({ addressId: 'a1', note: null, paymentMethod: 'COD' })
    expect(sent().headers.get('Idempotency-Key')).toBe('key-9')
  })

  it('cancels and requests returns on the buyer endpoints', async () => {
    await cancelOrder('o1')
    expect(sent().method).toBe('put')
    expect(sent().url).toBe('/api/orders/o1/cancel')
    adapter.mockClear()
    await createReturn('o1', { reason: 'Lỗi', evidenceUrls: [], items: [{ orderItemId: 'i1' }] })
    expect(sent().url).toBe('/api/orders/o1/returns')
  })

  it('normalizes both Spring page serializations', () => {
    expect(
      normalizePage({ content: [1], number: 2, size: 10, totalElements: 21, totalPages: 3 }),
    ).toEqual({ content: [1], page: 2, size: 10, totalElements: 21, totalPages: 3 })
    expect(
      normalizePage({
        content: [1],
        page: { number: 1, size: 5, totalElements: 6, totalPages: 2 },
      }),
    ).toEqual({ content: [1], page: 1, size: 5, totalElements: 6, totalPages: 2 })
  })
})
