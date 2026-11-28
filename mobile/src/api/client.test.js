import axios from 'axios'
import * as SecureStore from 'expo-secure-store'
import client, {
  onSessionChange,
  refreshAccessToken,
  signOutLocally,
  storeAuthResponse,
} from './client'

const token = (sub) =>
  `h.${Buffer.from(JSON.stringify({ sub, roles: ['BUYER'] })).toString('base64url')}.s`

function respond(status, data = {}) {
  if (status < 400) return Promise.resolve({ status, data, headers: {}, config: {} })
  const error = new Error(`HTTP ${status}`)
  error.response = { status, data }
  return Promise.reject(error)
}

let adapter
beforeEach(async () => {
  SecureStore.__store.clear()
  adapter = jest.fn()
  client.defaults.adapter = (config) => {
    const result = adapter(config)
    return result.catch
      ? result.catch((error) => Promise.reject(Object.assign(error, { config })))
      : result
  }
  await signOutLocally()
})
afterEach(() => jest.restoreAllMocks())

async function signIn() {
  await storeAuthResponse({
    data: { accessToken: token('old') },
    headers: { 'set-cookie': ['refresh_token=r1; Path=/api/auth; HttpOnly'] },
  })
}

it('stores the refresh token from Set-Cookie in SecureStore and attaches the access token', async () => {
  await signIn()
  expect(SecureStore.__store.get('vmarket.refreshToken')).toBe('r1')
  adapter.mockImplementation((config) => {
    expect(config.headers.Authorization).toBe(`Bearer ${token('old')}`)
    return respond(200, { ok: true })
  })
  expect((await client.get('/api/x')).data).toEqual({ ok: true })
})

it('refreshes once for concurrent 401s and replays every request', async () => {
  await signIn()
  const refresh = jest.spyOn(axios, 'post').mockImplementation(async (url, body, options) => {
    expect(url).toMatch(/\/api\/auth\/refresh$/)
    expect(options.headers.Cookie).toBe('refresh_token=r1')
    return {
      data: { accessToken: token('new') },
      headers: { 'set-cookie': ['refresh_token=r2; HttpOnly'] },
    }
  })
  adapter.mockImplementation((config) =>
    config.headers.Authorization === `Bearer ${token('new')}`
      ? respond(200, { url: config.url })
      : respond(401),
  )
  const [a, b] = await Promise.all([client.get('/api/a'), client.get('/api/b')])
  expect([a.data.url, b.data.url]).toEqual(['/api/a', '/api/b'])
  expect(refresh).toHaveBeenCalledTimes(1)
  expect(SecureStore.__store.get('vmarket.refreshToken')).toBe('r2')
})

it('signs out when the refresh token is rejected', async () => {
  await signIn()
  const changes = []
  const stop = onSessionChange((value) => changes.push(value))
  jest.spyOn(axios, 'post').mockImplementation(() => respond(401))
  adapter.mockImplementation(() => respond(401))
  await expect(client.get('/api/a')).rejects.toThrow('HTTP 401')
  expect(changes).toEqual([null])
  expect(SecureStore.__store.has('vmarket.refreshToken')).toBe(false)
  stop()
})

it('keeps the session when refresh fails only because the network is down', async () => {
  await signIn()
  jest.spyOn(axios, 'post').mockRejectedValue(new Error('Network Error'))
  adapter.mockImplementation(() => respond(401))
  await expect(client.get('/api/a')).rejects.toThrow('HTTP 401')
  expect(SecureStore.__store.get('vmarket.refreshToken')).toBe('r1')
})

it('ends the session when a direct refresh is rejected because the account was suspended', async () => {
  await signIn()
  const changed = jest.fn()
  const unsubscribe = onSessionChange(changed)
  jest
    .spyOn(axios, 'post')
    .mockImplementation(() => respond(423, { error: { code: 'ACCOUNT_SUSPENDED' } }))

  await expect(refreshAccessToken()).rejects.toThrow('HTTP 423')

  expect(changed).toHaveBeenCalledWith(null)
  expect(SecureStore.__store.has('vmarket.refreshToken')).toBe(false)
  unsubscribe()
})
