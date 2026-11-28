import { claimsFromToken, isShipper, refreshTokenFromHeaders, sessionFromToken } from './session'

const token = (claims) =>
  `header.${Buffer.from(JSON.stringify(claims)).toString('base64url')}.signature`

describe('sessionFromToken', () => {
  it('reads the user and roles from the access token', () => {
    const session = sessionFromToken(
      token({ sub: 'u1', email: 'a@b.vn', username: 'an', roles: ['SHIPPER'], exp: 100 }),
    )
    expect(session).toMatchObject({
      userId: 'u1',
      email: 'a@b.vn',
      username: 'an',
      roles: ['SHIPPER'],
      expiresAt: 100000,
    })
    expect(isShipper(session)).toBe(true)
  })

  it('decodes non-ASCII claims', () => {
    expect(claimsFromToken(token({ sub: 'u1', username: 'Nguyễn' })).username).toBe('Nguyễn')
  })

  it('rejects broken tokens', () => {
    expect(sessionFromToken('nope')).toBeNull()
    expect(sessionFromToken(token({ email: 'no-sub' }))).toBeNull()
  })

  it('treats buyers as not shippers', () => {
    expect(isShipper(sessionFromToken(token({ sub: 'u', roles: ['BUYER'] })))).toBe(false)
  })
})

describe('refreshTokenFromHeaders', () => {
  it('extracts the HttpOnly refresh_token cookie', () => {
    expect(
      refreshTokenFromHeaders({
        'set-cookie': ['refresh_token=abc.def; Path=/api/auth; HttpOnly; SameSite=Lax'],
      }),
    ).toBe('abc.def')
  })

  it('handles several cookies folded into one header', () => {
    expect(
      refreshTokenFromHeaders({
        'set-cookie': 'other=1; Path=/, refresh_token=xyz; Expires=Wed, 01 Jan 2027 00:00:00 GMT',
      }),
    ).toBe('xyz')
  })

  it('returns null when the cookie is missing or cleared', () => {
    expect(refreshTokenFromHeaders({})).toBeNull()
    expect(refreshTokenFromHeaders({ 'set-cookie': ['refresh_token=; Max-Age=0'] })).toBeNull()
  })
})
