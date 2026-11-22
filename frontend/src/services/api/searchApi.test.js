import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager } from './apiClient'
import {
  isSearchUnavailable,
  searchByImage,
  searchProducts,
  SEARCH_SORT,
  suggestKeywords,
} from './searchApi'

const originalAdapter = apiClient.defaults.adapter
let adapter
let response

beforeEach(() => {
  response = {}
  bindSessionManager({
    getSnapshot: () => ({ session: { userId: 'u1', accessToken: 'token-123' } }),
    getGeneration: () => 1,
  })
  adapter = vi.fn(async (config) => ({ data: response, status: 200, config, headers: {} }))
  apiClient.defaults.adapter = adapter
})

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
  bindSessionManager(undefined)
})

const sent = () => adapter.mock.calls[0][0]

describe('search API', () => {
  it('searches by keyword with only the filters that are set, without the token', async () => {
    response = { results: [{ id: 'p1' }], totalElements: 41, totalPages: 3, page: 1, size: 20 }
    await expect(
      searchProducts({ keyword: '  áo polo ', minPrice: 0, categoryId: '', page: 1 }),
    ).resolves.toEqual({
      content: [{ id: 'p1' }],
      totalElements: 41,
      totalPages: 3,
      page: 1,
      size: 20,
    })
    expect(sent().url).toBe('/api/ai/search')
    // Search rejects unknown or empty parameters, so nothing else may be sent.
    expect(sent().params).toEqual({
      keyword: 'áo polo',
      minPrice: 0,
      sort: SEARCH_SORT.relevance,
      page: 1,
      size: 20,
    })
    expect(sent().headers.get('Authorization')).toBeFalsy()
  })

  it('caps the page count at the 10,000-result window', async () => {
    response = { results: [], totalElements: 50000, totalPages: 2500 }
    expect((await searchProducts({ keyword: 'áo', size: 20 })).totalPages).toBe(500)
  })

  it('asks for at most 8 suggestions and skips keywords under two characters', async () => {
    await expect(suggestKeywords(' a ')).resolves.toEqual([])
    expect(adapter).not.toHaveBeenCalled()
    response = { keyword: 'áo', results: [{ id: 'p1', name: 'Áo polo' }] }
    const controller = new AbortController()
    await expect(suggestKeywords('áo', { signal: controller.signal })).resolves.toEqual([
      { id: 'p1', name: 'Áo polo' },
    ])
    expect(sent().url).toBe('/api/ai/search/suggestions')
    expect(sent().params).toEqual({ keyword: 'áo', limit: 8 })
    expect(sent().signal).toBe(controller.signal)
  })

  it('uploads an image as multipart with filters in the query', async () => {
    response = { results: [{ id: 'p1', similarity: 0.9 }], limit: 20, totalReturned: 1 }
    const file = new File(['x'], 'polo.jpg', { type: 'image/jpeg' })
    await expect(searchByImage(file, { maxPrice: 500000, categoryId: 'c1' })).resolves.toEqual([
      { id: 'p1', similarity: 0.9 },
    ])
    expect(sent().method).toBe('post')
    expect(sent().url).toBe('/api/ai/search/image')
    expect(sent().data).toBeInstanceOf(FormData)
    expect(sent().data.get('file')).toBe(file)
    expect([...sent().data.keys()]).toEqual(['file'])
    expect(sent().params).toEqual({ maxPrice: 500000, categoryId: 'c1', limit: 20 })
    expect(sent().timeout).toBe(30000)
    expect(sent().headers.get('Authorization')).toBeFalsy()
  })

  it.each([
    [{ response: { status: 503 } }, true],
    [{ response: { status: 502 } }, true],
    [{ code: 'ERR_NETWORK' }, true],
    [{ code: 'ERR_CANCELED' }, false],
    [{ response: { status: 400 } }, false],
    [{ response: { status: 429 } }, false],
  ])('decides whether to fall back to basic search (%#)', (error, expected) => {
    expect(isSearchUnavailable(error)).toBe(expected)
  })
})
