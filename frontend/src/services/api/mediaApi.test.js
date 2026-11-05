// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import apiClient from './apiClient'
import {
  deleteMyMedia,
  deleteMyShopMedia,
  uploadMyAvatar,
  uploadMyShopCover,
  uploadMyShopLogo,
} from './mediaApi'

const originalAdapter = apiClient.defaults.adapter

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
})

function capture() {
  const calls = []
  apiClient.defaults.adapter = async (config) => {
    calls.push(config)
    return { data: { url: 'http://localhost:9000/vmarket-media/x.png' }, status: 200, config }
  }
  return calls
}

const file = new File([new Uint8Array([1, 2, 3])], 'anh.png', { type: 'image/png' })

describe('media API', () => {
  it.each([
    [uploadMyAvatar, '/api/users/me/avatar'],
    [uploadMyShopLogo, '/api/shops/me/logo'],
    [uploadMyShopCover, '/api/shops/me/cover'],
  ])('posts multipart FormData to %s', async (call, path) => {
    const calls = capture()
    await call(file)
    const config = calls[0]
    expect(config.url).toBe(path)
    expect(config.data).toBeInstanceOf(FormData)
    expect(config.data.get('file')).toBe(file)
    expect(config.timeout).toBe(45_000)
    // The browser must set the multipart boundary, so no JSON content type is forced.
    expect(config.headers.get('Content-Type')).not.toBe('application/json')
  })

  it('deletes media by URL through the user and shop endpoints', async () => {
    const calls = capture()
    await deleteMyMedia('http://localhost:9000/vmarket-media/avatars/u/a.png')
    await deleteMyShopMedia('http://localhost:9000/vmarket-media/shops/u/logo/a.png')

    expect(calls[0].url).toBe('/api/users/me/media')
    expect(calls[0].params).toEqual({
      url: 'http://localhost:9000/vmarket-media/avatars/u/a.png',
    })
    expect(calls[1].url).toBe('/api/shops/me/media')
    expect(calls[1].params).toEqual({
      url: 'http://localhost:9000/vmarket-media/shops/u/logo/a.png',
    })
  })
})
