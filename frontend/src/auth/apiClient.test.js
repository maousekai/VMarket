import { afterEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager, getApiError } from '../services/api/apiClient'
import { loginAccount } from '../services/api/authApi'

function setup() {
  let session = { accessToken: 'old-access-token' }
  let generation = 1
  const manager = {
    getSnapshot: () => ({ session }),
    getGeneration: () => generation,
    refresh: vi.fn(async () => {
      session = { accessToken: 'new-access-token' }
      return session
    }),
    invalidateSession: vi.fn(() => {
      generation += 1
      session = null
    }),
  }
  bindSessionManager(manager)
  return {
    manager,
    replaceSession: (next) => {
      session = next
    },
  }
}

const rejected = (config, status = 401, code = 'UNAUTHORIZED') =>
  Object.assign(new Error(code), {
    config,
    response: { status, data: { error: { code, message: code } } },
  })
const successful = (config) => ({ data: { okay: true }, status: 200, config, headers: {} })

afterEach(() => bindSessionManager(undefined))

describe('login API contract', () => {
  it.each(['an@example.com', 'An@Example.com'])(
    'sends email %s through the real API wrapper',
    async (email) => {
      const original = apiClient.defaults.adapter
      const adapter = vi.fn(async (config) => successful(config))
      apiClient.defaults.adapter = adapter
      try {
        await loginAccount({ email, password: 'legacy' })
        const config = adapter.mock.calls[0][0]
        expect(config.url).toBe('/api/auth/login')
        expect(JSON.parse(config.data)).toEqual({ email, password: 'legacy' })
      } finally {
        apiClient.defaults.adapter = original
      }
    },
  )
})

describe('protected API requests', () => {
  it('uses a refreshed token and retries the original request exactly once', async () => {
    const { manager } = setup()
    const adapter = vi
      .fn()
      .mockImplementationOnce(async (config) => {
        throw rejected(config)
      })
      .mockImplementationOnce(async (config) => successful(config))
    await apiClient.get('/api/users/me', { adapter })
    expect(manager.refresh).toHaveBeenCalledOnce()
    expect(adapter).toHaveBeenCalledTimes(2)
    expect(adapter.mock.calls[1][0].headers.get('Authorization')).toBe('Bearer new-access-token')
  })

  it('never refreshes login, OTP, reset-password, or other Auth requests', async () => {
    const { manager } = setup()
    for (const path of ['/login', '/otp/verify', '/password/reset', '/refresh']) {
      await expect(
        apiClient.post(
          `/api/auth${path}`,
          {},
          {
            adapter: async (config) => {
              throw rejected(config)
            },
          },
        ),
      ).rejects.toThrow('UNAUTHORIZED')
    }
    expect(manager.refresh).not.toHaveBeenCalled()
  })

  it('does not enter another refresh cycle when the retried request still returns 401', async () => {
    const { manager } = setup()
    const adapter = vi.fn(async (config) => {
      throw rejected(config)
    })
    await expect(apiClient.get('/api/users/me', { adapter })).rejects.toThrow('UNAUTHORIZED')
    expect(manager.refresh).toHaveBeenCalledOnce()
    expect(adapter).toHaveBeenCalledTimes(2)
  })

  it('reuses a token that another request already refreshed', async () => {
    const { manager, replaceSession } = setup()
    const adapter = vi
      .fn()
      .mockImplementationOnce(async (config) => {
        replaceSession({ accessToken: 'already-refreshed' })
        throw rejected(config)
      })
      .mockImplementationOnce(async (config) => successful(config))
    await apiClient.get('/api/users/me', { adapter })
    expect(manager.refresh).not.toHaveBeenCalled()
    expect(adapter.mock.calls[1][0].headers.get('Authorization')).toBe('Bearer already-refreshed')
  })

  it('does not retry a request after the session identity has changed', async () => {
    const { manager } = setup()
    const adapter = vi.fn(async (config) => {
      manager.invalidateSession()
      throw rejected(config)
    })
    await expect(apiClient.get('/api/users/me', { adapter })).rejects.toThrow('UNAUTHORIZED')
    expect(manager.refresh).not.toHaveBeenCalled()
    expect(adapter).toHaveBeenCalledOnce()
  })

  it('invalidates a suspended account without triggering refresh', async () => {
    const { manager } = setup()
    const adapter = vi.fn(async (config) => {
      throw rejected(config, 423, 'ACCOUNT_SUSPENDED')
    })
    await expect(apiClient.get('/api/users/me', { adapter })).rejects.toThrow('ACCOUNT_SUSPENDED')
    expect(manager.invalidateSession).toHaveBeenCalledOnce()
    expect(manager.refresh).not.toHaveBeenCalled()
  })

  it('keeps the current session when a password operation is temporarily locked', async () => {
    const { manager } = setup()
    const adapter = vi.fn(async (config) => {
      throw rejected(config, 423, 'ACCOUNT_LOCKED')
    })
    await expect(apiClient.put('/api/users/me/password', {}, { adapter })).rejects.toThrow(
      'ACCOUNT_LOCKED',
    )
    expect(manager.invalidateSession).not.toHaveBeenCalled()
    expect(manager.refresh).not.toHaveBeenCalled()
    expect(manager.getSnapshot().session.accessToken).toBe('old-access-token')
  })

  it('exposes backend field errors and a retryable network error', () => {
    expect(
      getApiError({
        response: {
          status: 400,
          data: {
            error: {
              code: 'VALIDATION_ERROR',
              message: 'Dữ liệu đầu vào không hợp lệ',
              details: [{ field: 'phone', message: 'Số điện thoại không hợp lệ' }],
            },
          },
        },
      }),
    ).toMatchObject({ status: 400, fieldErrors: { phone: 'Số điện thoại không hợp lệ' } })
    expect(getApiError(new Error('network disconnected'))).toMatchObject({
      retryable: true,
      status: 0,
    })
  })
})
