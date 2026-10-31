import { describe, expect, it } from 'vitest'
import { validateAvatarUrl } from './avatarValidation'

describe('avatar URL validation', () => {
  it('accepts an empty value and http(s) URLs', () => {
    expect(validateAvatarUrl({})).toEqual({})
    expect(validateAvatarUrl({ avatarUrl: '   ' })).toEqual({})
    expect(validateAvatarUrl({ avatarUrl: 'https://cdn.vmarket.vn/avatars/an.jpg' })).toEqual({})
    expect(validateAvatarUrl({ avatarUrl: ' http://example.com/a.png?size=64 ' })).toEqual({})
  })

  it.each([
    'ftp://example.com/a.png',
    'example.com/a.png',
    'javascript:alert(1)',
    'data:image/png;base64,AAAA',
    'HTTPS://example.com/a.png',
    'https://',
    'https://exa mple.com/a.png',
  ])('rejects %s', (avatarUrl) => {
    expect(validateAvatarUrl({ avatarUrl })).toHaveProperty('avatarUrl')
  })

  it('limits the URL to 500 characters', () => {
    const base = 'https://example.com/'
    expect(validateAvatarUrl({ avatarUrl: base + 'a'.repeat(500 - base.length) })).toEqual({})
    expect(validateAvatarUrl({ avatarUrl: base + 'a'.repeat(501 - base.length) })).toHaveProperty(
      'avatarUrl',
    )
  })
})
